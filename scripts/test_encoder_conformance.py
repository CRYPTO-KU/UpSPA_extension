#!/usr/bin/env python3
"""Synthetic regression controls for qualification orchestration (stdlib only)."""
import fnmatch
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from encoder_conformance_summary import CHECKS, render

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/encoder-conformance.yml'
PROFILE_CHECK = ('profile_changes=$(git status --porcelain --untracked-files=all -- '
                 'test-vectors/compatibility-profile-v1/) && test -z "$profile_changes"')


class WorkflowControls(unittest.TestCase):
    def test_regeneration_rejects_corpus_manifest_and_untracked_changes(self):
        self.assertIn(PROFILE_CHECK, WORKFLOW.read_text())
        self.assertIn(PROFILE_CHECK, (ROOT / 'scripts/run_encoder_conformance.sh').read_text())
        with tempfile.TemporaryDirectory(prefix='encoder-regeneration-control-') as temp:
            copy = Path(temp) / 'review'
            subprocess.run(['git', 'clone', '--quiet', '--no-hardlinks',
                            str(ROOT), str(copy)], check=True, capture_output=True)
            profile = copy / 'test-vectors/compatibility-profile-v1'
            def run_check():
                return subprocess.run(['bash', '-c', PROFILE_CHECK], cwd=copy,
                                      capture_output=True, text=True)
            self.assertEqual(run_check().returncode, 0)
            for filename in ('vectors.json', 'manifest.json', 'unexpected-synthetic.json'):
                path = profile / filename
                original = path.read_bytes() if path.exists() else None
                try:
                    path.write_bytes((original or b'') + b'\n')
                    result = run_check()
                    self.assertNotEqual(result.returncode, 0, filename)
                    self.assertEqual(result.stdout + result.stderr, '')
                finally:
                    if original is None:
                        path.unlink()
                    else:
                        path.write_bytes(original)
                self.assertEqual(run_check().returncode, 0)

    def test_failed_and_skipped_steps_are_not_reported_as_success(self):
        steps = {key: {'outcome': 'success'} for key in CHECKS}
        steps['rust_corpus'] = {'outcome': 'failure', 'conclusion': 'success'}
        steps['ts_corpus'] = {'outcome': 'skipped'}
        steps['security'] = {'outcome': 'cancelled'}
        steps['normalization'] = {'outcome': 'failure'}
        del steps['regeneration']
        summary = render(steps)
        self.assertIn('| Rust corpus | failure |', summary)
        self.assertIn('| TypeScript corpus | skipped |', summary)
        self.assertIn('| Security gates and fixtures | cancelled |', summary)
        self.assertIn('| Shared normalization controls and excluded gap | failure |', summary)
        self.assertIn('| Deterministic regeneration | skipped |', summary)
        self.assertIn('| Rust properties and boundaries | success |', summary)
        workflow = WORKFLOW.read_text()
        self.assertIn('ENCODER_STEPS_JSON: ${{ toJSON(steps) }}', workflow)
        self.assertIn('if: always()', workflow)
        self.assertIn('run: python3 scripts/encoder_conformance_summary.py', workflow)
        self.assertNotIn('| Passed |', workflow)
        for key in CHECKS:
            self.assertIn(f'id: {key}\n', workflow)

    def test_workflow_pin_overrides_root_stable_for_nested_helpers(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('channel = "stable"', (ROOT / 'rust-toolchain.toml').read_text())
        self.assertIn('    runs-on: ubuntu-latest\n    env:\n', workflow)
        self.assertIn('      RUSTUP_TOOLCHAIN: "1.95.0"', workflow)
        self.assertIn('          toolchain: "1.95.0"', workflow)
        self.assertIn('test "$(rustc --version | cut -d \' \' -f 2)" = "$RUSTUP_TOOLCHAIN"', workflow)
        self.assertIn('node-version: "24.19.0"', workflow)
        self.assertIn('test "$(node --version)" = "v24.19.0"', workflow)

    def test_dependency_only_changes_trigger_both_events(self):
        # Parse the deliberately simple path lists without adding a YAML dependency.
        workflow = WORKFLOW.read_text()
        for event in ('push', 'pull_request'):
            # Event blocks have deeper indentation; end at the next two-space key.
            lines = workflow.split(f'  {event}:\n', 1)[1].splitlines()
            patterns = []
            for line in lines:
                if line.startswith('  ') and not line.startswith('    '):
                    break
                if line.strip().startswith('- "'):
                    patterns.append(json.loads(line.strip()[2:]))
            for changed in ('Cargo.toml', 'Cargo.lock', 'crates/upspa-wasm/Cargo.toml',
                            'crates/synthetic/Cargo.lock',
                            'package.json', 'package-lock.json',
                            'packages/extension/package.json',
                            'packages/upspa-js/package-lock.json', 'rust-toolchain.toml',
                            'scripts/verify_encoder_normalization.mjs',
                            'crates/upspa-core/tests/encoder_normalization.rs',
                            'crates/upspa-core/tests/fixtures/encoder_normalization.json'):
                # GitHub **/ also matches a root file; fnmatch requires handling it.
                self.assertTrue(any(fnmatch.fnmatchcase(changed, pattern) or
                                    (pattern.startswith('**/') and
                                     fnmatch.fnmatchcase(changed, pattern[3:]))
                                    for pattern in patterns), (event, changed))


class LeakageControls(unittest.TestCase):
    def run_helper(self, consumer=None, leaked=None, marker=True, status=1):
        with tempfile.TemporaryDirectory(prefix='encoder-control-') as temp:
            root = Path(temp)
            (root / 'scripts').mkdir()
            corpus_dir = root / 'test-vectors/compatibility-profile-v1'
            corpus_dir.mkdir(parents=True)
            corpus = ROOT / 'test-vectors/compatibility-profile-v1/vectors.json'
            (corpus_dir / 'vectors.json').write_bytes(corpus.read_bytes())
            helper = root / 'scripts/verify_corpus_corruption.sh'
            helper.write_bytes((ROOT / 'scripts/verify_corpus_corruption.sh').read_bytes())
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for command, label, notification in (
                ('cargo', 'Rust', 'password mismatch for vector IDs: v001'),
                ('npm', 'TypeScript', 'v001: password mismatch'),
            ):
                output = notification if marker or label != consumer else 'unexpected failure'
                if label == consumer and leaked:
                    output += '\n' + leaked
                code = status if label == consumer else 1
                fake = bin_dir / command
                fake.write_text('#!/usr/bin/env python3\nimport sys\n'
                                f'print({output!r})\nsys.exit({code})\n')
                fake.chmod(0o755)
            env = {**os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH']}
            return subprocess.run(['bash', str(helper)], env=env, capture_output=True, text=True)

    def test_safe_mismatches_pass(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_complete_candidate_and_input_logging_fail_without_echoing_output(self):
        vectors = json.loads((ROOT / 'test-vectors/compatibility-profile-v1/vectors.json').read_text())
        protected = {v[key] for v in vectors for key in ('expected', 'secretB64')}
        protected.add('corrupted_expected_v001')
        for consumer in ('Rust', 'TypeScript'):
            for value in protected:
                with self.subTest(consumer=consumer):
                    result = self.run_helper(consumer, value)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('leaked protected synthetic fixture material', result.stderr)
                    self.assertNotIn(value, result.stdout + result.stderr)

    def test_missing_marker_and_unexpected_success_do_not_dump_captured_output(self):
        for consumer in ('Rust', 'TypeScript'):
            for marker, status in ((False, 1), (True, 0)):
                result = self.run_helper(consumer, 'unchecked-output-sentinel', marker, status)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('unchecked-output-sentinel', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
