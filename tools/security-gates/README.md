# Mobile Security Gates

Small, dependency-free checks for UpSPA's Android app and mobile FFI (foreign function interface) code. The gates use Python 3 from the standard library, nothing extra needs to be installed.

## Run Commands

Run all gates from the repository root:

```bash
python3 tools/security-gates/run_gates.py .
```

Run one gate:

```bash
python3 tools/security-gates/run_gates.py . --gate exported_components
```

Get JSON output:

```bash
python3 tools/security-gates/run_gates.py . --json
```

Run the negative fixtures:

```bash
python3 tools/security-gates/test_negative_fixtures.py
```

The commands return `0` when there are no FAIL findings and `1` when there is at least one FAIL. The GitHub Actions workflow uses the same rule.

## Security Checks

| Gate | Performed Checks |
|---|---|
| `exported_components` | Exported Android activities, services, receivers, and providers that do not have a permission; except for known legitimate cases, such as the launcher and properly protected system services. |
| `backup_config` | `android:allowBackup` is missing or set to `true`. |
| `accessibility_clipboard` | Accessibility API usage and clipboard writes near credential-like names. |
| `screenshot_recents` | Sensitive activities that do not use `FLAG_SECURE` or disable recents screenshots. |
| `pending_intent` | Implicit `PendingIntent`s and mutable `PendingIntent`s that do not have a valid reason to be mutable. |
| `network_cleartext` | Unexpected `INTERNET` permission (waivable; see the gate's docstring) and any setting that allows cleartext traffic (never waivable). |
| `secret_logging` | Secret-like values used near logging or plaintext persistence calls. |
| `uniffi_secret_fields` | Secret-like UniFFI fields or exported function parameters stored as plain `String` or byte types instead of a protected type. |

## PendingIntent Exception

The Autofill flow needs a mutable `PendingIntent`. Android adds the authentication result to that intent before sending it. An immutable `PendingIntent` would stop that flow from working.

Therefore, the gate allows the existing Autofill authentication case, but it still requires `FLAG_ONE_SHOT` or `FLAG_CANCEL_CURRENT`. Other mutable `PendingIntent`s fail.

The Autofill exception is found by looking for related Autofill calls in the same file. This is a simple source check, not full call-graph analysis.

## Files Parsing Mechanism

Manifest files and network security configuration files are parsed as XML with Python's standard library.

Kotlin, Java, and Rust use small regular expressions where a full parser would add dependencies. These checks are intentionally simple and document their limits.

For example, the secret logging check can miss a secret that is passed through a helper function before it reaches the logging or storage call.

## Negative Fixtures

Each folder under `fixtures/negative/` contains a small example that is supposed to fail.

`test_negative_fixtures.py` runs all gates on each fixture. It checks both:

1. the expected gate fails, and
2. no other gate fails.

The second check keeps the fixtures focused. Explanations are kept in each fixture's `NOTES.md` rather than inside source comments, because these gates inspect source text directly.

The network fixture covers both network checks, which are the unexpected `INTERNET` permission and the cleartext traffic. They are reported by the same gate, while unrelated gates stay clean.

## Known Limits

These are source checks, not full security analysis.

- `secret_logging` only checks the logging or persistence call itself. It may miss a secret that is passed through a helper function first. This is a known limitation from the previous task (simple source checks).
- `accessibility_clipboard` uses nearby variable names, so it can miss secrets stored in a generically named variable and can sometimes report an unrelated match.
- `uniffi_secret_fields` relies on field and parameter names. It does not verify that a custom secret type really zeroizes or hides its value.
- `pending_intent` detects the Autofill exception by nearby code in the same file, not by following the whole call graph.

These limits are written down on purpose. The gates are meant to catch common regressions in CI, not replace code review or runtime testing.
