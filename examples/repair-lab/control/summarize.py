"""Offline descriptive analysis of a completed REAL batch; never calls a model."""
import collections
import hashlib
import json
from pathlib import Path
import statistics
import sys


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(archive, output):
    results = load(archive / 'results.json')
    assert len(results) == 18 and [r['run']['order'] for r in results] == list(range(1, 19))
    cells = collections.Counter((r['run']['scenario'], r['run']['experiencesEnabled']) for r in results)
    assert len(cells) == 6 and set(cells.values()) == {3}
    assert all(0 <= r['requests'] <= 12 for r in results)
    assert sum(r['requests'] for r in results) <= 216
    config = load(archive / 'config.json')
    assert config['simulated'] is False
    for scenario, _ in cells:
        hashes = [r['initial'] for r in results if r['run']['scenario'] == scenario]
        assert all(value == hashes[0] for value in hashes)
    evidence = []
    for result in results:
        directory = archive / f"run-{result['run']['order']}" / '.agent/tasks'
        states = list(directory.glob('*/state.json')) if directory.exists() else []
        checks = []
        for state in states:
            task = load(state)
            assert task['experiencesEnabled'] == result['run']['experiencesEnabled']
            assert task['progress']['requests'] == result['requests']
            for ref in task['checks']:
                proof = state.parent / ref['id'] / 'result.json'
                assert digest(proof) == ref['sha256']
                data = load(proof)
                for name, sha in data['reports'].items():
                    assert digest(proof.parent / 'reports' / name) == sha
                checks.append({'id': ref['id'], 'sha256': ref['sha256'],
                               'verdict': data['verdict'], 'tests': data['tests']})
        if result['state'] == 'Verified':
            assert len(states) == 1 and checks[0]['verdict'] == 'FAILED'
            assert checks[-1]['verdict'] == 'PASSED' and checks[-1]['tests'] == 16
        evidence.append({'order': result['run']['order'], 'checks': checks})
    groups = {}
    for scenario in ['ALL', 'eval-coupon', 'eval-stock', 'eval-quantity']:
        for enabled in [True, False]:
            rows = [r for r in results if r['run']['experiencesEnabled'] == enabled
                    and (scenario == 'ALL' or r['run']['scenario'] == scenario)]
            metrics = {'n': len(rows), 'verified': sum(r['state'] == 'Verified' for r in rows),
                       'states': dict(collections.Counter(r['state'] for r in rows))}
            for field in ['requests', 'tools', 'promptTokens', 'completionTokens', 'elapsedMillis', 'humanInterventions']:
                values = [r[field] for r in rows]
                known = all(value is not None for value in values)
                metrics[field] = {'unknown': values.count(None),
                                  'total': sum(values) if known else None,
                                  'mean': statistics.mean(values) if known else None,
                                  'median': statistics.median(values) if known else None}
            groups[f"{scenario}/{'on' if enabled else 'off'}"] = metrics
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()  # Never overwrite a previous publication.
    for name in ['results.json', 'config.json']:
        (output / name).write_bytes((archive / name).read_bytes())
    frozen = [{k: v for k, v in entry.items() if k != 'project'} for entry in load(archive / 'frozen.json')]
    for name, value in [('statistics.json', groups), ('evidence-index.json', evidence), ('frozen-advice.json', frozen)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    summarize(Path(sys.argv[1]), Path(sys.argv[2]))
