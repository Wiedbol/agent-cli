import json
from pathlib import Path
import tempfile
import unittest
import summarize


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name) / 'archive'
        self.archive.mkdir()
        self.output = Path(self.temp.name) / 'output'
        self.results = []
        for repetition in range(3):
            for scenario in ('eval-coupon', 'eval-stock', 'eval-quantity'):
                for enabled in (True, False):
                    self.results.append({'run': {'order': len(self.results) + 1, 'scenario': scenario,
                                                'experiencesEnabled': enabled}, 'initial': {'source': scenario},
                                         'requests': 1, 'tools': 0, 'state': 'Paused', 'promptTokens': None,
                                         'completionTokens': None, 'elapsedMillis': 2, 'humanInterventions': 0})
        self.write('config.json', {'simulated': False})
        self.write('frozen.json', [])

    def write(self, name, value):
        (self.archive / name).write_text(json.dumps(value), encoding='utf-8')

    def test_retains_all_pauses_and_unknown_usage(self):
        self.write('results.json', self.results)
        summarize.summarize(self.archive, self.output)
        stats = json.loads((self.output / 'statistics.json').read_text())['ALL/on']
        self.assertEqual(stats['states'], {'Paused': 9})
        self.assertEqual(stats['promptTokens']['unknown'], 9)
        self.assertIsNone(stats['promptTokens']['total'])
        self.assertEqual(len(json.loads((self.output / 'results.json').read_text())), 18)
        with self.assertRaises(FileExistsError):
            summarize.summarize(self.archive, self.output)

    def test_rejects_incomplete_batch_without_publishing(self):
        self.write('results.json', self.results[:-1])
        with self.assertRaises(AssertionError):
            summarize.summarize(self.archive, self.output)
        self.assertFalse(self.output.exists())

    def test_rejects_unequal_initial_inputs(self):
        self.results[1]['initial'] = {'source': 'changed'}
        self.write('results.json', self.results)
        with self.assertRaises(AssertionError):
            summarize.summarize(self.archive, self.output)
        self.assertFalse(self.output.exists())
