import json
import os
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest
import shutil
import subprocess
import sys

import lab_v2 as lab


class ControlTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='repair-lab-v2-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'workspace'
        self.archive = self.base / 'archive'

    def test_frozen_catalog_separates_stages_and_exposes_only_current_business_inputs(self):
        catalog = lab.describe()
        self.assertEqual(catalog['version'], 'repair-lab-2')
        self.assertEqual(len(catalog['stages']['formal']), 3)
        self.assertEqual(catalog['stages']['required-regression'], catalog['stages']['initial-development'])
        self.assertEqual({item['kind'] for item in catalog['cases'] if item['id'] in catalog['stages']['formal']},
                         {'related', 'exclusion'})
        lab.initialize(self.root, self.archive, 'source-delivery')
        self.assertEqual(set(p.relative_to(self.root).as_posix() for p in self.root.rglob('*') if p.is_file()),
                         set(lab.inputs('source-delivery')) | {lab.MARKER})
        manifest = json.loads((self.archive / 'current.json').read_text(encoding='utf-8'))
        self.assertIn('source-delivery', str(manifest))
        visible = '\n'.join(p.read_text(encoding='utf-8') for p in self.root.rglob('*') if p.is_file())
        for hidden in ('source-delivery', 'formal-', 'expectedFailures', 'reference', 'repair-lab-1'):
            self.assertNotIn(hidden, visible)
        self.assertEqual((self.root / lab.SOURCE).read_bytes(), lab.inputs('source-delivery')[lab.SOURCE])

    def test_reset_archives_history_and_build_output_without_reexposing_them(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        marker = (self.root / lab.MARKER).read_bytes()
        for name in ('.agent/tasks/proof.json', 'target/old-source.txt'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('PRIVATE_HISTORY', encoding='utf-8')
        lab.reset(self.root, self.archive, 'dev-points')
        self.assertEqual(marker, (self.root / lab.MARKER).read_bytes())
        self.assertFalse((self.root / '.agent').exists())
        self.assertFalse((self.root / 'target').exists())
        self.assertEqual((self.root / lab.SOURCE).read_bytes(), lab.inputs('dev-points')[lab.SOURCE])
        snapshots = list((self.archive / 'history').glob('*/workspace'))
        self.assertEqual(len(snapshots), 1)
        self.assertEqual((snapshots[0] / '.agent/tasks/proof.json').read_text(), 'PRIVATE_HISTORY')
        self.assertEqual((snapshots[0] / lab.SOURCE).read_bytes(), lab.inputs('source-delivery')[lab.SOURCE])
        self.assertFalse((self.archive / 'pending.json').exists())

    def test_reference_keeps_frozen_acceptance_and_replaces_hard_links(self):
        lab.initialize(self.root, self.archive, 'formal-grammar')
        tests = (self.root / lab.TEST).read_bytes()
        external = self.base / 'unrelated.java'
        external.write_text('KEEP', encoding='utf-8')
        source = self.root / lab.SOURCE
        source.unlink()
        os.link(external, source)
        lab.reference(self.root, self.archive)
        self.assertEqual(external.read_text(), 'KEEP')
        self.assertEqual(source.read_bytes(), lab.inputs('formal-grammar', repaired=True)[lab.SOURCE])
        self.assertEqual(tests, (self.root / lab.TEST).read_bytes())

    def test_refuses_existing_related_noncanonical_and_repository_roots(self):
        self.root.mkdir()
        user = self.root / 'user.txt'
        user.write_text('KEEP')
        with self.assertRaises(ValueError): lab.initialize(self.root, self.archive, 'source-delivery')
        self.assertEqual(user.read_text(), 'KEEP')
        with self.assertRaises(ValueError): lab.workspace(lab.REPO / 'new-lab')
        with self.assertRaises(ValueError): lab.workspace(Path('relative-lab'))
        with self.assertRaises(ValueError): lab.workspace(self.base / 'a' / '..' / 'b')
        with self.assertRaises(ValueError): lab.initialize(self.base / 'fresh', self.base / 'fresh/control', 'source-delivery')
        with self.assertRaises(ValueError): lab.initialize(self.base / 'nested/worker', self.base / 'nested', 'source-delivery')
        other = self.base / 'other-checkout'
        other.mkdir()
        (other / '.git').mkdir()
        with self.assertRaises(ValueError): lab.workspace(other / 'new-lab')
        self.assertFalse(self.archive.exists())

    def test_unknown_files_are_not_moved_or_overwritten(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        user = self.root / 'answer-from-other-round.txt'
        user.write_text('KEEP')
        before = (self.root / lab.SOURCE).read_bytes()
        with self.assertRaises(ValueError): lab.reset(self.root, self.archive, 'formal-bulk')
        self.assertEqual(user.read_text(), 'KEEP')
        self.assertEqual((self.root / lab.SOURCE).read_bytes(), before)
        self.assertFalse((self.archive / 'history').exists())

    def test_refuses_moved_workspace_and_copied_archive(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        copied = self.base / 'copied-archive'
        shutil.copytree(self.archive, copied)
        with self.assertRaises(ValueError): lab.reset(self.root, copied, 'dev-points')
        moved = self.base / 'moved'
        self.root.rename(moved)
        with self.assertRaises(ValueError): lab.reset(moved, self.archive, 'dev-points')
        self.assertTrue((moved / lab.SOURCE).exists())

    def test_refuses_changed_acceptance_and_manifest(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        (self.root / lab.TEST).write_text('changed')
        with self.assertRaises(ValueError): lab.reference(self.root, self.archive)
        (self.archive / 'current.json').write_text('{}')
        with self.assertRaises(ValueError): lab.reset(self.root, self.archive, 'dev-points')
        self.assertEqual((self.root / lab.TEST).read_text(), 'changed')

    def test_rejects_symlinked_inputs_without_touching_external_content(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        source = self.root / lab.SOURCE
        outside = self.base / 'outside.java'
        outside.write_text('KEEP')
        source.unlink()
        try:
            source.symlink_to(outside)
        except OSError:
            # Windows without symlink privilege still has path-escape and hard-link checks in other tests.
            self.skipTest('OS does not permit creating symbolic links')
        with self.assertRaises(ValueError): lab.reset(self.root, self.archive, 'dev-points')
        self.assertEqual(outside.read_text(), 'KEEP')

    def test_pending_reset_retains_evidence_and_blocks_automatic_replay(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        real_write = lab.write
        def fail_new_source(path, data):
            if path == self.root / lab.SOURCE:
                raise OSError('injected disk failure after archival')
            real_write(path, data)
        with patch.object(lab, 'write', side_effect=fail_new_source):
            with self.assertRaises(OSError): lab.reset(self.root, self.archive, 'dev-points')
        self.assertTrue((self.archive / 'pending.json').exists())
        backup = list((self.archive / 'history').glob('*/workspace'))[0]
        self.assertEqual((backup / lab.SOURCE).read_bytes(), lab.inputs('source-delivery')[lab.SOURCE])
        with self.assertRaises(ValueError): lab.reset(self.root, self.archive, 'dev-points')

    def test_every_scenario_has_exactly_current_files_and_stable_project_identity(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        marker = (self.root / lab.MARKER).read_bytes()
        for case in lab.CASES:
            lab.reset(self.root, self.archive, case)
            self.assertEqual(marker, (self.root / lab.MARKER).read_bytes())
            files = {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
            self.assertEqual(set(files), set(lab.inputs(case)) | {lab.MARKER})
            for name, value in lab.inputs(case).items(): self.assertEqual(files[name], value)

    def test_exclusion_wrong_steps_preserve_original_failure_and_break_zero_constraint(self):
        for case in ('dev-whitespace', 'formal-grammar'):
            data = lab.metadata(case)
            self.assertIn('shouldRejectZero', data['exclusion']['wrongFailures'])
            self.assertTrue(set(data['expectedFailures']).issubset(data['exclusion']['wrongFailures']))
            self.assertIn('须拒绝', data['exclusion']['rejectionEvidence'])
            self.assertNotEqual(lab.inputs(case)[lab.SOURCE], lab.inputs(case, wrong=True)[lab.SOURCE])

    def test_frozen_inputs_survive_windows_checkout_line_endings(self):
        clone = self.base / 'clone/examples/repair-lab-v2'
        shutil.copytree(lab.HOME, clone, ignore=shutil.ignore_patterns('__pycache__'))
        pom = clone / 'template/pom.xml'
        pom.write_bytes(pom.read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n'))
        result = subprocess.run([sys.executable, str(clone / 'control/lab_v2.py'), 'describe'],
                                capture_output=True, encoding='utf-8', env={**os.environ, 'PYTHONIOENCODING': 'utf-8'})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), lab.describe())

    def test_cli_rejects_silent_scenario_override_on_reference(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        before = (self.root / lab.SOURCE).read_bytes()
        result = subprocess.run([sys.executable, str(lab.HOME / 'control/lab_v2.py'), 'reference',
                                 '--workspace', str(self.root), '--archive', str(self.archive), '--scenario', 'formal-bulk'],
                                capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual((self.root / lab.SOURCE).read_bytes(), before)

    def test_declared_derived_quantity_shortcuts_have_operator_negative_controls(self):
        lab.initialize(self.root, self.archive, 'feedback-product')
        for case, statement in (('feedback-product', 'return units >= 44000;'),
                                ('formal-bulk', 'return items >= 100;'),
                                ('formal-remaining', 'return used >= 13;')):
            lab.reset(self.root, self.archive, case)
            lab.reference(self.root, self.archive, wrong=True)
            self.assertIn(statement, (self.root / lab.SOURCE).read_text(encoding='utf-8'))

    def test_reset_from_inside_model_root_is_rejected_before_archival(self):
        lab.initialize(self.root, self.archive, 'source-delivery')
        before = (self.root / lab.SOURCE).read_bytes()
        result = subprocess.run([sys.executable, str(lab.HOME / 'control/lab_v2.py'), 'reset',
                                 '--workspace', str(self.root), '--archive', str(self.archive), '--scenario', 'dev-points'],
                                cwd=self.root, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual((self.root / lab.SOURCE).read_bytes(), before)
        self.assertFalse((self.archive / 'pending.json').exists())
        self.assertFalse((self.archive / 'history').exists())


if __name__ == '__main__':
    unittest.main()
