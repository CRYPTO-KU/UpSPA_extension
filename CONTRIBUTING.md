# Contributing to UpSPA

UpSPA contains a browser prototype, shared Rust protocol implementation, reference storage
provider and an evolving native mobile implementation. Changes must preserve protocol and
credential compatibility across these components.

Read the [AI working rules](docs/ai-working-rules.md) when using AI assistance. For mobile work,
start with the [architecture](docs/mobile-architecture.md) and
[tentative development plan](docs/mobile-development-plan.md).

## Scope and branches

- Mobile PRs target `mobile-dev`; this includes shared-core/backend changes scoped to the mobile
  roadmap. Confirm the target for unrelated browser/backend work.
- Record the base commit, task scope, affected paths and acceptance criteria before implementation.
- Open a focused draft early. Address existing review blockers before adding unrelated features.
- Prefer work that builds and tests against the merged base plus your own changes. Use injected
  fakes for unfinished external dependencies without representing them as production behavior.
- Follow-on PRs may depend on your own repair if the dependency is explicit. Once the parent
  merges, update the branch and verify that the remaining diff contains only follow-on work.
- Send shared-file/interface decisions to the maintainer. Plan a single integration change after
  its prerequisites merge; do not make contributors wait on informal coordination.

Do not discard someone else's work, force-push shared history, or remove code/tests to resolve a
conflict. Synchronize with the integration branch before final review and rerun checks affected by
new base commits or conflict resolutions. Do not include IDE files, local SDK paths or build output.

## Definition of ready

A task names its outcome, ownership paths, merged dependencies, success/failure behavior and
verification method. Identify data sensitivity and compatibility impact. Unresolved identity,
cryptographic, persistence or distributed-recovery decisions need maintainer resolution before
the dependent implementation starts. Routine coding choices do not need repeated approval.

## Verification

Run from the repository root unless a command specifies another directory. Install dependencies
using the committed lockfiles and repository setup instructions. Use commands relevant to the
changed components; this is not a requirement to run every suite for every PR.

| Area | Commands / evidence |
|---|---|
| Rust core | `cargo test --locked -p upspa-core` |
| Encoder parity | `cargo test --locked -p upspa-core --test vectors_password_encoder` and `npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts` |
| TypeScript client | `npm run test:js` |
| Go storage provider | `go -C services/storage-provider-go test -v ./...`; use only an isolated disposable test database (see below) |
| Mobile FFI | `cargo test --locked -p upspa-mobile-ffi`; also run the binding generator and native consumer tests supplied by the branch |
| Android units | `./apps/android/gradlew -p apps/android :app:testDebugUnitTest` |
| Android APKs | `./apps/android/gradlew -p apps/android :app:assembleDebug :fixtures:assembleDebug` |
| Mobile static gates | `python3 tools/security-gates/run_gates.py .` |
| Gate controls | `python3 tools/security-gates/test_negative_fixtures.py` and `python3 tools/security-gates/test_positive_fixtures.py` |
| Documentation | Check local links, command/path references, consistency with code and explicit planned/current labels |

Android prerequisites and device instructions are in [apps/android/README.md](apps/android/README.md).
Browser/WASM build instructions are in [README.md](README.md).

Go database/API tests use `DATABASE_URL` when set, otherwise attempt to start a temporary Postgres
container. Some database tests truncate tables: never point them at a shared, production or valuable
database. With no usable database/container, integration tests can skip while the command still
exits successfully; inspect verbose results and record those skips as missing coverage.

The security runner scans files on disk, including untracked or Git-ignored source directories.
Local research copies can therefore produce findings absent from a clean checkout. Record the
actual scanned tree and reproduce findings against the submitted source; keep the gates enabled.

Run additional module-local suites when those modules exist on your branch. Do not claim a
bootstrap FFI test proves generated Kotlin/Swift behavior. The extension's current `lint` script
is a placeholder and is not lint evidence.

For corpus regeneration, run `npm ci` from the root with development dependencies enabled, then
use the installed local runner:

```bash
./node_modules/.bin/tsx scripts/gen_password_vectors.mjs
git diff --exit-code -- test-vectors/compatibility-profile-v1/
```

Regeneration writes the corpus; run it in a disposable checkout if that directory contains work
in progress. Run the diff check against a clean, committed corpus directory; unrelated local edits
in that directory also make it fail. The manifest's encoder provenance must identify the actual
source used. Do not update expected values merely to make a failing consumer pass.

For security-critical behavior, include valid and invalid cases plus a controlled mutation that
proves the relevant check fails. Remove the mutation before submission. The existing intentionally
mismatched corpus case is a comparison control, not an invalid-policy test or permission to accept
arbitrary mismatches. A mutation of an accepted vector must fail the suite.

A clean-checkout run means dependencies and documented steps work without uncommitted files,
manual library copying or hidden local configuration. Document legitimate prerequisites. Record
unavailable CI, device, toolchain or service checks as **not run** with their impact.

## Definition of done and PR evidence

A completed PR contains the implementation, relevant regressions, negative-control evidence,
reproduction/demo instructions and known limits. Clearly label fakes, stubs and deferred work.
Report actual command results, environment and base/head revisions; do not equate compiling with
working or mergeability with approval. Use the [PR template](.github/pull_request_template.md).

Each author verifies their own work. Independent review and combined integration are maintainer
responsibilities; another contributor's review is not a prerequisite for submission. Self-review
or AI review does not replace independent approval for security-sensitive changes. Approval,
required checks and merge decisions are separate from the author's completion claim.

## Security and compatibility changes

Read [security notes](docs/security.md) and the mobile invariants before changing a trust boundary.
Record material decisions in the relevant architecture document or a focused decision record:
problem, alternatives, chosen behavior, compatibility/migration impact, evidence and unresolved
risks. Version affected wire/profile/catalog/snapshot contracts rather than silently changing
previously stored bytes. Keep diagnostics structured and free of sensitive values.

Dependency changes need a task-related reason, reviewed lockfile diff and affected builds/tests.
Generated bindings must come from the matching generator/runtime version. A static gate exception
requires an explicit rationale, bounded scope and regression evidence; weakening a gate is not a
substitute for fixing the underlying behavior.
