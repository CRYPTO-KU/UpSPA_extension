# Storage provider read contract for mobile

PR target is `mobile-dev` at `dfaf1d1ef7c7c4f3193f64f6c3ea03e50d36d417`.
The public OpenAPI spec and its embedded Go test copy are byte-for-byte identical. This
inventory also uses the Go router, handlers, and models, and the TypeScript HTTP client and
its caller. [Public OpenAPI](openapi/sp.yaml) ·
[Embedded copy](../services/storage-provider-go/internal/api/testdata/sp.yaml) ·
[Go router](../services/storage-provider-go/internal/api/routes.go) ·
[Go models](../services/storage-provider-go/internal/model/types.go) ·
[TypeScript HTTP client](../packages/upspa-js/src/spClient.ts) ·
[TypeScript caller](../packages/upspa-js/src/upspaClient.ts)

## Requests and responses

All lengths below are decoded bytes. Send canonical base64url without padding. Go accepts both
raw and correctly padded URL-safe base64 and normalizes accepted lookup keys to raw base64url.
Its decoder uses Go's non-strict encodings, which also ignore CR and LF. UID is any nonempty byte
sequence at this API boundary. The TypeScript read and evaluation methods encode a raw UID string
as UTF-8 before base64url. The TypeScript setup writer forwards an already encoded UID, and
the record reader takes an already encoded SUID.
[Go base64 decoder](../services/storage-provider-go/internal/crypto/b64.go)

| Request | HTTP 200 JSON | Handler rejection codes |
| --- | --- | --- |
| `GET /v1/setup/{uid_b64}` with a nonempty UID | `{uid_b64, sig_pk_b64, cid: {nonce, ct, tag}}`. Canonical UID echo, 32-byte Ed25519 verifying key, and a ciphertext blob with lengths 24/96/16. | `400 invalid_uid`, `404 not_found`, `500 internal_error` |
| `POST /v1/toprf/eval` with `{uid_b64, blinded_b64}`. The UID is nonempty and the blinded point is 32 bytes. | `{sp_id, y_b64}`. Provider ID as a Go `uint32` JSON number and a 32-byte encoded Ristretto255 result. | `400 invalid_json`, `400 invalid_uid`, `400 invalid_blinded`, `400 invalid_toprf_input`, `404 not_found`, `500 stored_invalid_k_i`, `500 internal_error` |
| `GET /v1/records/{suid_b64}` with a 32-byte SUID | `{suid_b64, cj: {nonce, ct, tag}}`. Canonical SUID echo and a ciphertext blob with lengths 24/40/16. | `400 invalid_suid`, `404 not_found`, `500 internal_error` |

Handler responses use `application/json`. Errors have the shape
`{"error":{"code":"…","message":"…"}}`. Field validation errors add
`"details":{"field":"uid_b64"}` (or `blinded_b64` or `suid_b64`) inside `error`.
Branch on the status and code, with message text used for diagnostics. A missing GET path
segment can be rejected by the router before the handler, outside this JSON contract.
[Setup GET](../services/storage-provider-go/internal/api/setup_get.go) ·
[TOPRF handler](../services/storage-provider-go/internal/api/toprf.go) ·
[Record handlers](../services/storage-provider-go/internal/api/records.go) ·
[JSON helpers](../services/storage-provider-go/internal/api/helpers.go)

## Server checks and mobile acceptance

- On setup and record reads, Go validates and canonicalizes the lookup key, then echoes it and
  returns the stored fields. Fixed response blob and key lengths come from write-path validation.
  The GET handlers return stored strings directly. Mobile must check the echoed UID or SUID
  against the requested bytes and validate each encoded field and its decoded length.
- On TOPRF evaluation, Go bounds request JSON to 8 KiB, rejects unknown fields and trailing JSON, and
  checks a supplied content type for `application/json`. It validates the UID and blinded
  encoding and length, then loads the stored share. With a usable row, it decodes the canonical
  scalar and Ristretto point and returns their product. A missing row yields 404 even if the
  supplied 32 bytes would fail point decoding. The response contains a point and no proof of
  correct evaluation, so point validation alone cannot establish a correct share.
- Only TOPRF carries `sp_id`, from the service's `SP_ID`
  configuration. An unspecified or zero ID results in handler ID 1. Mobile must bind each
  reply to the configured HTTPS provider and to the outstanding request, operation, phase, and
  deadline. Check that the TOPRF ID matches the configured provider's nonzero ID. The setup
  and record echoes identify lookup keys, while the transport and request context bind them
  to the provider. Reject unknown, expired, duplicate, and contradictory evidence.
- HTTP 200 produces a candidate reply. Bound the response size and validate JSON
  shape, field types, canonical base64url, decoded lengths, and TOPRF point encoding before
  passing the data into the shared Rust workflow. Authenticate `cid` and `cj` with the existing
  XChaCha20-Poly1305 protocol and associated data. Check the setup key against authenticated
  enrollment or the verifying key derived from the authenticated setup. Count at most one
  accepted response per configured provider and apply the shared workflow's matching-evidence
  rules. The current API exposes no authenticated epoch or read snapshot. A valid tag or matching
  echo alone cannot establish freshness or a common lineage across providers.

The TypeScript HTTP client applies a fetch timeout and parses successful JSON with type assertions.
`getSetup` returns the parsed object but its declared type omits `uid_b64`. `getRecord` returns
only `cj`, and `toprfEval` maps the body to `{id, y}` without checking the claimed ID against
the descriptor. The higher-level caller counts fulfilled requests before passing data to WASM.
Mobile acceptance requires the checks above.
[Provider config](../services/storage-provider-go/internal/config/config.go) ·
[Rust ciphertext layout and AAD](../crates/upspa-core/src/protocol/mod.rs) ·
[Mobile architecture](mobile-architecture.md#quorum-and-recovery-semantics)

## Differences and minimal reproducers

- The documented wire spelling is unpadded. Seed setup under `_w`
  and request `GET /v1/setup/_w==`. The handler returns 200 and echoes `_w`. See the
  `TestMobileReadContract_SetupGet/_w==` subtest.
- Both OpenAPI copies declare 200/400/404 for
  these reads. Set the corresponding fake store error and make a well-formed request to
  reproduce `500 internal_error` in each `store_error` subtest. With a valid blinded point,
  a stored 31-byte scalar produces `500 stored_invalid_k_i`, while a stored 32-byte noncanonical
  scalar produces `400 invalid_toprf_input`. Both stored scalar cases are in the TOPRF suite.
- The shared OpenAPI `CtBlob.ct` schema accepts any
  string. Seed a setup or record row directly in the fake with `ct = "AA"` and GET its valid
  UID or SUID. Go returns 200 and forwards the one-byte ciphertext. Supplying the same
  ciphertext to the corresponding write handler yields `400 invalid_cid` or `400 invalid_cj`.
- With a mocked fetch returning
  `{"sp_id":2,"y_b64":"…"}`, call `toprfEval` on a client configured with ID 1.
  The method returns `{id: 2, y: "…"}`. For UID encoding, `getSetup("alice")` requests
  `/v1/setup/YWxpY2U`, while `getSetup("YWxpY2U")` requests `/v1/setup/WVd4cFkyVQ`.
  An isolated Node run with a mocked fetch reproduced both behaviors against the current client.
- OpenAPI describes 200 for identical setup payloads. Submit two syntactically
  valid setup payloads with the same UID and different `cid.ct` values against the same
  store. The first returns 201, the second returns 200, and a GET still returns the first
  ciphertext. The handler uses the insert result and the Go store ignores UID conflicts.
  [Database queries](../services/storage-provider-go/internal/db/queries.go)

## Deferred API work

- Setup's existing-row success, record creation's conflict, and password
  update's timestamp replay rejection do not give clients an operation-level receipt for a
  retry after a lost response.
- There is no operation-status query, shared epoch or read snapshot, or
  staged activation. The stored password-update timestamp is a per-provider write guard.
  The encrypted per-record counter serves the existing site-secret workflow and supplies
  neither a setup/TOPRF epoch nor an operation-status receipt.
- There is no API to enumerate a user's SUIDs, query an
  ambiguous mutation by operation ID, or reconcile partial writes across providers. Recovery
  still depends on the appropriate catalog and identifiers. Mutation and reconciliation
  semantics belong to the later mobile milestone. [Mobile development plan](mobile-development-plan.md#m5--durable-mutations-and-distributed-recovery)

## Verification

The focused suite uses synthetic fixtures, `FakeStore`, `httptest`, and the real router. Its
TOPRF success uses the fixed encodings of the Ristretto255 generator and twice the generator.
[Handler tests](../services/storage-provider-go/internal/api/mobile_read_contract_test.go) ·
[RFC 9496 Appendix A.1](https://www.rfc-editor.org/rfc/rfc9496.html#appendix-A.1)

```sh
cmp docs/openapi/sp.yaml services/storage-provider-go/internal/api/testdata/sp.yaml
env -u DATABASE_URL go -C services/storage-provider-go test -count=1 -v -run '^TestMobileReadContract' ./internal/api
```

To reproduce the test sensitivity check, copy the Go module to a temporary directory. Run
`go -C "$scratch" test -count=1 -run '^TestMobileReadContract_ToprfEval$/^valid$' ./internal/api`
against the copy, then change `SpID: h.spID` to `SpID: 99` in the copied `internal/api/toprf.go`
and rerun the same command. The baseline passed. The modified copy failed in
`TestMobileReadContract_ToprfEval/valid` on the response assertion (got ID 99, wanted 42).

On Go 1.27.3 (Linux amd64), the focused suite passed on the upstream baseline.
`go -C services/storage-provider-go test -count=1 -p 1 -v ./...` also passed with no skips.
It used a disposable PostgreSQL 15 Alpine container on loopback.
Packages ran serially because the existing DB tests truncate tables. The container was stopped
after the run. An additional fake-store reproducer confirmed the differing setup retry and the
one-byte setup ciphertext behavior. The full TypeScript suite, mobile device tests, and CI were
outside this Go handler qualification.
