"""Trusted local control. Never copy this directory into an Agent workspace."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

HOME = Path(__file__).resolve().parent.parent
REPO = HOME.parent.parent
VERSION = 'repair-lab-1'
SOURCE = 'src/main/java/demo/ShopRules.java'
TEST = 'src/test/java/demo/ShopRulesTest.java'
CASES = {
    'source-shipping': ('经验来源：满10000分免运费，修复恰好达到门槛的错误。', 'cents >= 10000', 'cents > 10000', 'shouldShipFreeAtThreshold'),
    'eval-coupon': ('评测：满20000分允许优惠券，修复门槛判断。', 'cents >= 20000', 'cents > 20000', 'shouldAllowCouponAtThreshold'),
    'eval-stock': ('评测：库存不超过5件应预警，修复临界库存判断。', 'count <= 5', 'count < 5', 'shouldWarnAtStockThreshold'),
    'eval-quantity': ('评测：数量必须为正整数，修复负数数量被接纳的问题。', 'value <= 0', 'value == 0', 'shouldRejectNegativeQuantity'),
}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def inputs(case, repaired=False):
    source = (HOME / 'control/ShopRules.java').read_bytes()
    if not repaired:
        good, bad = CASES[case][1:3]
        assert source.count(good.encode()) == 1
        source = source.replace(good.encode(), bad.encode())
    return {'pom.xml': (HOME / 'template/pom.xml').read_bytes(),
            SOURCE: source, TEST: (HOME / 'template/ShopRulesTest.java').read_bytes()}

def metadata(case):
    files = inputs(case)
    return {'version': VERSION, 'acceptanceVersion': 'shop-rules-tests-1', 'scenario': case, 'goal': CASES[case][0],
            'initial': {name: digest(value) for name, value in files.items()},
            'acceptance': {name: digest(files[name]) for name in ('pom.xml', TEST)}}

def workspace(path):
    root = Path(path).absolute()
    actual = root.resolve()
    if root != actual or actual == Path(actual.anchor) or actual.is_relative_to(REPO):
        raise ValueError('Workspace must be an absolute canonical path outside the developer repository')
    return actual

def marker(root):
    return root / '.repair-lab-workspace.json'

def checked(root):
    workspace(str(root))
    value = json.loads(marker(root).read_text(encoding='utf-8'))
    if value != {'root': str(root), 'version': VERSION}:
        raise ValueError('Workspace marker/root/version mismatch')
    for name in ('pom.xml', SOURCE, TEST, '.repair-lab-manifest.json', '.repair-lab-workspace.json'):
        target = root / name
        if target.resolve() != target or not target.resolve().is_relative_to(root):
            raise ValueError('Declared input contains a symbolic link or path escape')

def write(path, data):
    # Replace the directory entry, never truncate an existing hard-linked user file.
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)

def reset(root, case):
    checked(root)
    for name, data in inputs(case).items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        write(target, data)
    write(root / '.repair-lab-manifest.json', json.dumps(metadata(case), ensure_ascii=False, indent=2).encode('utf-8'))

def initialize(root, case):
    workspace(str(root))
    if root.exists():
        raise ValueError('Init requires a nonexistent workspace; existing directories are never adopted')
    root.mkdir()
    marker(root).write_text(json.dumps({'root': str(root), 'version': VERSION}), encoding='utf-8')
    reset(root, case)

def reference(root, case):
    checked(root)
    if json.loads((root / '.repair-lab-manifest.json').read_text(encoding='utf-8')) != metadata(case):
        raise ValueError('Scenario/acceptance manifest mismatch')
    for name, sha in metadata(case)['acceptance'].items():
        if digest((root / name).read_bytes()) != sha:
            raise ValueError('Acceptance was changed')
    write(root / SOURCE, inputs(case, True)[SOURCE])

def verify():
    mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
    if not mvn:
        raise ValueError('Maven is required for explicit local verification')
    results = []
    with tempfile.TemporaryDirectory(prefix='repair-lab-verify-') as folder:
        for case in CASES:
            root = workspace(str(Path(folder).resolve() / case))
            initialize(root, case)
            identity = str(root)
            for repaired in (False, True):
                if repaired:
                    reference(root, case)
                # Only explicit verify invokes Maven; normal unit tests never recurse into it.
                result = subprocess.run([mvn, '-o', '-B', 'clean', 'test'], cwd=root, capture_output=True, timeout=120)
                report = ET.parse(root / 'target/surefire-reports/TEST-demo.ShopRulesTest.xml').getroot()
                failures = [test.get('name') for test in report.findall('testcase') if test.find('failure') is not None or test.find('error') is not None]
                assert int(report.get('tests')) == 16 and int(report.get('skipped')) == 0
                assert failures == ([] if repaired else [CASES[case][3]]), (case, failures)
                assert (result.returncode == 0) == repaired
                assert metadata(case)['acceptance'] == {name: digest((root / name).read_bytes()) for name in ('pom.xml', TEST)}
                results.append({'scenario': case, 'repaired': repaired, 'tests': 16, 'failures': failures})
            evidence = root / '.agent/source-evidence.txt'
            evidence.parent.mkdir()
            evidence.write_text('preserve', encoding='utf-8')
            reset(root, case)
            assert str(root) == identity and evidence.read_text() == 'preserve'
    print(json.dumps({'version': VERSION, 'results': results}, indent=2))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'reset', 'reference', 'describe', 'verify'])
    parser.add_argument('--workspace')
    parser.add_argument('--scenario', choices=CASES, default='source-shipping')
    args = parser.parse_args()
    if args.action == 'verify':
        verify()
    elif args.action == 'describe':
        print(json.dumps([metadata(case) for case in CASES], ensure_ascii=False, indent=2))
    else:
        if not args.workspace or not Path(args.workspace).is_absolute():
            parser.error('--workspace must be absolute')
        root = workspace(args.workspace)
        {'init': initialize, 'reset': reset, 'reference': reference}[args.action](root, args.scenario)
        print(json.dumps({'root': str(root), **metadata(args.scenario)}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
