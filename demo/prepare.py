"""Prepare a dedicated demo fixture before acceptance is agreed; no model calls."""
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'examples/repair-lab/control'))
import lab


def prepare(root):
    lab.initialize(root, 'source-shipping')
    test = root / lab.TEST
    message = ('满10000分免运费：边界测试在金额恰为10000分时断言运费为0，但实际返回800。'
               'ShopRules.shipping cents shouldShipFreeAtThreshold boundary')
    text = test.read_text(encoding='utf-8').replace('assertEquals(0, ShopRules.shipping(10000));',
                'assertEquals(0, ShopRules.shipping(10000), "' + message + '");')
    test.write_text(text, encoding='utf-8')
    metadata = lab.metadata('source-shipping')
    metadata['version'] = 'repair-lab-demo-1'
    metadata['initial'][lab.TEST] = hashlib.sha256(test.read_bytes()).hexdigest()
    metadata['acceptance'][lab.TEST] = metadata['initial'][lab.TEST]
    (root / '.repair-lab-manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    prepare(lab.workspace(sys.argv[1]))
