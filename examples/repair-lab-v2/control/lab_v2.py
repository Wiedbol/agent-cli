"""Trusted Repair Lab v2 controller; model roots receive current business inputs only."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import tempfile
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from fixtures import CASES, EXCLUSION, STAGES

HOME = Path(__file__).resolve().parent.parent
REPO = HOME.parent.parent
VERSION = 'repair-lab-2'
SOURCE = 'src/main/java/demo/BusinessRules.java'
TEST = 'src/test/java/demo/BusinessRulesTest.java'
MARKER = '.repair-lab-workspace.json'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inputs(case, repaired=False, wrong=False):
    data = CASES[case]
    method = data['reference']
    good, bad = data['mutation']
    if method.count(good) != 1:
        raise ValueError('Defect control must select exactly one location')
    if not repaired:
        method = method.replace(good, bad)
    if wrong:
        good, bad = data['wrongMutation']
        if method.count(good) != 1:
            raise ValueError('Wrong reuse control must select exactly one location')
        method = method.replace(good, bad)
    source = 'package demo;\n\npublic final class BusinessRules {\n    ' + method + '\n}\n'
    tests = 'package demo;\n\nimport org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n\nclass BusinessRulesTest {\n'
    tests += ''.join('    @Test void ' + name + '() { ' + body + ' }\n' for name, body in data['tests']) + '}\n'
    # Freeze generated UTF-8/LF bytes rather than Git's platform-dependent checkout line endings.
    return {'pom.xml': (HOME / 'template/pom.xml').read_text(encoding='utf-8').encode('utf-8'), SOURCE: source.encode('utf-8'),
            TEST: tests.encode('utf-8'), 'GOAL.md': (data['goal'] + '\n').encode('utf-8')}


def metadata(case):
    files = inputs(case)
    return {'id': case, 'kind': CASES[case]['kind'], 'goal': CASES[case]['goal'],
            'initial': {name: digest(value) for name, value in files.items()},
            'acceptance': {name: digest(files[name]) for name in ('pom.xml', TEST)},
            'command': 'mvn test', 'tests': [name for name, _ in CASES[case]['tests']],
            'expectedFailures': CASES[case]['failures'],
            'exclusion': {**EXCLUSION, 'wrongFailures': CASES[case]['wrongFailures']} if CASES[case]['kind'] == 'exclusion' else None,
            'rejectedShortcutFailures': CASES[case]['wrongFailures'] if CASES[case]['kind'] == 'related' and 'wrongMutation' in CASES[case] else None}


def describe():
    catalog = {'version': VERSION, 'stages': STAGES, 'cases': [metadata(case) for case in CASES]}
    if catalog != json.loads((HOME / 'MANIFEST.json').read_text(encoding='utf-8')):
        raise ValueError('Fixture inputs no longer match the frozen version manifest')
    return catalog


def workspace(path):
    root = Path(path)
    if not root.is_absolute() or root != root.resolve() or root == Path(root.anchor) or root.is_relative_to(REPO):
        raise ValueError('Root must be canonical, absolute and outside the developer repository')
    if any((parent / '.git').exists() for parent in (root, *root.parents)):
        raise ValueError('A Git checkout cannot be used as a controlled lab root')
    return root


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json(path, value):
    write(path, json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8'))


def initialize(root, archive, case):
    describe()
    root, archive = workspace(root), workspace(archive)
    if root == archive or root.is_relative_to(archive) or archive.is_relative_to(root):
        raise ValueError('Model and archive roots must be separate')
    if root.exists() or archive.exists():
        raise ValueError('Init requires two nonexistent roots; existing directories are never adopted')
    if root.parent.stat().st_dev != archive.parent.stat().st_dev:
        raise ValueError('Archival rename requires one filesystem')
    current = metadata(case)
    marker = {'version': VERSION, 'projectId': str(uuid.uuid4()), 'root': str(root)}
    root.mkdir()
    archive.mkdir()
    write_json(archive / 'owner.json', {'marker': marker, 'archive': str(archive)})
    write_json(root / MARKER, marker)
    for name, data in inputs(case).items():
        write(root / name, data)
    write_json(archive / 'current.json', current)


def checked_path(path, root):
    if path.resolve() != path or not path.is_relative_to(root):
        raise ValueError('Controlled path contains a link or escape')


def checked(root, archive):
    describe()
    root, archive = workspace(root), workspace(archive)
    if root == archive or root.is_relative_to(archive) or archive.is_relative_to(root):
        raise ValueError('Model and archive roots must be separate')
    for path in (root / MARKER, archive / 'owner.json', archive / 'current.json', archive / 'pending.json'):
        checked_path(path, root if path.is_relative_to(root) else archive)
    if (archive / 'pending.json').exists():
        raise ValueError('Interrupted reset; preserve both roots and inspect pending.json/history manually')
    marker = json.loads((root / MARKER).read_text(encoding='utf-8'))
    if set(marker) != {'version', 'projectId', 'root'} or marker['version'] != VERSION or marker['root'] != str(root):
        raise ValueError('Workspace identity mismatch')
    uuid.UUID(marker['projectId'])
    if json.loads((archive / 'owner.json').read_text(encoding='utf-8')) != {'marker': marker, 'archive': str(archive)}:
        raise ValueError('Archive identity mismatch')
    current = json.loads((archive / 'current.json').read_text(encoding='utf-8'))
    if not isinstance(current, dict) or current.get('id') not in CASES or current != metadata(current['id']):
        raise ValueError('Frozen scenario manifest mismatch')
    allowed = set(inputs(current['id'])) | {MARKER}
    directories = {str(parent).replace('\\', '/') for name in allowed for parent in Path(name).parents if parent != Path('.')}
    for path in root.rglob('*'):
        checked_path(path, root)
        relative = path.relative_to(root)
        if path.name.casefold() == '.git':
            raise ValueError('Nested Git checkout refused')
        if relative.parts[0] in ('.agent', 'target'):
            continue
        if relative.as_posix() not in (directories if path.is_dir() else allowed):
            raise ValueError('Undeclared user input; reset refused: ' + relative.as_posix())
    return marker, current


def reset(root, archive, case):
    current_new = metadata(case)
    marker, current_old = checked(root, archive)
    if Path.cwd().resolve().is_relative_to(root):
        raise ValueError('Run reset from outside the model root; stop tasks/builds using that root first')
    folder = archive / 'history' / str(uuid.uuid4())
    checked_path(folder, archive)
    if root.stat().st_dev != archive.stat().st_dev:
        raise ValueError('Archival rename requires one filesystem')
    folder.mkdir(parents=True)
    write_json(folder / 'manifest.json', current_old)
    write_json(archive / 'pending.json', {'from': current_old['id'], 'to': case, 'backup': str(folder / 'workspace')})
    # Move only the fully checked owned root; preserve every prior evidence byte instead of deleting recursively.
    os.replace(root, folder / 'workspace')
    root.mkdir()
    write_json(root / MARKER, marker)
    for name, data in inputs(case).items():
        write(root / name, data)
    write_json(archive / 'current.json', current_new)
    (archive / 'pending.json').unlink()


def reference(root, archive, wrong=False):
    _, current = checked(root, archive)
    for name, sha in current['acceptance'].items():
        if digest((root / name).read_bytes()) != sha:
            raise ValueError('Frozen acceptance was changed')
    if wrong and 'wrongMutation' not in CASES[current['id']]:
        raise ValueError('No wrong-reuse or shortcut control declared for this case')
    files = inputs(current['id'], repaired=not wrong, wrong=wrong)
    write(root / SOURCE, files[SOURCE])


def inspect(root, archive):
    marker, current = checked(root, archive)
    actual = {name: digest((root / name).read_bytes()) if (root / name).is_file() else None for name in current['initial']}
    return {'projectId': marker['projectId'], 'modelRoot': str(root), 'case': current,
            'currentInputHashes': actual, 'initialMatches': actual == current['initial']}


def verify(output):
    output = Path(output)
    if not output.is_absolute() or output != output.resolve() or output.exists():
        raise ValueError('Verification output must be a new canonical absolute directory')
    mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
    if not mvn:
        raise ValueError('Maven is required; verify never downloads dependencies or calls a model')
    catalog = describe()
    output.mkdir()
    summary = {'version': VERSION, 'kind': 'OFFLINE operator controls, not Agent success', 'status': 'RUNNING', 'results': []}
    write_json(output / 'catalog.json', catalog)
    write_json(output / 'summary.json', summary)
    try:
        with tempfile.TemporaryDirectory(prefix='repair-lab-v2-verify-') as folder:
            base = Path(folder).resolve()
            root, archive = base / 'workspace', base / 'control'
            initialize(root, archive, 'source-delivery')
            identity = (root / MARKER).read_bytes()
            for index, case in enumerate(CASES):
                if index:
                    reset(root, archive, case)
                if not inspect(root, archive)['initialMatches'] or (root / MARKER).read_bytes() != identity:
                    raise ValueError('Input or project identity changed before verification')
                phases = ['initial', 'wrong', 'reference'] if 'wrongMutation' in CASES[case] else ['initial', 'reference']
                for phase in phases:
                    if phase != 'initial':
                        reference(root, archive, wrong=phase == 'wrong')
                    evidence = output / case / phase
                    evidence.mkdir(parents=True)
                    for name in inputs(case):
                        write(evidence / 'inputs' / name, (root / name).read_bytes())
                    started = time.time_ns()
                    result = subprocess.run([mvn, '-o', '-B', 'clean', 'test'], cwd=root, capture_output=True, timeout=120)
                    write(evidence / 'maven.log', result.stdout + result.stderr)
                    report_path = root / 'target/surefire-reports/TEST-demo.BusinessRulesTest.xml'
                    if not report_path.is_file() or report_path.stat().st_mtime_ns < started:
                        raise ValueError(case + ': no fresh test report; infrastructure failure is not a fixture failure')
                    report_bytes = report_path.read_bytes()
                    write(evidence / 'report.xml', report_bytes)
                    report = ET.fromstring(report_bytes)
                    tests = report.findall('testcase')
                    failures = sorted(test.get('name') for test in tests if test.find('failure') is not None)
                    expected = [] if phase == 'reference' else CASES[case]['wrongFailures' if phase == 'wrong' else 'failures']
                    names = sorted(name for name, _ in CASES[case]['tests'])
                    if (sorted(test.get('name') for test in tests) != names
                            or any(test.get('classname') != 'demo.BusinessRulesTest' for test in tests)
                            or int(report.get('tests')) != len(names) or int(report.get('errors')) != 0
                            or int(report.get('skipped')) != 0 or int(report.get('failures')) != len(failures)
                            or failures != sorted(expected) or result.returncode != (0 if phase == 'reference' else 1)):
                        raise ValueError(case + ': unexpected test outcome in ' + phase + ': ' + str(failures))
                    if metadata(case)['acceptance'] != {name: digest((root / name).read_bytes()) for name in ('pom.xml', TEST)}:
                        raise ValueError('Frozen acceptance was modified')
                    summary['results'].append({'case': case, 'phase': phase, 'tests': len(names), 'failures': failures,
                                               'reportSha256': digest(report_bytes), 'exitCode': result.returncode})
                    write_json(output / 'summary.json', summary)
                    print(case + ' ' + phase + ': checked', flush=True)
        summary['status'] = 'PASSED'
    except Exception as failure:
        summary['status'] = 'FAILED'
        summary['error'] = str(failure)
        raise
    finally:
        write_json(output / 'summary.json', summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'reset', 'reference', 'wrong-reuse', 'describe', 'inspect', 'verify'])
    parser.add_argument('--workspace')
    parser.add_argument('--archive')
    parser.add_argument('--scenario', choices=CASES)
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.scenario and args.action not in ('init', 'reset'):
        parser.error('--scenario is only valid with init/reset; other actions use the recorded current case')
    if args.output and args.action != 'verify':
        parser.error('--output is only valid with verify')
    if args.action in ('describe', 'verify') and (args.workspace or args.archive):
        parser.error('describe/verify do not use an existing model or archive root')
    try:
        if args.action == 'describe':
            value = describe()
        elif args.action == 'verify':
            if not args.output:
                parser.error('verify requires a new --output directory')
            value = verify(args.output)
        else:
            if not args.workspace or not args.archive:
                parser.error('workspace and archive are both required')
            root, archive = workspace(args.workspace), workspace(args.archive)
            if args.action in ('init', 'reset'):
                if not args.scenario:
                    parser.error('init/reset require an explicit scenario')
                {'init': initialize, 'reset': reset}[args.action](root, archive, args.scenario)
            elif args.action != 'inspect':
                reference(root, archive, wrong=args.action == 'wrong-reuse')
            value = inspect(root, archive)
        print(json.dumps(value, ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, ET.ParseError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, str(failure) + '\n')


if __name__ == '__main__':
    main()
