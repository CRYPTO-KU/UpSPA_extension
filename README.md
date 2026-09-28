# UpSPA Browser Extension Demo Repository

UpSPA is a browser-extension-based prototype for **Updatable Single Password Authentication**. The goal is to let a user authenticate to many login servers with one human-memorable master password, while the login servers only see site/account-specific derived secrets. The user-side protocol runs in Rust/WASM and TypeScript, while Storage Providers (SPs) expose a small HTTP API and store only encrypted/opaque state.

This repository contains:

- Rust protocol core and WASM bindings.
- TypeScript client library for talking to Storage Providers.
- Chrome Manifest V3 browser extension.
- Go Storage Provider reference service.
- A lightweight local login server for browser demo testing.
- Protocol/API/security documentation and intern implementation notes.

## Development guidance

- [Contributing](CONTRIBUTING.md): branch workflow, verification and PR evidence.
- [AI working rules](docs/ai-working-rules.md) and [agent instructions](AGENTS.md).
- [Mobile architecture](docs/mobile-architecture.md): target design, invariants and open decisions.
- [Tentative mobile development plan](docs/mobile-development-plan.md): full product scope,
  implementation milestones and evidence required at each release gate.

Mobile development targets `mobile-dev`. The current Android implementation is a synthetic
provider shell, not a production credential provider. The mobile plan describes the full target
product and development lifecycle.

---

## 1. High-level architecture

```text
+----------------------+        +-------------------------------+
| Chrome Extension     |        | Light Login Server / Website  |
|                      |        |                               |
| - Options page       |        | /register                     |
| - Content script     |        | /login                        |
| - upspa-js client    |        | /secret-update                |
| - Rust/WASM protocol |        |                               |
+----------+-----------+        +-------------------------------+
           |
           | HTTP SP API
           |
  +--------+--------+    +--------+--------+    +--------+--------+
  | Storage Provider 1 |  | Storage Provider 2 |  | Storage Provider 3 |
  | localhost:8081    |  | localhost:8082    |  | localhost:8083    |
  | DB: upspa1        |  | DB: upspa2        |  | DB: upspa3        |
  +-------------------+  +-------------------+  +-------------------+
```

Recommended first demo parameters:

```text
nsp = 3 Storage Providers
threshold/tsp = 2
```

This means any two of the three SPs should be enough for authentication, while one SP alone is not enough.

---

## 2. Protocol-role separation

### Extension-only operations

These are **not related to any login server**:

1. **Setup / Provision**
   - User enters global UpSPA UID and master password in the extension options page.
   - Extension provisions protected setup state to the SPs.

2. **Master Password Update**
   - User enters old master password and new master password in the extension options page.
   - Extension updates the protection of `cid` and the TOPRF shares at SPs.
   - No login server is contacted.

### Login-server-related operations

These happen on a website/login server form:

1. **Registration**
   - User enters login-server account ID, for example `alice`, and master password.
   - Extension computes `vInfo` and submits it as the website password.

2. **Authentication**
   - User enters login-server account ID and master password.
   - Extension computes `vInfoPrime` and submits it as the website password.

3. **Secret Update**
   - User enters login-server account ID and master password.
   - Extension computes:
     - old login-server secret: `vInfoPrime`
     - new login-server secret: `vInfoNew`
   - The login server verifies the old secret and stores the new secret.
   - This rotates the LS-specific secret; it is **not** a master password update.

The login-server identifier used by the extension is:

```text
lsj = origin | account_id
```

Example:

```text
http://localhost:3000|alice
```

---

## 3. Repository layout

```text
.
├── Cargo.toml
├── Cargo.lock
├── package.json
├── package-lock.json
├── README.md
├── LICENSE
│
├── crates/
│   ├── upspa-core/
│   │   ├── src/
│   │   │   ├── aead.rs
│   │   │   ├── hash.rs
│   │   │   ├── toprf.rs
│   │   │   ├── sign.rs
│   │   │   ├── types.rs
│   │   │   └── protocol/
│   │   │       ├── setup.rs
│   │   │       ├── register.rs
│   │   │       ├── authenticate.rs
│   │   │       ├── secret_update.rs
│   │   │       └── password_update.rs
│   │   └── tests/
│   │       ├── integration_flows.rs
│   │       ├── vector_toprf.rs
│   │       ├── vectors_aead.rs
│   │       └── vectors_setup.rs
│   │
│   ├── upspa-wasm/
│   │   └── src/lib.rs
│   │
│   └── upspa-cli/
│       └── src/main.rs
│
├── packages/
│   ├── upspa-js/
│   │   ├── src/
│   │   │   ├── base64url.ts
│   │   │   ├── index.ts
│   │   │   ├── spClient.ts
│   │   │   ├── types.ts
│   │   │   ├── upspaClient.ts
│   │   │   ├── wasm.ts
│   │   │   └── wasm-pkg.d.ts
│   │   ├── test/
│   │   │   ├── base64url.test.ts
│   │   │   └── upspaClient.test.ts
│   │   └── wasm-pkg/
│   │       ├── upspa_wasm.js
│   │       ├── upspa_wasm_bg.wasm
│   │       └── upspa_wasm.d.ts
│   │
│   └── extension/
│       ├── src/
│       │   ├── manifest.ts
│       │   ├── background/index.ts
│       │   ├── content/index.ts
│       │   ├── options/options.html
│       │   ├── options/options.ts
│       │   ├── popup/popup.html
│       │   ├── popup/popup.ts
│       │   └── shared/
│       │       ├── config.ts
│       │       ├── messages.ts
│       │       └── upspaActions.ts
│       ├── vite.config.ts
│       └── tsconfig.json
│
├── services/
│   └── storage-provider-go/
│       ├── cmd/sp/main.go
│       ├── Dockerfile
│       ├── go.mod
│       ├── go.sum
│       └── internal/
│           ├── api/
│           ├── config/
│           ├── crypto/
│           ├── db/
│           ├── model/
│           └── testutil/
│
├── demo/
│   └── light-login-server/server.mjs
│
├── docs/
│   ├── apis.md
│   ├── browser-extension.md
│   ├── protocol-phases.md
│   └── security.md
│
├── scripts/
│   ├── build_wasm.sh
│   └── dev.md
│
└── tools/
    └── hooks/gaurd.sh
```

---

## 4. Prerequisites

Install these before running the full project:

| Tool | Needed for |
|---|---|
| Rust + Cargo | `upspa-core`, `upspa-wasm`, Rust tests |
| `wasm-pack` | Building `packages/upspa-js/wasm-pkg` from Rust/WASM |
| Node.js 20+ recommended | TypeScript, Vite, Vitest, extension build |
| npm | Workspaces and package scripts |
| Go | Storage Provider server |
| PostgreSQL 16 recommended | Storage Provider databases |
| Chrome / Chromium | Loading the extension |
| Docker optional | Alternative Postgres/test setup |

Check versions:

```bash
rustc --version
cargo --version
node -v
npm -v
go version
psql --version
```

Install `wasm-pack` if missing:

```bash
cargo install wasm-pack
```

---

## 5. Install JavaScript dependencies

Run from the repository root:

```bash
npm install
```

Do **not** run `npm install` inside `packages/upspa-js` or `packages/extension` first. This repository uses npm workspaces from the root.

If npm tries to use a wrong/internal registry, set the public npm registry:

```bash
npm config set registry https://registry.npmjs.org/
npm install
```

If you accidentally extracted another package under `packages/`, remove it. A common error is:

```text
EDUPLICATEWORKSPACE: package 'upspa-js' has conflicts
```

Fix:

```bash
rm -rf "packages/New folder"
```

---

## 6. Build commands

### 6.1 Build WASM

```bash
npm run build:wasm
```

This runs:

```bash
bash ./scripts/build_wasm.sh
```

The script generates:

```text
packages/upspa-js/wasm-pkg/
```

Current build script:

```bash
wasm-pack build crates/upspa-wasm \
  --target web \
  --out-dir ../../packages/upspa-js/wasm-pkg \
  --release
```

### 6.2 Build TypeScript client

```bash
npm -w upspa-js run build
```

### 6.3 Build browser extension

```bash
npm -w upspa-extension run build
```

The unpacked extension output is:

```text
packages/extension/dist
```

### 6.4 Build full demo package

```bash
npm run build:demo
```

This runs:

```text
build WASM → build JS → build extension
```

---

## 7. Run tests

## 7.1 Rust protocol tests

Run all `upspa-core` tests:

```bash
cargo test -p upspa-core -- --nocapture
```

List discovered tests:

```bash
cargo test -p upspa-core -- --list
```

Run selected tests:

```bash
cargo test -p upspa-core --test integration_flows -- --nocapture
cargo test -p upspa-core --test vector_toprf -- --nocapture
cargo test -p upspa-core --test vectors_aead -- --nocapture
cargo test -p upspa-core --test vectors_setup -- --nocapture
```

Expected currently passing core tests:

| File | Tests | Expected result |
|---|---:|---|
| `crates/upspa-core/tests/integration_flows.rs` | 1 | pass |
| `crates/upspa-core/tests/vector_toprf.rs` | 2 | pass |
| `crates/upspa-core/tests/vectors_aead.rs` | 1 | pass |
| `crates/upspa-core/tests/vectors_setup.rs` | 1 | pass |

Important test names:

```text
full_client_flow_smoke_test
toprf_threshold_reconstruction_matches_master
toprf_reconstruction_fails_with_t_minus_1_shares
xchacha_roundtrip_and_aad_binding
setup_produces_decryptable_cid_via_toprf
```

Notes:

- `running 0 tests` for `src/lib.rs` only means there are no inline unit tests in that file.
- Integration tests under `tests/*.rs` are the real Rust protocol tests.

### 7.2 JS tests

Build WASM and JS first:

```bash
npm run build:wasm
npm -w upspa-js run build
```

Run tests:

```bash
npm -w upspa-js test -- --reporter=verbose
```

### 7.3 Go Storage Provider tests

Run from the SP service directory:

```bash
cd services/storage-provider-go
go test ./... -v
```

If dependencies changed:

```bash
go mod tidy
go test ./... -v
```

Some DB integration tests may require Docker or a local PostgreSQL setup depending on the test path.

---

## 8. Storage Provider service

The Go SP service implements the API used by the browser extension.

### 8.1 Environment variables

| Variable | Example | Meaning |
|---|---|---|
| `DATABASE_URL` | `postgres://upspa:upspa@localhost:5432/upspa1?sslmode=disable` | Postgres connection string |
| `SP_ID` | `1` | This provider's numeric ID |
| `PORT` | `8081` | HTTP server port |

### 8.2 Create local PostgreSQL user/databases

If using local Postgres, create three demo DBs:

```bash
sudo -u postgres psql
```

Inside `psql`:

```sql
CREATE USER upspa WITH PASSWORD 'upspa';
CREATE DATABASE upspa1 OWNER upspa;
CREATE DATABASE upspa2 OWNER upspa;
CREATE DATABASE upspa3 OWNER upspa;
\q
```

If user already exists but password fails:

```sql
ALTER USER upspa WITH PASSWORD 'upspa';
```

Test connection:

```bash
psql -U upspa -h localhost -d upspa1
```

### 8.3 Start three SPs locally

Open three terminals.

Terminal 1:

```bash
cd services/storage-provider-go
export DATABASE_URL='postgres://upspa:upspa@localhost:5432/upspa1?sslmode=disable'
export SP_ID=1
export PORT=8081
go run ./cmd/sp
```

Terminal 2:

```bash
cd services/storage-provider-go
export DATABASE_URL='postgres://upspa:upspa@localhost:5432/upspa2?sslmode=disable'
export SP_ID=2
export PORT=8082
go run ./cmd/sp
```

Terminal 3:

```bash
cd services/storage-provider-go
export DATABASE_URL='postgres://upspa:upspa@localhost:5432/upspa3?sslmode=disable'
export SP_ID=3
export PORT=8083
go run ./cmd/sp
```

Health checks:

```bash
curl http://localhost:8081/v1/health
curl http://localhost:8082/v1/health
curl http://localhost:8083/v1/health
```

Expected:

```json
{"ok":true}
```

### 8.4 Reset SP database state

```bash
psql -U upspa -h localhost -d upspa1 -c "TRUNCATE TABLE setup, records RESTART IDENTITY CASCADE;"
psql -U upspa -h localhost -d upspa2 -c "TRUNCATE TABLE setup, records RESTART IDENTITY CASCADE;"
psql -U upspa -h localhost -d upspa3 -c "TRUNCATE TABLE setup, records RESTART IDENTITY CASCADE;"
```

---

## 9. Storage Provider API

Base URL examples:

```text
http://localhost:8081
http://localhost:8082
http://localhost:8083
```

### 9.1 Global encoding rules

All binary values are encoded as:

```text
base64url without padding
```

Ciphertext blobs use:

```json
{
  "nonce": "base64url(24 bytes)",
  "ct": "base64url(context-dependent bytes)",
  "tag": "base64url(16 bytes)"
}
```

Context-dependent ciphertext lengths in this implementation:

| Blob | `ct` length |
|---|---:|
| `cid` / `cid_new` | 96 bytes |
| `cj` | 40 bytes |

### 9.2 `GET /v1/health`

Checks service health.

Response:

```json
{"ok":true}
```

### 9.3 `POST /v1/setup`

Provisions one user on one SP.

Request:

```json
{
  "uid_b64": "...",
  "sig_pk_b64": "...",
  "cid": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  },
  "k_i_b64": "..."
}
```

Responses:

| Status | Meaning |
|---:|---|
| `201` | setup inserted |
| `200` | setup already existed / idempotent |
| `400` | invalid base64 or invalid field length |
| `500` | internal DB/server error |

### 9.4 `GET /v1/setup/{uid_b64}`

Fetches stored setup metadata and `cid` for a user.

Response:

```json
{
  "uid_b64": "...",
  "sig_pk_b64": "...",
  "cid": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  }
}
```

### 9.5 `POST /v1/toprf/eval`

Evaluates this SP's TOPRF share on a blinded point.

Request:

```json
{
  "uid_b64": "...",
  "blinded_b64": "..."
}
```

Response:

```json
{
  "sp_id": 1,
  "y_b64": "..."
}
```

### 9.6 `POST /v1/records`

Creates a login-server/account record.

Request:

```json
{
  "suid_b64": "...",
  "cj": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  }
}
```

Responses:

| Status | Meaning |
|---:|---|
| `201` | record created |
| `409` | record already exists |

### 9.7 `GET /v1/records/{suid_b64}`

Fetches a stored `cj` blob.

Response:

```json
{
  "suid_b64": "...",
  "cj": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  }
}
```

### 9.8 `PUT /v1/records/{suid_b64}`

Updates/replaces a stored `cj` blob.

Request:

```json
{
  "cj": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  }
}
```

### 9.9 `DELETE /v1/records/{suid_b64}`

Deletes a record.

### 9.10 `POST /v1/password-update`

Applies a master password update for one SP.

Request:

```json
{
  "uid_b64": "...",
  "sp_id": 1,
  "timestamp": 1739999999,
  "sig_b64": "...",
  "cid_new": {
    "nonce": "...",
    "ct": "...",
    "tag": "..."
  },
  "k_i_new_b64": "..."
}
```

Important invariant:

```text
sig verifies over:
cid_new.nonce || cid_new.ct || cid_new.tag || k_i_new || timestamp_le_u64 || sp_id_le_u32
```

Responses:

| Status | Meaning |
|---:|---|
| `200` | password update applied |
| `400` | invalid request/field/sp_id |
| `401` | invalid signature |
| `404` | user setup not found |
| `409` | replay/stale timestamp |

---

## 10. Light login server

The light login server is only for checking the browser extension. It does not know UpSPA. It stores/verifies whatever password value the browser submits.

Run:

```bash
node demo/light-login-server/server.mjs
```

Open:

```text
http://localhost:3000
```

Routes:

| Route | Purpose |
|---|---|
| `/register` | Register account ID + transformed password |
| `/login` | Verify transformed password |
| `/secret-update` | Rotate LS-specific secret |

The extension should intercept these forms and transform the password fields.

If port `3000` is busy:

```bash
kill -9 $(lsof -t -i:3000)
node demo/light-login-server/server.mjs
```

or:

```bash
PORT=3001 node demo/light-login-server/server.mjs
```

If the port changes, records are tied to the new LS identifier, for example:

```text
http://localhost:3001|alice
```

so re-register the account.

---

## 11. Load the Chrome extension

Build first:

```bash
npm run build:demo
```

Then open:

```text
chrome://extensions
```

Steps:

1. Enable **Developer mode**.
2. Click **Load unpacked**.
3. Select:

```text
packages/extension/dist
```

If you rebuild the extension:

1. Remove the old UpSPA extension.
2. Load `packages/extension/dist` again.
3. Hard-refresh the demo login page with `Ctrl + Shift + R`.

---

## 12. Browser demo flow

### 12.1 Start services

You need:

- SP1 on `8081`
- SP2 on `8082`
- SP3 on `8083`
- light login server on `3000`
- extension loaded from `packages/extension/dist`

### 12.2 Extension setup / provision

Open extension options and enter:

```text
UID: test-user
Master Password: test-password
Threshold: 2

Storage Providers:
1,http://localhost:8081
2,http://localhost:8082
3,http://localhost:8083
```

Click:

```text
Setup / Provision SPs
```

Expected status:

```text
Setup/provision completed successfully.
```

### 12.3 Register account

Open:

```text
http://localhost:3000/register
```

Use:

```text
account id: alice
master password: test-password
confirm: test-password
```

Expected:

```text
Registered successfully
```

### 12.4 Login

Open:

```text
http://localhost:3000/login
```

Use:

```text
account id: alice
master password: test-password
```

Expected:

```text
Login success
```

### 12.5 Secret update

Open:

```text
http://localhost:3000/secret-update
```

Use:

```text
account id: alice
master password: test-password
```

Expected:

```text
Secret updated
```

Then login again with the same master password:

```text
account id: alice
master password: test-password
```

Expected:

```text
Login success
```

### 12.6 Master password update

Open extension options.

Use:

```text
Old master password: test-password
New master password: new-password
Confirm new master password: new-password
```

Click:

```text
Update Master Password
```

Then login with:

```text
account id: alice
master password: new-password
```

Expected:

```text
Login success
```

The old master password should no longer work after a successful master password update.

### 12.7 Resilience test

Stop one SP, for example SP3.

Then try login again.

Expected:

```text
Login success
```

because the extension is configured with threshold `2` out of `3` SPs.

---

## 13. Common troubleshooting

### `EDUPLICATEWORKSPACE`

Cause: duplicate package folder under `packages/`.

Fix:

```bash
rm -rf "packages/New folder"
npm install
```

### `Unsupported URL Type "workspace:"`

Cause: package manager/workspace mismatch or dependency still uses `workspace:*`.

Fix: use npm-compatible `file:` dependencies or run install from the root workspace.

### `upspa_wasm.js: using deprecated parameters`

Use object initialization in `packages/upspa-js/src/wasm.ts`:

```ts
await initWasm({ module_or_path: resolvedUrl } as any);
```

### `WebAssembly.instantiateStreaming violates CSP`

Make sure `packages/extension/src/manifest.ts` contains:

```ts
content_security_policy: {
  extension_pages: "script-src 'self' 'wasm-unsafe-eval'; object-src 'self';",
},
```

### WASM fetched from `http://localhost:3000/assets/...`

The content script must resolve WASM through the extension URL, not the page URL. Check `packages/upspa-js/src/wasm.ts` and use `chrome.runtime.getURL(...)` or equivalent `globalThis.chrome` fallback when running in extension context.

### `Cannot find name 'chrome'`

Install Chrome types:

```bash
npm install -D @types/chrome
```

or access Chrome API through:

```ts
const extChrome = (globalThis as any).chrome;
```

### `connect: connection refused` for Postgres

Postgres is not running or the port is wrong.

Check:

```bash
pg_lsclusters
```

Start:

```bash
sudo pg_ctlcluster 16 main start
```

### `password authentication failed for user "upspa"`

Reset password:

```bash
sudo -u postgres psql
```

```sql
ALTER USER upspa WITH PASSWORD 'upspa';
\q
```

### `EADDRINUSE: address already in use :::3000`

The login server is already running.

Fix:

```bash
kill -9 $(lsof -t -i:3000)
node demo/light-login-server/server.mjs
```

### Secret update fails with `old secret is empty`

Most likely the content script is not injected or the old/new fields are not posted.

Check browser console for:

```text
[UpSPA] content script active
[UpSPA] secret update flow detected
[UpSPA] posted old length = ...
[UpSPA] posted new length = ...
```

If not present, reload the extension and hard-refresh the demo page.

---

## 14. Contributor workflow

Follow [CONTRIBUTING.md](CONTRIBUTING.md) for branch selection, independent task scope,
verification commands and PR requirements. Mobile work, including shared-core/backend work
assigned to the mobile roadmap, targets `mobile-dev`. Confirm the integration target for other
work before creating a branch.

Use focused task branches, review the diff before staging, and preserve unrelated local changes.
AI-assisted contributions follow [AI working rules](docs/ai-working-rules.md); coding agents also
read [AGENTS.md](AGENTS.md). Review and merging remain separate from author verification.

---

## 15. Security notes

- The SP must never store the master password.
- The SP stores opaque encrypted blobs and TOPRF shares only.
- The login server never sees the master password; it only sees UpSPA-derived values.
- Do not log `uid`, `suid`, `cid`, `cj`, `k_i`, signatures, or curve points in production.
- For a production secret update, commit to SPs only after the login server confirms the old-to-new secret update.
- The demo uses permissive local CORS and broad extension host permissions for development. Restrict these before production use.

---

## 16. References

- Chrome extension Manifest V3 and CSP documentation: see Chrome Extensions documentation.
- WebAssembly/Rust build flow: see `wasm-pack` and MDN Rust-to-WASM documentation.
- npm workspaces: see npm CLI workspace documentation.
- Go modules and `go run`: see Go command documentation.
- PostgreSQL: see PostgreSQL documentation for local users/databases and `psql`.

