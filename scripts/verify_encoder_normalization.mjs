#!/usr/bin/env node
// Synthetic normalization probes only. Canonical encoders and profile-v1 stay unchanged.
import { createHash, webcrypto } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import {
  defaultPasswordPolicy,
  encodeSecretAsPassword,
  normalizePasswordPolicy,
} from '../packages/extension/src/shared/passwordPolicy.ts';

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true });
}

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const snapshotPath = join(root, 'crates/upspa-core/tests/fixtures/encoder_normalization.json');
const secretB64 = 'raw-upspa-secret-for-tests';
const accountId = 'alice@example.com';
const counter = 0;

class ProbeFailure extends Error {}

function check(condition, message) {
  if (!condition) throw new ProbeFailure(message);
}

async function makeFixtures() {
  const corpus = JSON.parse(readFileSync(join(root, 'test-vectors/compatibility-profile-v1/vectors.json'), 'utf8'));
  for (const vector of corpus) {
    check(isDeepStrictEqual(normalizePasswordPolicy(vector.policy), vector.policy),
      'profile-v1 contains a non-fixed-point browser policy');
    check(vector.policy.allowedSymbols.length > 0 &&
      [vector.policy.allowedSymbols, ...vector.policy.forbiddenSubstrings, vector.accountId]
        .every((value) => /^[\x00-\x7f]*$/.test(value)),
    'profile-v1 falls outside the documented ASCII/symbol precondition');
  }
  const defaults = normalizePasswordPolicy(defaultPasswordPolicy());
  // This is exactly the review's one-pass input, including the whitespace-only symbols.
  const singlePass = normalizePasswordPolicy({
    ...defaultPasswordPolicy(),
    minLen: 16, maxLen: 20,
    requireSymbol: false, allowedSymbols: ' \t',
  });
  const secondPass = normalizePasswordPolicy(singlePass);
  check(singlePass.allowedSymbols === '', 'single-pass fixture must retain the empty-symbol gap');
  check(secondPass.allowedSymbols === defaults.allowedSymbols, 'second pass must substitute defaults');
  const cases = [];
  const liveCases = [];
  for (const [id, policy, browserFixedPoint, expectParity] of [
    ['default-policy', defaults, true, true],
    ['single-pass-empty-symbols', singlePass, false, false],
    ['shared-fixed-point', secondPass, true, true],
  ]) {
    check(
      isDeepStrictEqual(normalizePasswordPolicy(policy), policy) === browserFixedPoint,
      `${id}: unexpected browser normalization fixed-point classification`,
    );
    const result = await encodeSecretAsPassword(secretB64, policy, accountId, counter);
    const fixture = {
      id, secretB64, accountId, counter, policy, browserFixedPoint, expectParity,
      browserPasswordSha256: createHash('sha256').update(result.password, 'utf8').digest('hex'),
    };
    cases.push(fixture);
    // Actual candidates exist only in the private, disposable live fixture, never snapshots/logs.
    liveCases.push({ ...fixture, browserPassword: result.password });
  }
  return { snapshot: { cases }, live: { cases: liveCases } };
}

function runRust(path, requireParity) {
  return spawnSync('cargo', ['test', '--locked', '-p', 'upspa-core', '--test', 'encoder_normalization'], {
    cwd: root,
    env: {
      ...process.env,
      UPSPA_NORMALIZATION_FIXTURES: path,
      UPSPA_NORMALIZATION_REQUIRE_PARITY: requireParity ? '1' : '0',
    },
    encoding: 'utf8',
  });
}

async function main() {
  check(process.argv.length === 2 || (process.argv.length === 3 && process.argv[2] === '--write-fixtures'),
    'usage: node --import tsx scripts/verify_encoder_normalization.mjs [--write-fixtures]');
  const fixtures = await makeFixtures();
  const serialized = JSON.stringify(fixtures.snapshot, null, 2) + '\n';
  if (process.argv[2] === '--write-fixtures') {
    writeFileSync(snapshotPath, serialized);
    console.log('Wrote synthetic normalization snapshots; profile-v1 unchanged.');
    return;
  }
  // The fresh browser outputs must also match the standalone Rust-test snapshots.
  check(readFileSync(snapshotPath, 'utf8') === serialized, 'normalization snapshots differ from canonical browser');
  const temp = mkdtempSync(join(tmpdir(), 'upspa-normalization-'));
  try {
    const path = join(temp, 'fixtures.json');
    writeFileSync(path, JSON.stringify(fixtures.live), { mode: 0o600 });
    const strict = runRust(path, true);
    const strictOutput = (strict.stdout ?? '') + (strict.stderr ?? '');
    check(strict.status !== null && strict.status !== 0, 'strict parity probe must reject the known gap');
    check(strictOutput.includes('test default_policy_matches_browser ... ok'), 'default-policy control must pass');
    check(strictOutput.includes('test shared_fixed_point_matches_browser ... ok'), 'shared fixed-point control must pass');
    check(strictOutput.includes('test single_browser_pass_empty_symbols_is_excluded ... FAILED') &&
      strictOutput.includes('single-pass-empty-symbols: browser/Rust parity mismatch'),
    'strict probe must fail specifically on the empty-symbol parity gap');
    console.log('Strict cross-language probe: both controls match; single-pass empty-symbol parity fails as expected.');

    const regression = runRust(path, false);
    check(regression.status === 0, 'normalization gap regression failed');
    console.log('Normalization regressions: shared controls match; excluded gap retained.');
    // Captured Rust output is never dumped, including on unexpected outcomes.
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}

main().catch((error) => {
  const reason = error instanceof ProbeFailure ? error.message : 'normalization qualification probe';
  console.error(`FAIL: ${reason}; captured output withheld.`);
  process.exitCode = 1;
});
