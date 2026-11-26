package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func mobileReadRequest(t *testing.T, store *FakeStore, method, path, body string, status int) map[string]any {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if method == http.MethodPost {
		req.Header.Set("Content-Type", "application/json")
	}
	rr := httptest.NewRecorder()
	NewRouter(NewHandler(store, 42)).ServeHTTP(rr, req)

	if rr.Code != status {
		t.Fatalf("status = %d, want %d (body: %s)", rr.Code, status, rr.Body.String())
	}
	if got := rr.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", got)
	}
	var response map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return response
}

func assertMobileReadBody(t *testing.T, got, want map[string]any) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Errorf("response = %#v, want %#v", got, want)
	}
}

func assertMobileReadError(t *testing.T, got map[string]any, code, field string) {
	t.Helper()
	detail, _ := got["error"].(map[string]any)
	message, _ := detail["message"].(string)
	if message == "" {
		t.Fatalf("missing error message in %#v", got)
	}
	want := map[string]any{"code": code, "message": message}
	if field != "" {
		want["details"] = map[string]any{"field": field}
	}
	assertMobileReadBody(t, got, map[string]any{"error": want})
}

func TestMobileReadContract_SetupGet(t *testing.T) {
	const uid = "_w" // One opaque byte (0xff), with a padded spelling "_w==".
	setup := fakeSetup{sigPk: b64n(32), cidNonce: b64n(24), cidCt: b64n(96), cidTag: b64n(16), lastTs: 7}
	store := NewFakeStore()
	store.setups[uid] = setup

	for _, input := range []string{uid, "_w=="} {
		t.Run(input, func(t *testing.T) {
			got := mobileReadRequest(t, store, http.MethodGet, "/v1/setup/"+input, "", http.StatusOK)
			assertMobileReadBody(t, got, map[string]any{
				"uid_b64": uid, "sig_pk_b64": setup.sigPk,
				"cid": map[string]any{"nonce": setup.cidNonce, "ct": setup.cidCt, "tag": setup.cidTag},
			})
		})
	}

	t.Run("missing", func(t *testing.T) {
		got := mobileReadRequest(t, store, http.MethodGet, "/v1/setup/AA", "", http.StatusNotFound)
		assertMobileReadError(t, got, "not_found", "")
	})
	t.Run("malformed_base64", func(t *testing.T) {
		got := mobileReadRequest(t, store, http.MethodGet, "/v1/setup/invalid!", "", http.StatusBadRequest)
		assertMobileReadError(t, got, "invalid_uid", "uid_b64")
	})
	t.Run("store_error", func(t *testing.T) {
		store := NewFakeStore()
		store.setupErr = errors.New("test setup read failure")
		got := mobileReadRequest(t, store, http.MethodGet, "/v1/setup/"+uid, "", http.StatusInternalServerError)
		assertMobileReadError(t, got, "internal_error", "")
	})
}

func TestMobileReadContract_ToprfEval(t *testing.T) {
	// RFC 9496, Appendix A.1 Ristretto255 encodings of B and 2*B.
	const basePoint = "4vKuCmq8TnGohKlhxQBRX1jjC2qlgt2NtqZZReCNLXY"
	const doubleBasePoint = "akkyEPdJnNF_7LUQrgzqI6EQ6NW5AfisrdMJXHOjuRk"
	const uid = "AA"
	scalarTwo := [32]byte{2} // Canonical little-endian scalar.
	store := NewFakeStore()
	store.setups[uid] = fakeSetup{kI: base64.RawURLEncoding.EncodeToString(scalarTwo[:])}
	store.setups["AQ"] = fakeSetup{kI: b64n(31)}
	store.setups["Ag"] = fakeSetup{kI: b64n(32)} // Correct length, noncanonical scalar.

	request := func(uid, blinded string) string {
		return fmt.Sprintf(`{"uid_b64":%q,"blinded_b64":%q}`, uid, blinded)
	}
	const path = "/v1/toprf/eval"
	t.Run("valid", func(t *testing.T) {
		got := mobileReadRequest(t, store, http.MethodPost, path, request(uid, basePoint), http.StatusOK)
		assertMobileReadBody(t, got, map[string]any{"sp_id": float64(42), "y_b64": doubleBasePoint})
	})

	for _, tc := range []struct {
		name, body  string
		status      int
		code, field string
	}{
		{"missing", request("Aw", basePoint), http.StatusNotFound, "not_found", ""},
		{"malformed_json", "{", http.StatusBadRequest, "invalid_json", ""},
		{"empty_uid", request("", basePoint), http.StatusBadRequest, "invalid_uid", "uid_b64"},
		{"malformed_uid", request("!", basePoint), http.StatusBadRequest, "invalid_uid", "uid_b64"},
		{"malformed_blinded", request(uid, "!"), http.StatusBadRequest, "invalid_blinded", "blinded_b64"},
		{"short_blinded", request(uid, b64n(31)), http.StatusBadRequest, "invalid_blinded", "blinded_b64"},
		{"long_blinded", request(uid, b64n(33)), http.StatusBadRequest, "invalid_blinded", "blinded_b64"},
		{"invalid_point", request(uid, b64n(32)), http.StatusBadRequest, "invalid_toprf_input", ""},
		{"stored_short_scalar", request("AQ", basePoint), http.StatusInternalServerError, "stored_invalid_k_i", ""},
		{"stored_noncanonical_scalar", request("Ag", basePoint), http.StatusBadRequest, "invalid_toprf_input", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := mobileReadRequest(t, store, http.MethodPost, path, tc.body, tc.status)
			assertMobileReadError(t, got, tc.code, tc.field)
		})
	}

	t.Run("store_error", func(t *testing.T) {
		store := NewFakeStore()
		store.kiErr = errors.New("test scalar read failure")
		got := mobileReadRequest(t, store, http.MethodPost, path, request(uid, basePoint), http.StatusInternalServerError)
		assertMobileReadError(t, got, "internal_error", "")
	})
}

func TestMobileReadContract_RecordGet(t *testing.T) {
	suid := b64n(32)
	record := fakeRecord{nonce: b64n(24), ct: b64n(40), tag: b64n(16)}
	store := NewFakeStore()
	store.records[suid] = record
	const path = "/v1/records/"

	t.Run("valid", func(t *testing.T) {
		got := mobileReadRequest(t, store, http.MethodGet, path+suid, "", http.StatusOK)
		assertMobileReadBody(t, got, map[string]any{
			"suid_b64": suid,
			"cj":       map[string]any{"nonce": record.nonce, "ct": record.ct, "tag": record.tag},
		})
	})

	for _, tc := range []struct {
		name, suid string
	}{
		{"malformed_base64", "!"},
		{"short_suid", b64n(31)},
		{"long_suid", b64n(33)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := mobileReadRequest(t, store, http.MethodGet, path+tc.suid, "", http.StatusBadRequest)
			assertMobileReadError(t, got, "invalid_suid", "suid_b64")
		})
	}

	t.Run("missing", func(t *testing.T) {
		got := mobileReadRequest(t, NewFakeStore(), http.MethodGet, path+suid, "", http.StatusNotFound)
		assertMobileReadError(t, got, "not_found", "")
	})
	t.Run("store_error", func(t *testing.T) {
		store := NewFakeStore()
		store.recErr = errors.New("test record read failure")
		got := mobileReadRequest(t, store, http.MethodGet, path+suid, "", http.StatusInternalServerError)
		assertMobileReadError(t, got, "internal_error", "")
	})
}
