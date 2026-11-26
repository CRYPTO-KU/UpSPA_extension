package api
import (
	"bytes"
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
)
type fakeSetup struct {
	sigPk    string
	cidNonce string
	cidCt    string
	cidTag   string
	kI       string
	lastTs   int64
}
type fakeRecord struct{ nonce, ct, tag string }
type FakeStore struct {
	setups   map[string]fakeSetup
	records  map[string]fakeRecord
	setupErr error
	kiErr    error
	recErr   error
}
func NewFakeStore() *FakeStore {
	return &FakeStore{setups: map[string]fakeSetup{}, records: map[string]fakeRecord{}}
}
func b64n(n int) string { return base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{0xaa}, n)) }
func (s *FakeStore) PutSetup(ctx context.Context, uid, sigPk, cidNonce, cidCt, cidTag, kI string) (bool, error) {
	if _, ok := s.setups[uid]; ok {
		return false, nil
	}
	s.setups[uid] = fakeSetup{sigPk: sigPk, cidNonce: cidNonce, cidCt: cidCt, cidTag: cidTag, kI: kI}
	return true, nil
}
func (s *FakeStore) GetSetup(ctx context.Context, uid string) (sigPk, cidNonce, cidCt, cidTag, kI string, lastTs int64, found bool, err error) {
	if s.setupErr != nil {
		return "", "", "", "", "", 0, false, s.setupErr
	}
	row, ok := s.setups[uid]
	if !ok {
		return "", "", "", "", "", 0, false, nil
	}
	return row.sigPk, row.cidNonce, row.cidCt, row.cidTag, row.kI, row.lastTs, true, nil
}
func (s *FakeStore) GetKi(ctx context.Context, uid string) (string, bool, error) {
	if s.kiErr != nil {
		return "", false, s.kiErr
	}
	row, ok := s.setups[uid]
	if !ok {
		return "", false, nil
	}
	return row.kI, true, nil
}
func (s *FakeStore) CreateRecord(ctx context.Context, suid, cjNonce, cjCt, cjTag string) (bool, error) {
	if _, ok := s.records[suid]; ok {
		return false, nil
	}
	s.records[suid] = fakeRecord{cjNonce, cjCt, cjTag}
	return true, nil
}
func (s *FakeStore) GetRecord(ctx context.Context, suid string) (cjNonce, cjCt, cjTag string, found bool, err error) {
	if s.recErr != nil {
		return "", "", "", false, s.recErr
	}
	row, ok := s.records[suid]
	if !ok {
		return "", "", "", false, nil
	}
	return row.nonce, row.ct, row.tag, true, nil
}
func (s *FakeStore) UpdateRecord(ctx context.Context, suid, cjNonce, cjCt, cjTag string) (bool, error) {
	if _, ok := s.records[suid]; !ok {
		return false, nil
	}
	s.records[suid] = fakeRecord{cjNonce, cjCt, cjTag}
	return true, nil
}
func (s *FakeStore) DeleteRecord(ctx context.Context, suid string) (bool, error) {
	if _, ok := s.records[suid]; !ok {
		return false, nil
	}
	delete(s.records, suid)
	return true, nil
}
func (s *FakeStore) ApplyPasswordUpdate(ctx context.Context, uid string, ts int64, cidNonceNew, cidCtNew, cidTagNew, kINew string) (bool, error) {
	row, ok := s.setups[uid]
	if !ok {
		return false, nil
	}
	if ts <= row.lastTs {
		return false, nil
	}
	row.cidNonce, row.cidCt, row.cidTag, row.kI, row.lastTs = cidNonceNew, cidCtNew, cidTagNew, kINew, ts
	s.setups[uid] = row
	return true, nil
}
func TestSetupGet_NotFound(t *testing.T) {
	handler := NewHandler(NewFakeStore())
	req := httptest.NewRequest("GET", "/v1/setup/"+b64n(4), nil)
	req.SetPathValue("uid_b64", b64n(4))
	rr := httptest.NewRecorder()
	handler.SetupGet(rr, req)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rr.Code)
	}
}
func TestSetupGet_BadRequestMalformedBase64(t *testing.T) {
	handler := NewHandler(NewFakeStore())
	req := httptest.NewRequest("GET", "/v1/setup/inv!alid", nil)
	req.SetPathValue("uid_b64", "inv!alid")
	rr := httptest.NewRecorder()
	handler.SetupGet(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", rr.Code)
	}
}
func TestRecordCreate_Conflict(t *testing.T) {
	store := NewFakeStore()
	handler := NewHandler(store)
	suid := b64n(32)
	_, _ = store.CreateRecord(context.Background(), suid, b64n(24), b64n(40), b64n(16))
	body := `{"suid_b64":"` + suid + `","cj":{"nonce":"` + b64n(24) + `","ct":"` + b64n(40) + `","tag":"` + b64n(16) + `"}}`
	req := httptest.NewRequest("POST", "/v1/records", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	handler.RecordCreate(rr, req)
	if rr.Code != http.StatusConflict {
		t.Fatalf("expected 409, got %d body=%s", rr.Code, rr.Body.String())
	}
}
func TestSetupCreate_Success(t *testing.T) {
	handler := NewHandler(NewFakeStore())
	body := `{"uid_b64":"` + b64n(8) + `","sig_pk_b64":"` + b64n(32) + `","cid":{"nonce":"` + b64n(24) + `","ct":"` + b64n(96) + `","tag":"` + b64n(16) + `"},"k_i_b64":"` + b64n(32) + `"}`
	req := httptest.NewRequest("POST", "/v1/setup", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	handler.Setup(rr, req)
	if rr.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d body=%s", rr.Code, rr.Body.String())
	}
}
