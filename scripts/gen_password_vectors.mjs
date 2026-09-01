#!/usr/bin/env node
import { webcrypto } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true });
}

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

let encodeSecretAsPassword, normalizePasswordPolicy;
try {
  const mod = await import(join(repoRoot, 'packages/extension/src/shared/passwordPolicy.ts'));
  encodeSecretAsPassword = mod.encodeSecretAsPassword;
  normalizePasswordPolicy = mod.normalizePasswordPolicy;
} catch (e) {
  console.error('Could not import passwordPolicy.ts:', e.message);
  console.error('Run via: npx tsx scripts/gen_password_vectors.mjs');
  process.exit(1);
}

const SECRET = 'raw-upspa-secret-for-tests';
const SECRET2 = 'another-upspa-secret-b64==';

const cases = [
  { id: 'v001', description: 'default policy, counter 0, alice@example.com',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: 'alice@example.com', counter: 0 },
  { id: 'v002', description: 'default policy, counter 1, alice@example.com',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: 'alice@example.com', counter: 1 },
  { id: 'v003', description: 'default policy, counter 3, alice@example.com',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: 'alice@example.com', counter: 3 },
  { id: 'v004', description: 'maxLen 16',
    secret: SECRET, policy: normalizePasswordPolicy({ maxLen: 16 }), accountId: 'alice@example.com', counter: 0 },
  { id: 'v005', description: 'maxLen 12 (minLen clamps to 8)',
    secret: SECRET, policy: normalizePasswordPolicy({ maxLen: 12 }), accountId: 'alice@example.com', counter: 0 },
  { id: 'v006', description: 'no symbol required',
    secret: SECRET, policy: normalizePasswordPolicy({ requireSymbol: false, allowedSymbols: '' }), accountId: 'alice@example.com', counter: 0 },
  { id: 'v007', description: 'restricted symbols -_.',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 16, maxLen: 20, requireSymbol: true, allowedSymbols: '-_.' }), accountId: 'alice', counter: 0 },
  { id: 'v008', description: 'all classes, minLen=maxLen=8',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 8, maxLen: 8 }), accountId: 'u', counter: 0 },
  { id: 'v009', description: 'fixed length 16 (minLen=maxLen=16)',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 16, maxLen: 16 }), accountId: 'alice', counter: 0 },
  { id: 'v010', description: 'fixed length 32 (minLen=maxLen=32)',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 32, maxLen: 32 }), accountId: 'alice', counter: 0 },
  { id: 'v011', description: 'no upper required',
    secret: SECRET, policy: normalizePasswordPolicy({ requireUpper: false }), accountId: 'alice', counter: 0 },
  { id: 'v012', description: 'no lower required',
    secret: SECRET, policy: normalizePasswordPolicy({ requireLower: false }), accountId: 'alice', counter: 0 },
  { id: 'v013', description: 'no digit required',
    secret: SECRET, policy: normalizePasswordPolicy({ requireDigit: false }), accountId: 'alice', counter: 0 },
  { id: 'v014', description: 'forbiddenSubstrings: ["pass"]',
    secret: SECRET, policy: normalizePasswordPolicy({ forbiddenSubstrings: ['pass'] }), accountId: 'alice', counter: 0 },
  { id: 'v015', description: 'accountId bob, counter 0',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 12, maxLen: 20 }), accountId: 'bob', counter: 0 },
  { id: 'v016', description: 'accountId bob, counter 5 (rotation)',
    secret: SECRET, policy: normalizePasswordPolicy({ minLen: 12, maxLen: 20 }), accountId: 'bob', counter: 5 },
  { id: 'v017', description: 'different secret, default policy, counter 0',
    secret: SECRET2, policy: normalizePasswordPolicy({}), accountId: 'alice@example.com', counter: 0 },
  { id: 'v018', description: 'maxLen 64 (boundary)',
    secret: SECRET, policy: normalizePasswordPolicy({ maxLen: 64 }), accountId: 'alice', counter: 0 },
  { id: 'v019', description: 'lower and digit only, no upper/symbol',
    secret: SECRET, policy: normalizePasswordPolicy({ requireUpper: false, requireSymbol: false, allowedSymbols: '' }), accountId: 'alice', counter: 0 },
  { id: 'v020', description: 'default policy, empty accountId, counter 0',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: '', counter: 0 },
  { id: 'v021', description: 'accountId with uppercase letters (Alice)',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: 'Alice', counter: 0 },
  { id: 'v022', description: 'MUTATED — deliberately corrupted expected value; both implementations must reject',
    secret: SECRET, policy: normalizePasswordPolicy({}), accountId: 'alice@example.com', counter: 0, _mutate: true },
];

console.log('Generating vectors...');
const vectors = [];

for (const c of cases) {
  const real = await encodeSecretAsPassword(c.secret, c.policy, c.accountId, c.counter);
  let expected = real.password;

  if (c._mutate) {
    const chars = [...expected];
    chars[chars.length - 1] = chars[chars.length - 1] === 'a' ? 'b' : 'a';
    expected = chars.join('');
  }

  const vec = { id: c.id, description: c.description, secretB64: c.secret,
    policy: c.policy, accountId: c.accountId, counter: c.counter, expected, reject: c._mutate === true };
  console.log(`  ${c.id}  [${vec.reject ? 'REJECT' : 'OK'}]  ${c.description}`);
  vectors.push(vec);
}

const accepted = vectors.filter(v => !v.reject);
if (accepted.length < 20) {
  console.error(`ERROR: only ${accepted.length} accepted vectors; need >= 20`);
  process.exit(1);
}
console.log(`\nTotal: ${vectors.length} vectors (${accepted.length} accepted, ${vectors.length - accepted.length} rejected)`);

const outPath = join(repoRoot, 'test-vectors/compatibility-profile-v1/vectors.json');
writeFileSync(outPath, JSON.stringify(vectors, null, 2) + '\n');
console.log(`Written to ${outPath}`);
