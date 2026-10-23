import json
import os
from pathlib import Path
import tempfile
import unittest
import lab

class ControlTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='repair-lab-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'workspace'

    def test_reset_preserves_identity_evidence_and_freezes_acceptance(self):
        lab.initialize(self.root, 'source-shipping')
        evidence = self.root / '.agent/source-proof'
        evidence.parent.mkdir()
        evidence.write_text('keep')
        for case in lab.CASES:
            lab.reset(self.root, case)
            expected = lab.inputs(case)
            self.assertEqual(set(p.relative_to(self.root).as_posix() for p in self.root.rglob('*.java')), {lab.SOURCE, lab.TEST})
            self.assertEqual((self.root / lab.SOURCE).read_bytes(), expected[lab.SOURCE])
            self.assertNotEqual(expected[lab.SOURCE], lab.inputs(case, True)[lab.SOURCE])
            before = (self.root / lab.TEST).read_bytes()
            lab.reference(self.root, case)
            self.assertEqual(before, (self.root / lab.TEST).read_bytes())
            self.assertEqual((self.root / lab.SOURCE).read_bytes(), lab.inputs(case, True)[lab.SOURCE])
            self.assertEqual(evidence.read_text(), 'keep')
            self.assertEqual(json.loads(lab.marker(self.root).read_text())['root'], str(self.root))

    def test_refuses_existing_unmarked_moved_or_repository_workspace(self):
        self.root.mkdir()
        user = self.root / 'user.txt'
        user.write_text('keep')
        with self.assertRaises(ValueError): lab.initialize(self.root, 'source-shipping')
        with self.assertRaises(FileNotFoundError): lab.reset(self.root, 'source-shipping')
        self.assertEqual(user.read_text(), 'keep')
        with self.assertRaises(ValueError): lab.workspace(str(lab.REPO / 'example-workspace'))

    def test_rejects_wrong_marker_and_changed_acceptance(self):
        lab.initialize(self.root, 'source-shipping')
        lab.marker(self.root).write_text(json.dumps({'root': str(self.base), 'version': lab.VERSION}))
        with self.assertRaises(ValueError): lab.reset(self.root, 'source-shipping')
        lab.marker(self.root).write_text(json.dumps({'root': str(self.root), 'version': lab.VERSION}))
        (self.root / lab.TEST).write_text('changed')
        with self.assertRaises(ValueError): lab.reference(self.root, 'source-shipping')

    def test_reset_does_not_follow_hard_links(self):
        lab.initialize(self.root, 'source-shipping')
        user = self.base / 'user.java'
        user.write_text('keep')
        target = self.root / lab.SOURCE
        target.unlink()
        os.link(user, target)
        lab.reset(self.root, 'source-shipping')
        self.assertEqual(user.read_text(), 'keep')

if __name__ == '__main__': unittest.main()
