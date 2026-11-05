# PR #33 Review Round 3 Verification Report

## Overview
This report documents the resolution of the two review round 3 findings for PR #33,
following the same TDD protocol as round 2:
1. Regression tests and valid controls were committed first, as a tests-only commit, and shown to fail on unfixed code.
2. Each production fix is a separate commit on top of the tests-only commit.
3. Isolated-copy mutation proofs show that the new tests detect removal or weakening of each fix.
4. All work stays inside `upspa-assetlink-verifier/`. Nothing was pushed.

### Baseline and Commit Hashes

| Role | Commit |
| :--- | :--- |
| `origin/mobile-dev` (merged base) | `75a33a2` (`75a33a25fb6b67647386c78172cfeddd92d4a626`) |
| PR branch before the sync merge | `a07e725` (`a07e725f81965a6e39600f7cd1bb20a68081ac92`) |
| Sync merge of `origin/mobile-dev` | `432cf02` (parents `a07e725`, `75a33a2`) |
| Tests-only commit (round 3 regression tests) | `158b393` (`158b393e725d587d5d19cee9f02720c62c222fb6`) |
| Fix commit A, Finding 1 | `7a78cec` (`7a78cec9c15ab5e5ef1f034e43b9b126593882ae`) |
| Fix commit B, Finding 2 | `388ac95` (`388ac95d533bd3c70380e40b008764f3294e3020`) |
| Report and evidence (first version) | `0e43dd2` |
| Commit C, evidence-printing child controls (tests only) | `475f865` (`475f8656afac5ee71a985b9276f2d82b23f0c96a`) |
| Commit D, this report update and evidence sanitization | the commit that contains this version of the report |

The tests-only commit `158b393` replaced an earlier unpushed version, `28a8b62`, with `git commit --amend` after review.
Its diff is in `upspa-assetlink-verifier/docs/pr33-round3-evidence/test-commit-158b393.diff`.
After `git fetch origin` at the end of this round, `origin/mobile-dev` still resolved to `75a33a2`.

### Environments

| Label | Description |
| :--- | :--- |
| **Windows Git Bash** | Windows 11, JDK 17.0.20 (Gradle toolchain). Gradle was run from PowerShell with `C:\Program Files\Git\bin` prepended to `PATH`. Scripts were run with Git for Windows `bash.exe` (GNU bash 5.3.15, cygwin). The working tree is NTFS, so files have no POSIX modes. |
| **WSL** | WSL2 Ubuntu (kernel 6.18.54.1-microsoft-standard-WSL2), GNU bash 5.3.9, and Temurin JDK 17.0.21+9 unpacked into the user's home directory without sudo. The repository was cloned from the local repo into the WSL home directory (ext4). `git` checks `scripts/mutation-check.sh` out with mode `644`, matching its committed mode `100644`. |

---

## Finding 1: Backslash followed by a literal control character is accepted (P2)

### 1.1 Problem & Reproduction
`validateStrictJsonStringSyntax` in `upspa-assetlink-verifier/src/main/kotlin/org/upspa/catalog/CatalogValidator.kt`
rejects unescaped control characters (< 0x20) inside JSON strings, as RFC 8259 Section 7 requires. After a backslash,
however, it cleared its `escaped` flag for **any** next byte. A backslash followed by a literal control byte
(for example `!` `\` LF `@` in `allowedSymbols`, or `\` LF inside an object key) therefore skipped the check.

### 1.2 Reproduction Command (Before Fix)
```powershell
$env:PATH = "C:\Program Files\Git\bin;" + $env:PATH
.\gradlew clean test --no-daemon
```
*(Windows Git Bash. The run used a working tree identical to tests-only commit `158b393`. It was made just before the amend, from the same test file content.)*

### 1.3 Real Output Before Fix (excerpt (trimmed))
Full output: `upspa-assetlink-verifier/docs/pr33-round3-evidence/unfixed-full-suite-158b393.txt`.
```text
ReviewRound3RegressionTest > Finding 1 - backslash followed by literal control character inside object key is rejected as MalformedJson() FAILED
ReviewRound3RegressionTest > Finding 1 - backslash followed by literal control character U+0000 to U+001F is rejected as MalformedJson(int) > code point 10 FAILED
ReviewRound3RegressionTest > Finding 1 - exact repro - backslash followed by literal LF in allowedSymbols is rejected as MalformedJson() FAILED
355 tests completed, 7 failed, 2 skipped
```
Three Finding 1 tests fail: the exact reproduction, `code point 10` (LF), and the object-key case.
The other 31 code points (0..9, 11..31) already returned `MalformedJson` on unfixed code. The reason was verified with a scratch probe that was never committed.
The probe source is in `upspa-assetlink-verifier/docs/pr33-round3-evidence/ScratchEscapeProbeTest.kt.txt`. It was run in a temporary worktree at `158b393` and again at `475f865`; the outputs are in `escape-probe-158b393.txt` and `escape-probe-475f865.txt` in the same directory.
- **Pre-check:** On unfixed code, `validateStrictJsonStringSyntax` skips every byte after a backslash, so the parse reaches Moshi's `JsonReader`.
- **Moshi:** In strict (non-lenient) mode, `JsonReader` throws `JsonEncodingException: Invalid escape sequence` for a backslash followed by any control character **except LF (10)**. It accepts LF as an escaped newline and decodes it to code point 10.
- **Validator:** `parseJsonAst` maps the Moshi exception to `MalformedJson`. That is why only `code point 10` failed.

On unfixed code the probe gives `StrictCatalogValidator` results of `Rejected(MalformedJson)` for backslash + 9 and backslash + 11, and `Accepted` with code points `[33, 10, 64]` for backslash + 10.
On fixed code, backslash + 10 is `Rejected(MalformedJson)` as well.

> [!NOTE]
> **Observation for reviewer (not changed in this round):** The same probe shows that Moshi strict mode also accepts
> backslash + apostrophe (`\'`, code point 39), which RFC 8259 Section 7 does not list as an escape. With both fixes applied,
> `StrictCatalogValidator` still **accepts** `allowedSymbols` containing `!\'@` and decodes it to `[33, 39, 64]`.
> Whether the validator should also reject `\'` is a catalog-strictness decision and is left to the maintainer.
All 7 Finding 1 valid controls passed. These are escaped `\n`, `\\`, `\t`, `\r`, `\u0000` and `\u001f`, each accepted with its decoded value checked, plus a literal LF without a backslash, which is rejected.

### 1.4 Fix Applied (commit `7a78cec`)
```kotlin
if (escaped) {
    // RFC 8259 Section 7: a control character (< 0x20) is never a valid escape character
    if (unsigned < 0x20) {
        return CatalogRejection.MalformedJson
    }
    escaped = false
} else if ...
```
Valid escapes (`\n`, `\t`, `\r`, `\\`, `\"`, `\uXXXX`) and bytes >= 0x80 are unchanged.

### 1.5 Verification Command (After Fix)
Same command as 1.2, at commit `388ac95`, which contains both fixes.

### 1.6 Real Output After Fix
Full output: `upspa-assetlink-verifier/docs/pr33-round3-evidence/fixed-full-suite-388ac95.txt`.
Totals from the XML reports: `upspa-assetlink-verifier/docs/pr33-round3-evidence/fixed-xml-totals-388ac95.txt`.
All Finding 1 tests and controls pass: `ReviewRound3RegressionTest` reports 48 tests, 0 failures, 1 skip (the Finding 2 mode-644 test, see 2.6).

---

## Finding 2: `mutation-check.sh --selftest-compile-fault` passes on a launch failure (P2)

### 2.1 Problem & Reproduction
`scripts/mutation-check.sh` is committed with mode `100644`. The self-test launched its child directly as
`"$BASH_SOURCE" --inject-compile-fault`. On a POSIX file system the child cannot execute: it prints
`Permission denied` and exits with status 126. The self-test treated **any** non-zero status as success,
printed `Self-test PASSED`, and exited 0. It also did not check that a baseline ran or that the compile fault was reached.
The relative `$BASH_SOURCE` would also break after the script's `cd` when the script is invoked from another directory.

The regression tests build a hermetic fixture in a temp directory and run the **real** script there. Gradle is never started.
The fixture contains:
- a `git init` repository with a copy of the script;
- minimal Gradle files, `gradlew.bat`, and an empty `gradle/` directory;
- `docs/`;
- a synthetic `PureAssetLinkVerifier.kt` that matches the real M1 grep pattern exactly once;
- a stub `gradlew`.

| Test | Asserts |
| :--- | :--- |
| Mode-644 positive test | The script is set to mode `644` (asserted). Requires exit 0, `Self-test PASSED`, `Baseline PASSED`, compile-fault injection, `failed to compile`, and child status exactly 2. Rejects `Permission denied`, `command not found`, 126 and 127. Skipped with an explicit message on file systems without POSIX modes. |
| Launch-failure controls (126, 127, 1) | The whole child-launch line is replaced by `sh -c 'exit N'`. Requires non-zero exit and `Self-test FAILED`, never `Self-test PASSED`. |
| Status 0 control | Same, with `sh -c 'exit 0'`. |
| Status 2 without evidence control | Same, with `sh -c 'exit 2'`, so there is no baseline or fault output. |
| Evidence-printing controls, N = 126, 127, 1, 3, 0 (added in `475f865`) | The whole launch statement is replaced by `SELFTEST_OUTPUT="$(sh -c '<print full evidence>; exit N' 2>&1)"`. The output is captured exactly like the real launch line. The child prints `Baseline PASSED: 3 tests completed`, `Injecting deliberate Kotlin compilation fault into ... for M1...` and `FATAL: Mutation M1 failed to compile! ...`. Requires non-zero exit and `Self-test FAILED`, never `Self-test PASSED`. |
| Evidence-printing positive control, N = 2 (added in `475f865`) | Same replacement with exit 2. Requires exit 0 and `Self-test PASSED`. It does not depend on file modes, so it also runs on Windows. |
| `bash -n` valid control | The script has valid bash syntax. |

The launch-line replacement helper replaces the single non-comment line containing `--inject-compile-fault` inside the
`--selftest-compile-fault` branch. It therefore works for both the pre-fix form (`"$BASH_SOURCE" --inject-compile-fault`)
and the post-fix form (`SELFTEST_OUTPUT="$("${BASH:-bash}" "$ABS_SCRIPT" --inject-compile-fault 2>&1)"`).

### 2.2 Reproduction Commands (Before Fix)
Windows Git Bash: the same full-suite command as 1.2.
WSL (POSIX file modes), using `upspa-assetlink-verifier/docs/pr33-round3-evidence/wsl-run-r3.sh` at `158b393`:
```bash
./gradlew test --tests '*ReviewRound3RegressionTest*' --no-daemon
```
WSL without a JDK (an earlier step) used the plain-bash fixture harness `upspa-assetlink-verifier/docs/pr33-round3-evidence/finding2-fixture-harness.sh`.
That harness mirrors the Kotlin positive test with modes 644 and 755.

### 2.3 Real Output Before Fix (excerpt (trimmed))
Windows Git Bash (`unfixed-full-suite-158b393.txt`): the 126, 127, 1 and status-2-without-evidence controls fail.
The status-0 control passes. The mode-644 positive test is **skipped** (NTFS has no POSIX modes).
```text
ReviewRound3RegressionTest > Finding 2 - negative control - child status 2 without baseline or compile-fault evidence is rejected() FAILED
ReviewRound3RegressionTest > Finding 2 - self-test of mode 644 script runs baseline, reaches compile fault and requires child status 2() SKIPPED
ReviewRound3RegressionTest > Finding 2 - negative control - self-test fails when child launch exits with status(int) > child status 126 FAILED
ReviewRound3RegressionTest > Finding 2 - negative control - self-test fails when child launch exits with status(int) > child status 127 FAILED
ReviewRound3RegressionTest > Finding 2 - negative control - self-test fails when child launch exits with status(int) > child status 1 FAILED
```
WSL at `158b393` (`upspa-assetlink-verifier/docs/pr33-round3-evidence/wsl-r3-tests-158b393.txt`): 48 tests, 8 failures, 0 skipped. The mode-644 positive test **fails**:
```text
Self-test must not hit Permission denied. Output:
=== Mutation Checker Self-Test Mode ===
Invoking mutation checker with an injected compile fault...
scripts/mutation-check.sh: line 16: scripts/mutation-check.sh: Permission denied
Self-test PASSED: mutation checker rejected compile fault with non-zero exit code (126).
```
The plain-bash harness in WSL (`upspa-assetlink-verifier/docs/pr33-round3-evidence/finding2-wsl-fixture-harness-158b393.txt`) gave these results:
- mode 644: `HARNESS RESULT: FAIL`, for the same reason;
- mode 755: `HARNESS RESULT: PASS`. This is the valid control: with a working launch, every assertion can be satisfied.

The header of that output names the harness by its old untracked path. It is the same file, renamed into the evidence directory.

**Evidence-printing controls on unfixed code.** This run used a temporary worktree at `158b393` with the test file from `475f865` copied in, on Windows Git Bash
(`upspa-assetlink-verifier/docs/pr33-round3-evidence/unfixed-158b393-with-475f865-tests.txt`, `--rerun`):
- 54 tests, 11 failures, 1 skipped.
- The non-zero-status cases `child status 126 with full evidence`, `127`, `1` and `3` **fail**: the unfixed script prints `Self-test PASSED` and exits 0.
- `child status 0 with full evidence` passes, because the unfixed script already rejects status 0.
- The N = 2 positive control passes.
- The other 7 failures are the 3 Finding 1 tests and the 4 earlier Finding 2 controls.

### 2.4 Fix Applied (commit `388ac95`)
- The script resolves its absolute path (`ABS_SCRIPT`) **before** `cd "$REAL_ROOT"`. It launches the child as
  `"${BASH:-bash}" "$ABS_SCRIPT" --inject-compile-fault`, so the file mode and the caller's directory do not matter.
  The committed file mode stays `100644`.
- The child's output is captured and displayed, and its exit status is recorded. The self-test passes only if the status is exactly 2
  **and** the output contains `Baseline PASSED`, `Injecting deliberate Kotlin compilation fault` and `failed to compile`.
  Otherwise it prints `Self-test FAILED: <reason>` and exits 1. The reason names one of these cases:
  - a launch failure (126 permission denied, 127 command not found);
  - status 0;
  - any other status;
  - status 2 without the evidence, listing the missing strings.
- Running `--inject-compile-fault` directly is unchanged.
- `upspa-assetlink-verifier/docs/mutation-check.md` describes the self-test requirements.

### 2.5 Verification Commands (After Fix)
```powershell
$env:PATH = "C:\Program Files\Git\bin;" + $env:PATH
.\gradlew clean test --no-daemon
```
```bash
"C:\Program Files\Git\bin\bash.exe" scripts/mutation-check.sh --selftest-compile-fault
"C:\Program Files\Git\bin\bash.exe" scripts/mutation-check.sh --inject-compile-fault
"C:\Program Files\Git\bin\bash.exe" scripts/mutation-check.sh
```
WSL: `wsl-run-r3.sh` at `388ac95`, and again at `475f865`.

### 2.6 Real Output After Fix (excerpt (trimmed))
**Windows Git Bash, full suite at `475f865`** (`fixed-full-suite-475f865.txt`, `fixed-xml-totals-475f865.txt`): Gradle exit 0.
All Finding 2 controls pass, including the 6 evidence-printing controls. The mode-644 positive test is skipped on NTFS.
The WSL VM was not running when the suite started (recorded in the totals file).
The earlier run at `388ac95` (`fixed-full-suite-388ac95.txt`, `fixed-xml-totals-388ac95.txt`) is kept as history.

**WSL at `475f865`** (`upspa-assetlink-verifier/docs/pr33-round3-evidence/wsl-r3-tests-475f865.txt`): `BUILD SUCCESSFUL`, 54 tests, 0 failures, **0 skipped**.
The mode-644 positive test and all evidence-printing controls **pass** with the script at mode `644 -rw-r--r--`.
The earlier WSL run at `388ac95` (`wsl-r3-tests-388ac95.txt`: 48 tests, 0 failures, 0 skipped) is kept as history.

**Real script, self-test** (`upspa-assetlink-verifier/docs/pr33-round3-evidence/script-selftest-388ac95.txt`), real Gradle, exit code 0:
```text
=== Mutation Checker Self-Test Mode ===
Invoking mutation checker with an injected compile fault...
=== Running mutation checker with deliberate compile fault injection ===
Running baseline test suite in temporary copy...
Baseline PASSED: all passed
Injecting deliberate Kotlin compilation fault into src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt for M1...
FATAL: Mutation M1 failed to compile! Compilation errors are NOT killed mutations.
Cleaning up temporary isolation directory...
Self-test PASSED: fresh baseline ran, compile fault was reached and rejected with exit code (2).
# exit code: 0
```
**Real script, `--inject-compile-fault`** (`upspa-assetlink-verifier/docs/pr33-round3-evidence/script-inject-compile-fault-388ac95.txt`): `Baseline PASSED`, then `FATAL: Mutation M1 failed to compile!`, `# exit code: 2`.

**Real script, normal run** (`upspa-assetlink-verifier/docs/pr33-round3-evidence/script-normal-388ac95.txt`), exit code 0:
```text
Baseline PASSED: all passed
PASS: Mutation M1 KILLED! (52 tests executed, failing cases: PACKAGE_MISMATCH_CASE_DIFFERENCE)
...
PASS: Mutation M8 KILLED! (52 tests executed, failing cases: ORIGIN_BOUNDARY_NON_HTTPS_REJECTION)
Working tree unchanged: **yes**
- Mutations killed: **8**
- Mutations survived: **0**
SUCCESS: All mutations were killed by the Identity Adversarial Suite!
# exit code: 0
```

---

## Mutation Proofs (isolated copies only)

### Current proofs, tests at `475f865`
F2(a1), F2(a2) and F2(b) were rerun in a temporary detached `git worktree` at `475f865`, on Windows Git Bash with `--rerun`.
The driver was `upspa-assetlink-verifier/docs/pr33-round3-evidence/win-mutations-r2.sh`.
The same driver produced the unfixed-code run and the escape probes described earlier.
The worktree was removed afterwards, and `git worktree list` shows only the main checkout
(`upspa-assetlink-verifier/docs/pr33-round3-evidence/worktree-list-after-475f865-proofs.txt`).

| ID | Mutation | Environment | Result | Failing tests |
| :--- | :--- | :--- | :--- | :--- |
| F2(a1) | Classification weakened: `2) ;;` → `[1-9]*) ;;` (any non-zero status accepted); evidence check kept | Windows Git Bash | **Detected** (4 failures) | `child status 126 with full evidence`, `child status 127 with full evidence`, `child status 1 with full evidence`, `child status 3 with full evidence` |
| F2(a2) | Any non-zero status accepted **and** evidence check dropped | Windows Git Bash | **Detected** (8 failures) | the 4 evidence-printing cases above, `child status 126`, `child status 127`, `child status 1`, status 2 without evidence |
| F2(b) | Evidence requirement dropped (`if [ -z "$SELFTEST_REASON" ]` → `if false`) | Windows Git Bash | **Detected** (1 failure) | status 2 without evidence |

Evidence: `upspa-assetlink-verifier/docs/pr33-round3-evidence/mutation-F2a1-475f865.txt`, `mutation-F2a2-475f865.txt` and `mutation-F2b-475f865.txt` in the same directory.
F1 and F2(c) were not rerun. The tests they rely on did not change in `475f865`.

A first attempt at this set of proofs used a worktree under a deeply nested temp directory. It failed with Windows `Filename too long` errors before any test ran.
It was rerun with a short worktree path. The leftover directories from that attempt, and from the first proof round, were outside the repository and have been deleted.

### History: first proofs, tests at `388ac95`
F1 and F2(a)/(b) ran in a temporary detached `git worktree` at `388ac95`, driven by
`upspa-assetlink-verifier/docs/pr33-round3-evidence/win-mutations.sh` (Windows Git Bash).
F2(c) ran in the WSL clone, driven by `upspa-assetlink-verifier/docs/pr33-round3-evidence/wsl-mutation-c.sh`.
Every run used `./gradlew test --rerun --tests ...ReviewRound3RegressionTest`. Each copy was restored afterwards.
The temporary worktree was removed. `git worktree list` afterwards shows only the main checkout
(`upspa-assetlink-verifier/docs/pr33-round3-evidence/worktree-list-after.txt`).

| ID | Mutation | Environment | Result | Failing tests |
| :--- | :--- | :--- | :--- | :--- |
| F1 | `CatalogValidator.kt` reverted to its pre-fix version (exact inverse of `7a78cec`) | Windows Git Bash | **Detected** (3 failures) | exact repro, `code point 10`, object-key case |
| F2(a1) | Classification weakened: `2) ;;` → `[1-9]*) ;;` (any non-zero status accepted); evidence check kept | Windows Git Bash | **Not detected at that time** (0 failures); detected since `475f865` | none: the evidence requirement still rejects 126/127/1 because they produce no baseline or fault output |
| F2(a2) | Any non-zero status accepted **and** evidence check dropped (pre-fix semantics) | Windows Git Bash | **Detected** (4 failures) | `child status 126`, `child status 127`, `child status 1`, status 2 without evidence |
| F2(b) | Evidence requirement dropped (`if [ -z "$SELFTEST_REASON" ]` → `if false`) | Windows Git Bash | **Detected** (1 failure) | status 2 without evidence |
| F2(c) | Launch reverted to `SELFTEST_OUTPUT="$("$BASH_SOURCE" --inject-compile-fault 2>&1)"` | WSL | **Detected** (1 failure) | mode-644 positive test: `Permission denied`, `Self-test FAILED: launch failure: child exit code (126) ...` |

Evidence: `upspa-assetlink-verifier/docs/pr33-round3-evidence/mutation-F1.txt`, `mutation-F2a1.txt`, `mutation-F2a2.txt`,
`mutation-F2b.txt` and `mutation-F2c-wsl.txt` in the same directory.

Notes:
- **F2(a1), history:** The 126/127/1 controls in `388ac95` printed no evidence, so a weakened status classification alone was not detected.
  This gap was closed in `475f865` by the evidence-printing controls (see the current proofs above).
- **F1 diff:** `mutation-F1.txt` shows an empty diff section. The mutation was applied with
  `git checkout <commit>~2 -- CatalogValidator.kt`, which stages the change, and the script printed only the unstaged diff.
  The mutation is exactly the inverse of commit `7a78cec`.
- **Stale first attempt:** An earlier F2(c) run without `--rerun` reported 0 failures because Gradle considered the test task up-to-date.
  `scripts/mutation-check.sh` is not a declared input of the `test` task. That run was discarded, and all proofs above use `--rerun`.

---

## Test Execution Arithmetic & Final Run Totals

### Test Suite Breakdown
- Before round 3: 307 tests (`ReviewRound3RegressionTest` did not exist).
- `+48` tests in `ReviewRound3RegressionTest`:
  - Finding 1: 3 rejection tests, one of which is parameterized over 32 code points, plus 7 valid controls. Total 41.
  - Finding 2: `bash -n` control, mode-644 positive test, 3 parameterized launch-failure controls, status-0 control, and status-2-without-evidence control. Total 7.
- `+6` tests in `475f865`: 5 parameterized evidence-printing controls (N = 126, 127, 1, 3, 0) and the N = 2 positive control.
- **Total: 361 tests**, of which 54 are in `ReviewRound3RegressionTest`.

### Unfixed Run (`158b393`, Windows Git Bash, `.\gradlew clean test --no-daemon`)
From the XML reports:

| Scope | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| All 14 classes | 355 | 346 | 7 | 2 |
| `ReviewRound3RegressionTest` | 48 | 40 | 7 | 1 |

Every other class passed with 0 skipped, except `ReviewRound2RegressionTest`: 16 tests, 1 skipped.

The unfixed run with the `475f865` test file (`ReviewRound3RegressionTest` only, temporary worktree, Windows Git Bash) gave 54 tests: 42 passed, 11 failed, 1 skipped.

### Fixed Run (`475f865`, Windows Git Bash, `.\gradlew clean test --no-daemon`, WSL VM stopped at start)
From the XML reports (`fixed-xml-totals-475f865.txt`). Gradle exit code 0.

| Scope | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| All 14 classes | 361 | 359 | 0 | 2 |
| `ReviewRound3RegressionTest` | 54 | 53 | 0 | 1 |

History: the fixed run at `388ac95` (`fixed-xml-totals-388ac95.txt`) gave 355 total, 353 passed, 0 failed and 2 skipped.
`ReviewRound3RegressionTest` was 48 total, 47 passed, 1 skipped.

### WSL Runs (`ReviewRound3RegressionTest` only)

| Commit | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| `158b393` | 48 | 40 | 8 | 0 |
| `388ac95` | 48 | 48 | 0 | 0 |
| `475f865` | 54 | 54 | 0 | 0 |

### Skipped Tests and Reasons (Windows)
1. `ReviewRound3RegressionTest` — the Finding 2 mode-644 positive test. The NTFS working tree has no POSIX file modes, so a mode-644 script cannot be expressed. This test was run in WSL instead.
2. `ReviewRound2RegressionTest` — `Finding 5 - valid control - mutation check script exists and has valid bash syntax`. That round 2 test probes `ProcessBuilder("bash")`.
   On Windows, process creation searches `System32` before `PATH`, so the probe hits the WSL launcher `System32\bash.exe` rather than Git Bash.
   The skip happens when that launcher does not answer within the test's 3 s timeout, which is the case when the WSL VM is not running.

---

## Pre-existing Issues Observed (not changed in this round)
- **Round 2 bash probe is environment-dependent.** When the WSL VM is already running, the `ReviewRound2RegressionTest` Finding 5 probe succeeds through `System32\bash.exe`.
  The test then fails with `No such file or directory`, because WSL bash cannot resolve the Windows path
  (`upspa-assetlink-verifier/docs/pr33-round3-evidence/round2-bash-check-wsl-warm-repro.txt`).
  - The probe itself can start the WSL VM, so a later Gradle run in the same session may fail.
  - This broke one normal `mutation-check.sh` baseline run (`upspa-assetlink-verifier/docs/pr33-round3-evidence/script-normal-388ac95-wsl-warm-failed.txt`: `355 tests completed, 1 failed, 1 skipped`).
  - The successful runs reported above were made with the WSL VM stopped. Each output header records the VM state.
  - The round 3 tests avoid the problem by resolving `bash` from `PATH` themselves and ignoring launchers under `%SystemRoot%`.
- **Cleanup trap can override the exit code on Windows.** In one `--inject-compile-fault` run
  (`upspa-assetlink-verifier/docs/pr33-round3-evidence/script-inject-compile-fault-388ac95-cleanup-busy.txt`), `rm -rf` of the temporary directory failed with
  `Device or resource busy`, a Windows file lock on the temp copy. With `set -e`, the `cleanup` trap then exited 1 instead of the intended 2.
  - The rerun exited 2.
  - The self-test reports this case as `Self-test FAILED: child exit code (1) ...`, so it fails closed.

## Not Run / Limitations
- The mode-644 positive test cannot run on NTFS. Its POSIX results come only from WSL.
- No CI or device runs were made. All results are local.
- The real `mutation-check.sh` runs (self-test, `--inject-compile-fault`, normal) were made at `388ac95` and were **not repeated** at `475f865`.
  Commit `475f865` changes only the test file, which the script's baseline runs as part of the suite; the `475f865` full suite passes.

## Evidence Files and Sanitization
All evidence is in `upspa-assetlink-verifier/docs/pr33-round3-evidence/`. Files are raw outputs, driver scripts, and the scratch probe source
(`ScratchEscapeProbeTest.kt.txt`, which was never compiled into the module).

Only local path prefixes were replaced; nothing else in the evidence files was edited.
The transform was applied with GNU sed in Git Bash to every file in that directory, one substitution per prefix form, in the order below.
In the applied command, `<win-user>` and `<wsl-user>` were the literal local account names; they are not reproduced here.
```bash
sed -i -e 's#/mnt/c/Users/<win-user>#<WIN_HOME>#g' \
       -e 's#/c/Users/<win-user>#<WIN_HOME>#g' \
       -e 's#C:/Users/<win-user>#<WIN_HOME>#g' \
       -e 's#C--Users-<win-user>#<WIN_HOME_SLUG>#g' \
       -e 's#/home/<wsl-user>#<WSL_HOME>#g' \
       upspa-assetlink-verifier/docs/pr33-round3-evidence/*
```

| Prefix form (generic pattern) | Where it occurs | Replacement |
| :--- | :--- | :--- |
| `/mnt/[a-z]/Users/[^/]+` | WSL mount form of the Windows home (driver scripts) | `<WIN_HOME>` |
| `/[a-z]/Users/[^/]+` | MSYS/Git Bash form of the Windows home | `<WIN_HOME>` |
| `[A-Za-z]:[\\/]+Users[\\/]+[^\\/]+` | Windows drive form, including inside `file:///` URLs | `<WIN_HOME>` |
| `[A-Za-z]--Users-[^/-]+` | The Windows home prefix as encoded in the session temp directory name | `<WIN_HOME_SLUG>` |
| `/home/[^/]+` | WSL home directory | `<WSL_HOME>` |

The evidence files contained no backslash form of the Windows home prefix, so only the forward-slash variants were applied.

Verification of the transform:
- **Committed files:** for every evidence file already committed in `0e43dd2`, `git show 0e43dd2:<file> | sed <transform>` is byte-identical to the new file.
- **New files:** for each new file, applying the transform to the original scratch output is byte-identical to the committed file.

Git branch names and the commit author e-mail are not path prefixes and were not changed.
The driver scripts were sanitized too. They reference `<WIN_HOME>` / `<WSL_HOME>` and must be adjusted before reuse.

---

## Independent review follow-up

An independent, read-only review of this branch (compared with `origin/mobile-dev`) found two JSON strictness gaps in the catalog
validator and five weaknesses in `scripts/mutation-check.sh`. Each was reproduced by running it. Two further observations are
recorded without a code change (section F.4). The protocol was the same as in rounds 2 and 3: a tests-only commit first, then
one fix commit per component, then this addendum. All commits are new; nothing was amended, rebased or pushed.

### F.1 Commits

| Role | Commit |
| :--- | :--- |
| Tests-only commit (independent review regression tests) | `80555aa` (`80555aab2ab36e484f79b323eb2f3249c053df62`) |
| Fix commit, validator (Findings A and B) | `75ebd30` (`75ebd30c29fe7b496ad9cf641c40ddab11d255e8`) |
| Fix commit, `mutation-check.sh` (Finding C) | `6b6c6d1` (`6b6c6d10c23e5d5d645bca40cba7b113572a0530`) |
| This addendum, evidence and doc updates | the commit that contains this section |

After `git fetch origin` at the end of this follow-up, `origin/mobile-dev` still resolved to `75a33a2`.
The evidence for this section is in `upspa-assetlink-verifier/docs/pr33-round3-evidence/independent-review/` (section F.9).

### F.2 Findings and fixes

| ID | Problem (before) | Fix |
| :--- | :--- | :--- |
| A | Moshi matches `true`, `false` and `null` case-insensitively, so `"requireUpper": TRUE` or `"reference": NULL` was **accepted**. RFC 8259 Section 3 defines the literal names in lowercase only. | `validateStrictJsonStringSyntax` rejects a run of ASCII letters outside strings that spells a keyword in a non-lowercase letter case where a value can start: at the document start, or after `:`, `[` or `,`. After `}` or `]`, Moshi still reports `TrailingData`. Exponent letters are not keywords, so `1e0` stays `WrongType`. |
| B | The escape `\'` was **accepted** in values and keys (Moshi accepts it with `isLenient = false`). | After a backslash, only `\" \\ \/ \b \f \n \r \t` and `\u` are accepted. Moshi still checks the `\u` hex digits. |
| C1 | `trap cleanup EXIT INT TERM`: cleanup exited with `$?` of the last command, so SIGTERM or SIGINT exited **0**. | Cleanup stays on `EXIT`. `INT` and `TERM` go to `on_signal`, which prints `FATAL: Mutation check interrupted by SIG<name>.` and exits 130 or 143. |
| C2 | A failing `rm -rf` of the isolation directory in the trap (under `set -e`) turned exit code 2 into **1**. | Cleanup runs with `set +e`, prints `WARNING: could not remove temporary isolation directory: <dir> ...` and exits with the original code. |
| C3 | With `CDPATH` set, `cd` printed the directory into `ABS_SCRIPT`/`REAL_ROOT` and the script exited 1. | `unset CDPATH` before any `cd`. |
| C4 | The tests-executed guard only rejected an empty value or 0. A non-integer `tests=` value **passed** it (`[: integer expected` counts as false inside `if`). | The guard requires `^[0-9]+$` and a value above 0, else exit 4. |
| C5 | The baseline ran `./gradlew test`, which up-to-date state or the build cache could satisfy. | The baseline runs `./gradlew test --rerun-tasks --no-build-cache`. |

The self-test evidence strings and the exit-code contract (2, 3, 4, 5, 6) are unchanged. C2 fixes the round 3 "Pre-existing
Issues" item **Cleanup trap can override the exit code on Windows**.

### F.3 New tests
- `IndependentReviewValidatorRegressionTest` (49 tests).
  - Finding A: 8 boolean keyword variants in `requireUpper`, 6 null variants in `reference` / `unverifiedReason`, and one in an array.
  - Finding B: `\'` in a value and in a key.
  - Valid controls:
    - lowercase `true`/`false`/`null` are accepted with their values;
    - `1e0`, `1E0`, `0e5`, `1.0` and `1.5E+2` still give `WrongType`;
    - a trailing ` TRUE` still gives `TrailingData`;
    - keyword text inside a string is accepted;
    - 12 RFC escapes are accepted with the decoded code point checked (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\u0041`, `\u00e9`, `\u00E9`, `\uD83D\uDE00`);
    - 9 non-RFC escapes (`\a`, `\0`, `\x41`, `\v`, `\U0041`, `\e`, `\u12`, `\u00G1`, `\uZZZZ`) and a short `\u` at the end of a string stay `MalformedJson`.
- `IndependentReviewScriptRegressionTest` (10 tests). It uses the same fixture style as `ReviewRound3RegressionTest`: a temporary git repository, a copy of the real script, and a stub `gradlew` that records its arguments and the isolation directory. No real Gradle runs.
  - C1: SIGTERM and SIGINT during a stub baseline that sleeps 3 s. Expects exit 143 / 130, no `SUCCESS` and no `Baseline PASSED`. The wrapper uses `set -m`, because a non-interactive shell starts background jobs with SIGINT ignored.
  - C2: a stub `rm` first on `PATH` refuses to remove exactly the recorded isolation directory. Expects exit 2 for `--inject-compile-fault` and the leftover directory reported. `PATH` is set inside a bash wrapper, because Git for Windows' `bin\bash.exe` launcher prepends its own `/usr/bin` to `PATH`. Control: without the stub, the directory is removed and the exit code is 2.
  - C3: `CDPATH=.`. Expects exit 2 for `--inject-compile-fault` and no `No such file or directory`.
  - C4: a stub suite XML with `tests="many"`. Expects exit 4 and M1 not counted as killed. Controls: `tests="0"` gives exit 4; `tests="52"` kills M1 and then stops at M2's pattern check (exit 1, because the fixture holds only the M1 target).
  - C5: the first recorded `gradlew` call must be `test` with `--rerun-tasks` and `--no-build-cache` and without `--tests`. Control: the self-test passes with the stub.
- `ReviewRound3RegressionTest`: its stub `gradlew` now also accepts `test --rerun-tasks --no-build-cache`. This was changed in `80555aa`, so the class passes both before and after the script fix.

No test in the new classes was skipped in any run (section F.7).

### F.4 Recorded without a code change
1. **Non-ASCII values that the mobile FFI encoder rejects (open question for the maintainer).** The catalog accepts non-ASCII
   `allowedSymbols`, `forbiddenSubstrings` and `accountReference` values (for example `§`, U+FEFF, an emoji or U+0085).
   `crates/upspa-mobile-ffi/src/encoder.rs` rejects them: `browser_normalizes_identically` requires ASCII symbols and substrings,
   and `account_id_is_portable` requires a printable ASCII account ID. A catalog accepted here can therefore fail closed at
   encoding time. This is recorded in `docs/catalog-v1-schema.md` section 8, "Known Gaps". The reviewer read the Rust side but did not run it.
2. **Backslash followed by a control character.** Of the 32 code points U+0000..U+001F after a backslash, Moshi already rejects 31.
   Only U+000A depends on the round 3 Finding 1 check. The reviewer showed this by removing the check in a scratch copy: 31 of the
   32 parameterized cases still passed. The other 31 cases are defense-in-depth controls. Also recorded in "Known Gaps".

### F.5 Commands
```powershell
$env:PATH = "C:\Program Files\Git\bin;" + $env:PATH
.\gradlew clean test --no-daemon
```
```bash
bash run-real-script.sh <module> <out> --selftest-compile-fault
bash run-real-script.sh <module> <out> --inject-compile-fault
bash run-real-script.sh <module> <out>
bash wsl-run-ir.sh <repo> <commit> <out>        # inside WSL
bash win-mutations-ir.sh <repo> <worktree> 6b6c6d1 mutate.py <out>
bash xml-totals.sh build/test-results/test
```
`run-real-script.sh` runs `wsl --shutdown`, then `"C:\Program Files\Git\bin\bash.exe" scripts/mutation-check.sh [args]` with real
Gradle, and records the HEAD, the WSL VM state and the exit code. All driver scripts are in the evidence directory.

### F.6 Real output before the fixes (tests-only commit `80555aa`)
Windows Git Bash, `.\gradlew clean test --no-daemon`, with the WSL VM not running at the start (`unfixed-full-suite-80555aa.txt`,
`unfixed-xml-totals-80555aa.txt`): Gradle exit 1, `420 tests completed, 23 failed, 2 skipped`.

| Scope | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| All 16 classes | 420 | 395 | 23 | 2 |
| `IndependentReviewValidatorRegressionTest` | 49 | 32 | 17 | 0 |
| `IndependentReviewScriptRegressionTest` | 10 | 4 | 6 | 0 |
| `ReviewRound3RegressionTest` | 54 | 53 | 0 | 1 |

Every other class passed, including `CatalogFixtureTest` (76/76), so all fixtures keep their expected results. All 23 failures
are new rejection tests, and all new controls passed. Failure reasons [excerpt (trimmed)]:
```text
Expected Rejected for requireUpper = TRUE, but got Accepted(document=CatalogDocument(schemaVersion=1, entryCount=1))
Expected Rejected for reference = NULL, but got Accepted(document=CatalogDocument(schemaVersion=1, entryCount=1))
Expected Rejected for backslash-apostrophe in allowedSymbols, but got Accepted(document=CatalogDocument(...))
SIGTERM during the run must make the script exit 143. ... WRAPPER: script exit status 0
SIGINT during the run must make the script exit 130. ... WRAPPER: script exit status 0
A failing cleanup rm must not change exit code 2. ... rm stub: refusing to remove isolation directory /tmp/tmp.<id> ... expected: <2> but was: <1>
cd must not fail under CDPATH. ... cd: $'/tmp/mutation-review-fixture<n>/scripts\n/tmp/mutation-review-fixture<n>/scripts/..': No such file or directory
M1 must not count as killed. ... PASS: Mutation M1 KILLED! ...
Baseline must pass --rerun-tasks, got test
```
The first two lines are representative of the 15 Finding A failures. In WSL at `80555aa` (`wsl-ir-tests-80555aa.txt`), the same
23 tests failed for the same reasons: 113 tests, 23 failed, 0 skipped, with `ReviewRound3RegressionTest` passing 54/54.

### F.7 Real output after the fixes (`6b6c6d1`)
**Windows Git Bash, full suite** (`fixed-full-suite-6b6c6d1.txt`, `fixed-xml-totals-6b6c6d1.txt`): Gradle exit 0. The WSL VM was
not running at the start and was running at the end; as recorded in round 3, the round 2 bash probe starts it.

| Scope | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| All 16 classes | 420 | 418 | 0 | 2 |
| `IndependentReviewValidatorRegressionTest` | 49 | 49 | 0 | 0 |
| `IndependentReviewScriptRegressionTest` | 10 | 10 | 0 | 0 |
| `ReviewRound3RegressionTest` | 54 | 53 | 0 | 1 |

Arithmetic: 361 tests before this follow-up + 49 + 10 = 420. The 2 skips are the same as in round 3 (see "Skipped Tests and
Reasons (Windows)"): the mode-644 positive test on NTFS and the round 2 `ProcessBuilder("bash")` probe.

**WSL** (`wsl-ir-tests-6b6c6d1.txt`; ext4 clone, with `scripts/mutation-check.sh` at mode `644 -rw-r--r--`): Gradle exit 0.

| Class | Total | Passed | Failed | Skipped |
| :--- | ---: | ---: | ---: | ---: |
| `IndependentReviewValidatorRegressionTest` | 49 | 49 | 0 | 0 |
| `IndependentReviewScriptRegressionTest` | 10 | 10 | 0 | 0 |
| `ReviewRound3RegressionTest` | 54 | 54 | 0 | 0 |

**Real script runs, repeated at `6b6c6d1`** (Windows Git Bash, real Gradle, with the WSL VM not running at each start):
- `script-selftest-6b6c6d1.txt`: `Baseline PASSED: all passed`, `FATAL: Mutation M1 failed to compile!`,
  `Self-test PASSED: fresh baseline ran, compile fault was reached and rejected with exit code (2).`, `# exit code: 0`.
- `script-inject-compile-fault-6b6c6d1.txt`: `Baseline PASSED: all passed`, `FATAL: Mutation M1 failed to compile!`, `# exit code: 2`.
  Its footer lists two modified doc files: the doc edits for this addendum were made while that run was in progress. They were
  then moved out of the working tree until the normal run below had finished. This mode has no before/after working-tree check.
- `script-normal-6b6c6d1.txt` [excerpt (trimmed)]:
```text
Baseline PASSED: all passed
PASS: Mutation M1 KILLED! (52 tests executed, failing cases: PACKAGE_MISMATCH_CASE_DIFFERENCE)
...
PASS: Mutation M8 KILLED! (52 tests executed, failing cases: ORIGIN_BOUNDARY_NON_HTTPS_REJECTION)
Working tree unchanged: **yes**
- Mutations killed: **8**
- Mutations survived: **0**
SUCCESS: All mutations were killed by the Identity Adversarial Suite!
# exit code: 0
# WSL VM not running (no vmmemWSL process) after the run; git status --short: ''
```

### F.8 Mutation proofs (isolated worktree only)
`win-mutations-ir.sh` created a temporary detached worktree at `6b6c6d1`. For each case it applied one exact-string mutation with
`mutate.py`, ran the named class with `./gradlew test --rerun --tests <class> --no-daemon`, and restored the worktree. It removed
the worktree at the end; `worktree-list-after-ir-proofs.txt` lists only the main checkout. Each `mutation-<id>.txt` contains the
applied diff.

| ID | Check removed | Class run | Result | Failing tests |
| :--- | :--- | :--- | :--- | :--- |
| A | keyword check (`if (false)`) | Validator | **Detected** (15 failures) | all 8 `requireUpper` variants, the array case, all 6 `reference`/`unverifiedReason` variants |
| B | escape allow-list (`if (false)`) | Validator | **Detected** (2 failures) | `\'` in a value, `\'` in a key |
| C1 | TERM handler (`trap cleanup TERM`) | Script | **Detected** (1 failure) | `SIGTERM -> exit 143` |
| C2 | `set +e` in `cleanup` | Script | **Detected** (1 failure) | C2 failing-rm test |
| C3 | `unset CDPATH` | Script | **Detected** (1 failure) | C3 CDPATH test |
| C4 | integer guard (back to `-z` / `-le 0`) | Script | **Detected** (1 failure) | C4 `tests="many"` test |
| C5 | `--rerun-tasks --no-build-cache` on the baseline | Script | **Detected** (1 failure) | C5 baseline-invocation test |

In B, the non-RFC escape controls still pass with the allow-list removed, because Moshi rejects those escapes itself; only `\'`
depends on the new check. Each worktree case ended with `git status --short -> ''`.

### F.9 Evidence and sanitization
Evidence directory: `upspa-assetlink-verifier/docs/pr33-round3-evidence/independent-review/`. It holds the raw outputs, the XML
totals, the mutation proof outputs and the driver scripts (`run-real-script.sh`, `wsl-run-ir.sh`, `win-mutations-ir.sh`,
`mutate.py`, `xml-totals.sh`). Every file in that directory went through the same path-prefix transform as in "Evidence Files and
Sanitization" above, with the same five substitutions in the same order. The files contained no backslash form of the Windows
home prefix and no machine host name. For every file, applying the transform to the original scratch output is byte-identical to
the committed file. The branch name in `worktree-list-after-ir-proofs.txt` was not changed.

### F.10 Not run / limitations
- No CI or device runs were made; all results are local (Windows Git Bash and WSL2).
- The Rust FFI behavior in F.4 (1) was read in `encoder.rs`, not executed.
- The C1 tests send the signal to the script's own process. They do not cover a signal sent to the whole process group (for
  example a CI runner that also kills the Gradle child).
- The C2 test simulates a failing `rm` with a stub. The Windows file lock seen in round 3 was not reproduced with a real Gradle or
  Kotlin daemon.

---

## Fresh review follow-up (head 9c99b60)

A fresh review of head `9c99b60` found one P2: `CatalogEntry.toString()` interpolated the whole policy, and
`NormalizedPasswordPolicy.toString()` printed raw `forbiddenSubstrings` and `allowedSymbols`. A policy can legitimately forbid the
account name or the site hostname, so logging a redacted entry exposed those identifiers. The protocol was the same as before: a
tests-only commit, then the fix, then this addendum. All commits are new; nothing was amended, rebased or pushed.

### G.1 Commits

| Role | Commit |
| :--- | :--- |
| Tests-only commit | `32e58e0` (`32e58e08c2deb81e76bbbb6bc8544c7114020b7d`) |
| Fix commit | `93e317f` (`93e317fc87095aeaf1dbe4df34cdec74328c6d64`) |
| This addendum, evidence and schema doc update | the commit that contains this section |

After `git fetch origin` during this follow-up, `origin/mobile-dev` still resolved to `75a33a2`. `git diff 9c99b60 93e317f` is empty
for `scripts/` and `CatalogValidator.kt`. The evidence is in `upspa-assetlink-verifier/docs/pr33-round3-evidence/fresh-review/`.

### G.2 Fix
`NormalizedPasswordPolicy` has a new `internal fun diagnosticSummary()`, and its `toString()` returns it. `CatalogEntry.toString()`
calls the same function directly, so the entry does not depend on what the policy's `toString()` prints. `equals`, `hashCode`,
`copy`, the public properties and all validation are unchanged. Example:

```text
NormalizedPasswordPolicy(minLen=20, maxLen=32, requireUpper=true, requireLower=false, requireDigit=true, requireSymbol=false, forbidWhitespace=true, allowedSymbolsCount=7, forbiddenSubstringsCount=1)
```

`allowedSymbols` is summarized as a count, in code points, instead of being printed. It is not an identifier in the normal case,
but it is free text that the catalog supplies, and a count is enough for diagnostics. It also keeps the rule simple: no raw policy
string appears in a diagnostic.

### G.3 `toString()` audit of `org.upspa.catalog`

| Class | Rendering | Can render a listed value? | Action |
| :--- | :--- | :--- | :--- |
| `NormalizedPasswordPolicy` | custom | **Yes before the fix:** forbidden substrings, allowed symbols | Fixed |
| `CatalogEntry` (data class) | custom | **Yes before the fix:** through the policy. Origin and account were already redacted | Fixed |
| `AccountReference` (value class) | custom | No: `AccountReference(<redacted>)` | None |
| `AliasEvidenceReference` (data class) | custom | No origin. Prints package name and digest on purpose (see G.8) | None |
| `AliasEvidence` (data class) | generated | Only through the reference above. `unverifiedReason` is an enum; there is no free reason text | None |
| `CatalogDocument` | custom | No: schema version and entry count only | None |
| `CatalogValidationResult.Accepted` / `Rejected` (data classes) | generated | No: delegates to the document or the rejection | None |
| `CatalogRejection` object variants | custom | No: fixed names | None |
| `CatalogRejection` data variants | generated | No listed value reaches them. They hold indexes, enum codes, numbers and paths | None |
| `CatalogSchemaVersion`, `CompatibilityProfileVersion`, `EncoderCounter` (value classes) | generated | No: numbers only | None |
| `AliasEvidenceStatus`, `UnverifiedReason`, rejection code enums | enum name | No | None |
| `CatalogConsumerContract` | interface | No rendering | None |
| Private AST types in `CatalogValidator.kt` (`JsonField`, `AstNode.*`, `AstResult.*`) | generated | **Yes if printed**, but they are private and no code interpolates them (by inspection) | None; file out of scope |

The paths need one note. `parseNode` builds child paths from raw key names, so `DuplicateJsonKey` could in principle carry a raw
key. The validator reads `duplicateKeyPath` only on objects reached through known keys, so the path contains known names only. Two
new controls pin this with an identifier used as a key under an unknown field and under a wrong-type field.

### G.4 New tests
`FreshReviewRedactionRegressionTest` has 27 tests: 15 redaction tests and 12 controls (named `control - ...`).

| Area | Tests |
| :--- | :--- |
| Reviewer cases 1 and 2 through `StrictCatalogValidator.validate`, plus a VERIFIED entry | 3 |
| The same through direct constructors, raw `allowedSymbols` in the entry, exact entry rendering | 4 |
| Policy alone: every forbidden item, raw symbols, exact summary, code-point count, identical rendering | 5 |
| `entries` list rendering | 1 |
| Mixed case (`Synthetic_User` with `synthetic_user`), validated and direct | 2 |
| Controls: empty list shows no identifier (validated and direct), safe fields still shown (entry and policy) | 4 |
| Controls: document and accepted result, alias evidence (two), account reference, rejections (two) | 6 |
| Controls: equality and hashing use raw strings, raw values stay readable to callers | 2 |

`CatalogDocument.toString()` with such an entry is covered by a control, not a redaction test: it passed before the fix, because the
document already printed only counts. The `entries` list rendering is the document-level test that failed.

### G.5 Before and after

| Run | Commit | tests | passed | failed | errors | skipped |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| Previous baseline, Windows | `6b6c6d1` | 420 | 418 | 0 | 0 | 2 |
| Tests only, Windows, full suite | `32e58e0` | 447 | 430 | 15 | 0 | 2 |
| Fix, Windows, full suite | `93e317f` | 447 | 445 | 0 | 0 | 2 |
| Tests only, WSL2 Ubuntu, new class | `32e58e0` | 27 | 12 | 15 | 0 | 0 |
| Fix, WSL2 Ubuntu, new class | `93e317f` | 27 | 27 | 0 | 0 | 0 |

Counts come from the JUnit XML. Windows runs used `.\gradlew clean test --no-daemon` with Git Bash on `PATH`, after `wsl --shutdown`.
The 2 skips are the known ones, in `ReviewRound2RegressionTest` and `ReviewRound3RegressionTest`.

All 15 failures at `32e58e0` are `AssertionFailedError`s whose message shows the raw policy rendering; none is a setup failure, and all
12 controls passed. Excerpt from `unfixed-new-class-32e58e0.txt` (trimmed):

```text
FAIL reviewer case 1 - validated entry does not render an account repeated in forbiddenSubstrings()
    org.opentest4j.AssertionFailedError: CatalogEntry.toString must not contain 'synthetic_user': CatalogEntry(enrolledOrigin=<redacted>, accountReference=<redacted>, ...forbiddenSubstrings=[synthetic_user])...
FAIL reviewer case 2 - validated entry does not render a hostname repeated in forbiddenSubstrings()
    org.opentest4j.AssertionFailedError: CatalogEntry.toString must not contain 'synthetic-host.example.com': CatalogEntry(enrolledOrigin=<redacted>, accountReference=<redacted>, ...forbiddenSubstrings=[synthetic-host.example.com])...
```

### G.6 Mutation proofs (isolated worktree only)
`win-mutations-fr.sh` created a temporary detached worktree at `93e317f`, applied exact-string mutations with `mutate.py`, ran the new
class and restored the worktree after each case. Each `mutation-<id>.txt` contains the applied diff.

| ID | Mutation | Result | Failing tests |
| :--- | :--- | :--- | :--- |
| P | Policy `toString()` back to the raw rendering; entry keeps the summary | **Detected** (5 failures) | the 5 policy tests, for example `policy toString omits every forbiddenSubstrings item` |
| E | Entry renders the raw policy fields itself; policy keeps the summary | **Detected** (10 failures) | reviewer cases 1 and 2, `entry renders the safe policy summary and nothing raw`, the other entry tests |
| L | Entry line back to `policy=$policy`; policy fix stays | **Not detected** (0 failures) | none |
| B | Both line reverts, the behavior at `9c99b60` | **Detected** (15 failures) | the same 15 tests as at `32e58e0` |

L is an equivalent mutant, not a gap in the tests: with the policy fix in place, `$policy` and `diagnosticSummary()` print the same
string, so no test can tell them apart. E is therefore the entry-side proof, because it restores the leaking output in the entry
alone. B shows that the line-by-line revert of both fixes is detected.

### G.7 Evidence and sanitization
The directory holds the raw outputs, XML totals, per-test reasons, mutation outputs, the probe output and the driver scripts
(`run-full-suite.ps1`, `wsl-run-fr.sh`, `win-mutations-fr.sh`, `failure-reasons.py`, `mutate.py`, `xml-totals.sh`). Every file went
through the path-prefix transform in "Evidence Files and Sanitization", with the same five substitutions in the same order. Applying
the transform to each scratch original is byte-identical to the committed file. The branch name in
`worktree-list-after-fr-proofs.txt` was not changed.

### G.8 Not run / limitations
- **Open question, package name.** `AliasEvidenceReference` prints the package name, which the schema allows in logs and
  `CatalogUnitTest` requires. A package name can have the same text as a hostname. A scratch probe in the temporary worktree accepted
  origin `https://synthetic.example` with package `synthetic.example`, and the rendered entry contained that text
  (`probe-package-name.txt`). This was not changed; it needs a maintainer decision.
- `EnrolledOrigin.toString()` returns the raw origin. It is outside this package, and the catalog classes do not call it in their
  renderings, but printing `entry.enrolledOrigin` directly shows the origin.
- Constructor `require` messages were not part of this change. `AndroidPackageName` echoes the rejected value in its message, and the
  policy reports a duplicate symbol as a `U+` code point.
- The counts show how many symbols and forbidden items a policy has, which is much less specific than the values.
- The non-ASCII question stays open. The code-point test uses a direct constructor and pins only the unit of the count.
- `git worktree remove` reported `Filename too long`. The worktree was deregistered, and its leftover directory was removed with a
  long-path `rd`. `worktree-list-after-fr-proofs.txt` lists only the main checkout.
- `scripts/mutation-check.sh` did not change, so it was not re-run. No CI or device runs were made; all results are local.
