import unittest
import replay


class ReplayTest(unittest.TestCase):
    def test_keeps_real_event_intervals_and_redacts_disk_output(self):
        source = ('header user\n@@EVENT 2026-10-23T15:00:00+08:00 start\n'
                  'output <script>\napi_key=private-value\n'
                  '@@EVENT 2026-10-23T15:00:07+08:00 end\n')
        events = replay.parse(source, {})
        self.assertEqual([e['seconds'] for e in events], [0, 7])
        self.assertNotIn('header user', str(events))
        self.assertNotIn('private-value', str(events))
        self.assertIn('<script>', events[0]['text'])
        html = replay.html(events)
        self.assertNotIn('output <script>', html)
        self.assertIn('\\u003cscript>', html)

    def test_rejects_missing_or_backward_clock_markers(self):
        with self.assertRaises(ValueError):
            replay.parse('no actual recording markers', {})
        with self.assertRaises(ValueError):
            replay.parse('@@EVENT 2026-10-23T15:00:07+08:00 one\n'
                         '@@EVENT 2026-10-23T15:00:00+08:00 two\n', {})

    def test_anonymizes_only_supplied_paths(self):
        events = replay.parse('@@EVENT 2026-10-23T15:00:00+08:00 start\nC:/private/work/file\n',
                              {'C:/private/work': '<WORKSPACE>'})
        self.assertIn('<WORKSPACE>/file', events[0]['text'])
