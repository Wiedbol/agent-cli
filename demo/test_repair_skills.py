"""Check verifier safety and subprocess accounting with temporary roots/local HTTP only."""
import os
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('verify_repair_skills.py').resolve()


class OfflineVerifierTest(unittest.TestCase):
    def invoke(self, *args):
        env = os.environ.copy()
        env.pop('DEEPSEEK_API_KEY', None)
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=env, capture_output=True,
                              encoding='utf-8', timeout=15)

    def test_help_does_not_require_jar_key_or_console(self):
        result = self.invoke('--help')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('SIMULATED', result.stdout)
        self.assertIn('--output', result.stdout)
        self.assertNotIn('--endpoint', result.stdout)

    def test_rejects_existing_or_repository_output_before_any_execution(self):
        with tempfile.TemporaryDirectory(prefix='offline-preflight-') as folder:
            base = Path(folder).resolve()
            keep = base / 'keep.txt'
            keep.write_text('preserve', encoding='utf-8')
            result = self.invoke('--output', str(base), '--java', 'missing-java', '--jar', str(base / 'missing.jar'))
            self.assertEqual(2, result.returncode)
            self.assertIn('already exists', result.stderr)
            self.assertEqual(['keep.txt'], sorted(p.name for p in base.iterdir()))
            self.assertEqual('preserve', keep.read_text(encoding='utf-8'))
        forbidden = SCRIPT.parent.parent / 'must-not-create-proof'
        result = self.invoke('--output', str(forbidden))
        self.assertEqual(2, result.returncode)
        self.assertIn('repository', result.stderr)
        self.assertFalse(forbidden.exists())

    def test_keyless_local_model_is_distinct_from_a_read_only_process(self):
        spec = importlib.util.spec_from_file_location('repair_delivery', SCRIPT)
        delivery = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(delivery)
        with tempfile.TemporaryDirectory(prefix='offline-request-boundary-') as folder:
            base = Path(folder).resolve()
            jar = base / 'placeholder.jar'
            jar.write_bytes(b'not executed; this check launches a Python HTTP client')
            verifier = delivery.Verifier(sys.executable, jar, base)
            verifier.root.mkdir()
            client = [sys.executable, '-c', 'import os,urllib.request; assert "DEEPSEEK_API_KEY" not in os.environ; '
                      + 'urllib.request.urlopen(urllib.request.Request(' + repr(verifier.endpoint)
                      + ', data=b"{}", headers={"Content-Type":"application/json"})).read()']
            try:
                verifier.fixture.phase('generate')
                verifier.invoke([], 'keyless-model', command=client, allow_model=True)
                self.assertEqual(1, len(verifier.fixture.calls))
                verifier.fixture.phase('generate')
                with self.assertRaisesRegex(AssertionError, 'read-only'):
                    verifier.invoke([], 'unexpected-query-call', command=client)
            finally:
                verifier.close()


if __name__ == '__main__':
    unittest.main()
