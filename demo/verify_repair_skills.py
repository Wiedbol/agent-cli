"""SIMULATED operator check: actual JAR, localhost HTTP and frozen Maven acceptance."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading

REPO = Path(__file__).resolve().parent.parent
FORMAL_RUNS = [('formal-bulk', 'v1'), ('formal-bulk', 'v2'), ('formal-remaining', 'v2'),
               ('formal-remaining', 'v1'), ('formal-grammar', 'v1'), ('formal-grammar', 'v2')]
sys.path.insert(0, str(REPO / 'examples/repair-lab-v2/control'))
import lab_v2 as lab


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def output_path(value):
    if value is None:
        return None
    path = Path(value)
    if path.exists():
        raise ValueError('output already exists; use a new directory')
    if not path.is_absolute() or path != path.resolve() or path.parent != path.parent.resolve() or not path.parent.is_dir():
        raise ValueError('output must be a new canonical absolute path with an existing parent')
    if path.is_relative_to(REPO) or any((p / '.git').exists() for p in path.parents):
        raise ValueError('output must be outside the developer repository')
    return path


def identifier(label, text):
    match = re.search(re.escape(label) + r': ([0-9a-f-]{36})', text)
    if not match:
        raise AssertionError('missing CLI identifier: ' + label)
    return match.group(1)


def wire_messages(messages):
    result = []
    for message in messages:
        item = {'role': message['role']}
        if message.get('content') is not None:
            item['content'] = message['content']
        if message.get('toolCalls'):
            item['tool_calls'] = [{'id': c['id'], 'type': 'function', 'function': c['function']} for c in message['toolCalls']]
        if message.get('toolCallId') is not None:
            item['tool_call_id'] = message['toolCallId']
        result.append(item)
    return result


def assert_audit(packet, actual):
    assert wire_messages(packet['messages']) == actual['messages'], 'actual HTTP messages differ from final audit'
    assert [{'type': 'function', 'function': t} for t in packet['tools']] == actual.get('tools', []), 'actual HTTP tools differ from final audit'


def assert_delivery_evidence(base):
    """Recheck recorded isolation/costs without any JVM, model call or state mutation."""
    config = json.loads((base / 'evaluation-config.json').read_text(encoding='utf-8'))
    report = json.loads((base / 'evaluation/report.json').read_text(encoding='utf-8'))['value']
    captured = json.loads((base / 'evidence/http-requests.json').read_text(encoding='utf-8'))
    formal = [entry['body'] for entry in captured if entry['phase'] == 'formal']
    assert len(captured) == 44 and len(formal) == len(report['attempts']) == 18
    assert report['kind'] == 'SIMULATED' and len(report['cells']) == 6
    source = Path(config['source'])
    v1 = json.loads((source / f'.agent/skills/versions/{config["v1"]}.json').read_text(encoding='utf-8'))['value']
    v2 = json.loads((source / f'.agent/skills/versions/{config["v2"]}.json').read_text(encoding='utf-8'))['value']
    assert v1['skillId'] == v2['skillId']
    for index, (case, label) in enumerate(FORMAL_RUNS):
        cell = report['cells'][index]
        assert (cell['run']['scenario'], cell['run']['version'], cell['run']['repetition']) == (case, label, 1)
        assert cell['state'] == 'Verified' and cell['requests'] == 3 and cell['loads'] == 1 and cell['repairRounds'] == 1
        assert cell['unknownRequests'] == 1 and cell['totalTokens'] is None
        marker = 'OFFLINE_V1_GUIDE' if label == 'v1' else 'OFFLINE_V2_GUIDE'
        forbidden_marker = 'OFFLINE_V2_GUIDE' if label == 'v1' else 'OFFLINE_V1_GUIDE'
        forbidden_version = config['v2' if label == 'v1' else 'v1']
        wire = [json.dumps(body, ensure_ascii=False) for body in formal[index * 3:index * 3 + 3]]
        assert config[label] in wire[0] and v1['skillId'] in wire[0]
        assert marker not in wire[0] and marker in wire[1] and marker in wire[2], 'full guide must follow ordinary loading'
        for body in wire:
            assert forbidden_version not in body and forbidden_marker not in body and 'OFFLINE_OTHER_GUIDE' not in body, 'unauthorized knowledge in actual HTTP'
            assert not re.search(r'参考经验\s+[0-9a-f-]{36}', body), 'old experience block in SKILL request'
        if index % 2:
            assert report['cells'][index - 1]['initial'] == cell['initial']
    for packet, actual in zip(report['attempts'], formal):
        assert packet['status'] == 'RESPONDED'
        assert_audit(packet['payload'], actual)
    cost = report['formalCost']
    assert cost['requests'] == cost['knownRequests'] == 18 and cost['unknownRecords'] == 6
    assert cost['knownPromptTokens'] == 126 and cost['knownCompletionTokens'] == 132
    assert cost['promptTokens'] is None and cost['completionTokens'] is None and cost['totalTokens'] is None, 'unknown totals must not become zero'
    assert report['wholeProcessCost']['requests'] is None and report['wholeProcessCost']['totalTokens'] is None
    batches = [json.loads(p.read_text(encoding='utf-8'))['value'] for p in (source / '.agent/skills/batches').glob('*.json')]
    assert sorted((b['limit'], len(b['requests'])) for b in batches) == [(1, 1), (8, 8), (10, 10)]
    assert any(r['state'] == 'NO_REVISION' for b in batches for r in b['requests'])
    return {'httpRequests': 44, 'formalRequests': 18, 'unknownFormalRequests': 6, 'knownFormalInputTokens': 126,
            'knownFormalOutputTokens': 132, 'skillBatches': ['8/8', '10/10', '1/1 NO_REVISION'], 'kind': 'SIMULATED recorded evidence only'}


class LocalFixture:
    def __init__(self):
        self.mode = 'source'
        self.case = 'source-delivery'
        self.target = None
        self.calls = []
        self.all_calls = []
        self.errors = []
        self.guide = {'title': 'BusinessRules 计算边界诊断', 'conditions': '业务计算与边界错误',
                      'exclusions': '解析或语法故障不适用，不能放宽正数量守卫', 'diagnosis': 'OFFLINE_V1_GUIDE：先区分业务计算、解析和语法原因',
                      'repair': '检查参与计算的数量和边界，独立修改业务实现，不能通用替换比较符', 'verification': '保留冻结验收并检查错误应用的反例'}

    def phase(self, mode, case=None, target=None):
        self.mode, self.case, self.target = mode, case, target
        self.calls = []

    def response(self, body):
        step = len(self.calls) + 1
        if self.mode == 'formal':
            self.case, version = FORMAL_RUNS[len(self.calls) // 3]
            self.target = self.formal_targets[version]
            step = len(self.calls) % 3 + 1
        self.calls.append(body)
        self.all_calls.append({'phase': self.mode, 'case': self.case, 'body': body})
        cap = 18 if self.mode == 'formal' else 2 if self.mode == 'source' else 3 if self.mode in ('trial', 'ordinary') else 1
        assert len(self.calls) <= cap, 'unexpected request; not silently retrying'
        message = {'role': 'assistant', 'content': '请独立验收'}
        def tool(name, arguments):
            return {'id': name + '-' + str(step), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(arguments, ensure_ascii=False)}}
        if self.mode == 'extract':
            message['content'] = json.dumps({'conditions': 'BusinessRules 业务计算边界', 'errorFeatures': 'BusinessRulesTest 边界验收失败',
                'recommendation': '先诊断业务数量及计算类型，再独立修复并保持全部验收', 'limitations': '解析或语法故障不能直接复用业务边界修改'}, ensure_ascii=False)
        elif self.mode in ('generate', 'other', 'revision'):
            guide = self.guide.copy()
            if self.mode == 'other':
                guide['diagnosis'] = 'OFFLINE_OTHER_GUIDE'
            message['content'] = json.dumps(guide, ensure_ascii=False)
        elif self.mode == 'no-revision':
            message['content'] = json.dumps({'noRevision': '离线夹具：没有进一步修订依据，不强造新候选'}, ensure_ascii=False)
        elif self.mode == 'source' and step == 1:
            message['content'] = None
            message['tool_calls'] = [tool('write_file', {'path': lab.SOURCE, 'content': lab.inputs(self.case, repaired=True)[lab.SOURCE].decode('utf-8')})]
        elif self.mode in ('trial', 'ordinary', 'formal'):
            if step == 1:
                message['content'] = None
                message['tool_calls'] = [tool('load_skill', {'id': self.target[0], 'version': self.target[1]})]
            elif step == 2:
                message['content'] = None
                message['tool_calls'] = [tool('declare_skill', {'id': self.target[0], 'version': self.target[1],
                    'decision': 'REJECT' if lab.CASES[self.case]['kind'] == 'exclusion' else 'ACCEPT',
                    'reason': '离线夹具：解析/语法原因不能放宽零约束' if lab.CASES[self.case]['kind'] == 'exclusion' else '离线夹具：业务计算边界适用，仍需独立验收'}),
                    tool('write_file', {'path': lab.SOURCE, 'content': lab.inputs(self.case, repaired=True)[lab.SOURCE].decode('utf-8')})]
        usage = {'prompt_tokens': 7, 'completion_tokens': 11}
        if self.mode in ('trial', 'ordinary', 'formal') and step == 1:
            usage.pop('completion_tokens')
        return {'choices': [{'message': message}], 'usage': usage}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            assert self.path == '/chat/completions'
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            payload = self.server.fixture.response(body)
            self.send_response(200)
        except Exception as error:
            self.server.fixture.errors.append(repr(error))
            payload = {'error': {'message': 'offline fixture failed; inspect retained evidence'}}
            self.send_response(500)
        data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True


class Verifier:
    def __init__(self, java, jar, base):
        self.java, self.jar, self.base = java, jar, base
        self.root, self.control, self.evidence = base / 'workspace', base / 'control', base / 'evidence'
        self.evidence.mkdir()
        self.fixture = LocalFixture()
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.fixture = self.fixture
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/chat/completions'
        self.env = os.environ.copy()
        self.env.update(DEEPSEEK_API_KEY='offline-fixture-only', MAVEN_ARGS='-o -B')
        if Path(java).parent.name.lower() == 'bin' and (Path(java).parent / 'javac.exe').exists():
            self.env['JAVA_HOME'] = str(Path(java).parent.parent)
        self.command = [java, '-Dagent.encoding=UTF-8', '-Dfile.encoding=UTF-8', f'-Dagent.deepseek.endpoint={self.endpoint}',
                        '-Dagent.deepseek.model=offline-model', '-jar', str(jar), '--root', str(self.root)]
        self.passed = []
        self.state('prepared')

    def state(self, stage, **details):
        lab.write_json(self.base / 'verification-state.json', {'kind': 'SIMULATED', 'stage': stage, 'root': str(self.root),
                       'control': str(self.control), 'jar': str(self.jar), 'jarSha256': sha(self.jar), **details})

    def invoke(self, args, name, key=False, allow_model=False, expected=0, confirmation='', command=None, timeout=300):
        env = self.env.copy()
        if not key:
            env.pop('DEEPSEEK_API_KEY', None)
        before = len(self.fixture.all_calls)
        completed = subprocess.run((command or self.command) + args, cwd=self.root, env=env,
                    input=confirmation.encode('utf-8'), capture_output=True, timeout=timeout)
        text = completed.stdout.decode('utf-8') + completed.stderr.decode('utf-8')
        (self.evidence / (name + '.txt')).write_text(text, encoding='utf-8')
        assert completed.returncode == expected, f'{name}: exit{completed.returncode}, expected{expected}\n{text[-1800:]}'
        if not allow_model:
            assert before == len(self.fixture.all_calls), name + ': read-only operation unexpectedly called model'
        return text

    def interact(self, args, name, confirmation, key=False, trace=None):
        self.state(name, confirmation=confirmation, trace=str(trace) if trace else None)
        print(f'\nSIMULATED fixture confirmation ({name}): {confirmation}\nReview: {trace or self.evidence}', flush=True)
        env = self.env.copy()
        if not key:
            env.pop('DEEPSEEK_API_KEY', None)
        before = len(self.fixture.all_calls)
        completed = subprocess.run(self.command + args, cwd=self.root, env=env)
        assert completed.returncode == 0, name + ': not confirmed or failed; proof retained'
        if not key:
            assert before == len(self.fixture.all_calls), name + ': approval unexpectedly called model'

    def confirm_experience(self, task, name):
        self.fixture.phase('extract')
        self.invoke(['--extract-experience', task], name + '-pipe-refused', key=True, expected=1, confirmation='save ' + task + '\n')
        assert not self.fixture.calls
        self.interact(['--extract-experience', task], name, 'save ' + task, key=True)
        assert len(self.fixture.calls) == 1
        listing = self.invoke(['--experiences'], name + '-experiences')
        return identifier('经验', listing)

    def trial(self, case, candidate, digest, batch, label):
        lab.reset(self.root, self.control, case)
        self.fixture.phase('trial', case, (candidate, digest))
        self.state(label)
        result = self.invoke(['--trial-skill', candidate, '--skill-batch', batch, '--trial-control', str(self.control),
                '--trial-source', str(self.source), '--trial-catalog', str(lab.HOME / 'MANIFEST.json'), '--trial-max-steps', '3'], label, key=True, allow_model=True)
        assert len(self.fixture.calls) == 3 and '状态: VERIFIED' in result
        identifier_value = identifier('技能试用', result)
        record = json.loads((self.control / 'skill-trials' / identifier_value / 'state.json').read_text(encoding='utf-8'))['value']
        for packet, actual in zip(record['requests'], self.fixture.calls):
            assert_audit(packet, actual)
        first, loaded = (json.dumps(body, ensure_ascii=False) for body in self.fixture.calls[:2])
        marker = 'OFFLINE_V2_GUIDE' if 'v2' in label else 'OFFLINE_V1_GUIDE'
        assert marker not in first and marker in loaded and 'OFFLINE_OTHER_GUIDE' not in loaded
        assert not (self.root / '.agent/skills/candidates').exists()
        if lab.CASES[case]['kind'] == 'exclusion':
            assert (self.root / lab.SOURCE).read_bytes() == lab.inputs(case, repaired=True)[lab.SOURCE]
            assert 'value <= 0' in (self.root / lab.SOURCE).read_text(encoding='utf-8')
        return identifier_value

    def publish(self, candidate, digest, related, counter, label, regressions=None):
        pair = ['--skill-digest', digest, '--related-trial', related, '--counterexample-trial', counter]
        if regressions:
            pair += ['--regression-trials', ','.join(regressions)]
        reviewed = self.invoke(['--skill-review', candidate] + self.library + pair, label + '-review')
        hashes = re.findall(r'证据摘要: ([a-f0-9]{64})', reviewed)
        assert len(hashes) >= 2 and 'shouldRejectZero' in reviewed and 'write_file' in reviewed
        attest = ['--attest-skill-trial', counter, '--trial-control', str(self.control), '--review-note', '离线夹具逐项核验：拒绝错误放宽，实际解析修复保持零守卫，独立冻结验收通过']
        approve = ['--approve-skill', candidate] + self.library + pair
        attest_line, approval_line = f'attest {counter} {digest} {hashes[1]}', f'approve {candidate} {digest}'
        self.invoke(attest, label + '-attest-pipe-refused', expected=1, confirmation=attest_line + '\n')
        self.invoke(approve, label + '-approve-pipe-refused', expected=1, confirmation=approval_line + '\n')
        self.invoke(approve + ['--yes'], label + '-yes-refused', expected=2)
        trace = self.evidence / (label + '-review.txt')
        self.interact(attest, label + '-attest', attest_line, trace=trace)
        self.interact(approve, label + '-approve', approval_line, trace=trace)
        listing = self.invoke(['--skill-versions'] + self.library, label + '-versions')
        records = list((self.source / '.agent/skills/versions').glob('*.json'))
        matches = [json.loads(p.read_text(encoding='utf-8'))['value'] for p in records]
        version = next(v for v in matches if v['candidateId'] == candidate)
        assert version['id'] in listing
        detail = self.invoke(['--skill-version', version['id']] + self.library, label + '-version')
        assert '批准记录' in detail
        return version

    def run(self):
        lab.initialize(self.root, self.control, 'source-delivery')
        help_text = self.invoke(['--help'], 'help')
        assert 'skill: 技能模式，当前不加载技能' not in help_text, 'CLI help still claims skill loading is absent'
        for flag in ('--knowledge-mode', '--trial-skill', '--approve-skill', '--revise-from-feedback', 'SkillEvaluationMain'):
            assert flag in help_text, 'missing help: ' + flag
        self.fixture.phase('source', 'source-delivery')
        self.state('source-task')
        source = self.invoke(['--task', lab.CASES['source-delivery']['goal'], '--knowledge-mode', 'none', '--yes', '--max-steps', '2'], 'source-task', key=True, allow_model=True)
        task = identifier('任务', source)
        assert len(self.fixture.calls) == 2
        status = self.invoke(['--task-status', task], 'source-status')
        assert 'baseline: FAILED' in status and 'final: PASSED' in status
        experience = self.confirm_experience(task, 'source-save')
        self.invoke(['--skill-feedbacks'], 'source-feedback')
        batch = identifier('技能批次', self.invoke(['--start-skill-batch', '--request-limit', '8'], 'v1-batch'))
        self.fixture.phase('generate')
        generated = self.invoke(['--generate-skill', experience, '--skill-batch', batch], 'v1-generate', key=True, allow_model=True)
        candidate = identifier('技能候选', generated)
        digest = re.search(r'内容摘要: ([a-f0-9]{64})', generated).group(1)
        assert '启用: false' in generated and len(self.fixture.calls) == 1
        self.fixture.phase('other')
        other_text = self.invoke(['--generate-skill', experience, '--skill-batch', batch], 'other-generate', key=True, allow_model=True)
        other = identifier('技能候选', other_text)
        other_digest = re.search(r'内容摘要: ([a-f0-9]{64})', other_text).group(1)
        self.invoke(['--skill-candidates'], 'candidates')
        self.invoke(['--skill-candidate', candidate], 'v1-candidate')
        lab.reset(self.root, self.control, 'dev-points')
        self.source = next((self.control / 'history').glob('*/workspace'))
        self.library = ['--trial-control', str(self.control), '--trial-source', str(self.source)]
        related = self.trial('dev-points', candidate, digest, batch, 'v1-related')
        counter = self.trial('dev-whitespace', candidate, digest, batch, 'v1-counter')
        self.invoke(['--skill-trials', '--trial-control', str(self.control)], 'v1-trials')
        self.invoke(['--skill-trial', counter, '--trial-control', str(self.control)], 'v1-counter-detail')
        v1 = self.publish(candidate, digest, related, counter, 'v1')
        v1_bytes = (self.source / f'.agent/skills/versions/{v1["id"]}.json').read_bytes()
        reject = ['--reject-skill', other, '--skill-digest', other_digest, '--review-note', '离线夹具拒绝未选候选'] + self.library
        self.interact(reject, 'other-reject', f'reject {other} {other_digest}', trace=self.evidence / 'other-generate.txt')
        self.invoke(['--skill-review', other, '--skill-digest', other_digest] + self.library, 'other-rejected')
        self.invoke(['--skill-batch', batch] + self.library, 'v1-batch-final')
        self.passed.append('actual source/confirmation/generation/query and positive/counterexample Maven trials; genuine terminal attestation/approval/rejection, pipes and --yes refused')

        lab.reset(self.root, self.control, 'feedback-product')
        self.fixture.phase('ordinary', 'feedback-product', (v1['skillId'], v1['id']))
        self.state('ordinary-use')
        used = self.invoke(['--task', lab.CASES['feedback-product']['goal'], '--knowledge-mode', 'skill', '--skill-control', str(self.control),
                '--skill-source', str(self.source), '--yes', '--max-steps', '3'], 'ordinary-use', key=True, allow_model=True)
        used_task = identifier('任务', used)
        assert len(self.fixture.calls) == 3 and '状态: Verified' in used
        record = json.loads((self.root / f'.agent/tasks/{used_task}/state.json').read_text(encoding='utf-8'))
        for decision, actual in zip(record['progress']['decisions'], self.fixture.calls):
            assert_audit(decision['skillRequest']['payload'], actual)
        self.confirm_experience(used_task, 'feedback-save')
        self.invoke(['--skill-feedbacks'], 'feedback-list')
        files = list((self.root / '.agent/skills/feedback').glob('*.json'))
        entries = [json.loads(p.read_text(encoding='utf-8'))['value'] for p in files]
        selected = [e['id'] for e in entries if e['taskId'] == used_task]
        assert len(selected) == 2 and {e['kind'] for e in entries} == {'SOURCE', 'USE'}
        before = {p.name: p.read_bytes() for p in files}
        self.invoke(['--register-skill-feedback'], 'feedback-catchup')
        assert before == {p.name: p.read_bytes() for p in files}
        lab.reset(self.root, self.control, 'feedback-product')
        feedback_root = next(p.parents[3] for p in (self.control / 'history').glob('*/workspace/.agent/tasks/' + used_task + '/state.json'))
        revision_batch = identifier('技能批次', self.invoke(['--start-skill-batch', '--request-limit', '10'] + self.library, 'v2-batch'))
        feedback_args = ['--revise-from-feedback', ','.join(selected), '--revise-skill', v1['id'], '--revision-reason', '离线开发反馈：补充中间乘积和类型提升检查',
                         '--feedback-source', str(feedback_root), '--feedback-catalog', str(lab.HOME / 'MANIFEST.json')] + self.library
        self.fixture.guide['diagnosis'] = 'OFFLINE_V2_GUIDE：保留原诊断并检查派生量和中间乘积'
        self.fixture.guide['repair'] = '在原流程中补充乘法中间值、类型提升时机及派生量核验，保留旧开发边界与反例'
        self.fixture.phase('revision')
        revised = self.invoke(feedback_args + ['--skill-batch', revision_batch], 'v2-revision', key=True, allow_model=True)
        candidate2 = identifier('技能候选', revised)
        digest2 = re.search(r'内容摘要: ([a-f0-9]{64})', revised).group(1)
        wire = json.dumps(self.fixture.calls, ensure_ascii=False)
        assert all(item in wire for item in selected) and 'OFFLINE_V1_GUIDE' in wire and 'OFFLINE_OTHER_GUIDE' not in wire
        assert not any(name in wire for name in lab.STAGES['formal']) and '内容差异' in revised
        related2 = self.trial('feedback-product', candidate2, digest2, revision_batch, 'v2-related')
        counter2 = self.trial('dev-whitespace', candidate2, digest2, revision_batch, 'v2-counter')
        incomplete = self.invoke(['--skill-review', candidate2, '--skill-digest', digest2, '--related-trial', related2, '--counterexample-trial', counter2] + self.library, 'v2-missing-regression')
        assert '旧开发回归' in incomplete
        regression = self.trial('dev-points', candidate2, digest2, revision_batch, 'v2-regression')
        v2 = self.publish(candidate2, digest2, related2, counter2, 'v2', [regression])
        assert v1_bytes == (self.source / f'.agent/skills/versions/{v1["id"]}.json').read_bytes()
        self.invoke(['--skill-batch', revision_batch] + self.library, 'v2-batch-final')
        self.invoke(['--skill-batches'] + self.library, 'batches')
        none_batch = identifier('技能批次', self.invoke(['--start-skill-batch', '--request-limit', '1'] + self.library, 'no-revision-batch'))
        self.fixture.phase('no-revision')
        none = self.invoke(feedback_args + ['--skill-batch', none_batch], 'no-revision', key=True, allow_model=True, expected=1)
        assert 'NO_REVISION' in none and len(self.fixture.calls) == 1
        self.passed.append('ordinary skill task plus confirmed SOURCE/USE feedback, keyless idempotent registration, explicit frozen revision, required earlier regression, immutable v1 and no forced revision')

        inputs = {case: {'goal': lab.CASES[case]['goal'], 'files': {k: v.decode('utf-8') for k, v in lab.inputs(case).items()}} for case in lab.STAGES['formal']}
        lab.write_json(self.base / 'formal-inputs.json', inputs)
        config = {'control': str(self.control), 'source': str(self.source), 'catalog': str(lab.HOME / 'MANIFEST.json'),
                  'inputs': str(self.base / 'formal-inputs.json'), 'archive': str(self.base / 'evaluation'), 'v1': v1['id'], 'v2': v2['id'],
                  'model': 'offline-model', 'endpoint': self.endpoint, 'taskRequests': 3, 'batchRequests': 18, 'repeats': 1, 'simulated': True}
        lab.write_json(self.base / 'evaluation-config.json', config)
        self.fixture.phase('formal')
        self.fixture.formal_targets = {'v1': (v1['skillId'], v1['id']), 'v2': (v2['skillId'], v2['id'])}
        entry = [self.java, '-Dagent.encoding=UTF-8', '-Dfile.encoding=UTF-8', '-cp', str(self.jar), 'com.agent.evaluation.SkillEvaluationMain']
        self.state('six-cell-simulation')
        self.invoke(['--simulate', str(self.root), str(self.base / 'evaluation-config.json')], 'six-cell', command=entry, allow_model=True, timeout=1800)
        report_path = self.base / 'evaluation/report.json'
        report_bytes = report_path.read_bytes()
        self.invoke(['--report', str(self.base / 'evaluation')], 'six-cell-report', command=entry)
        assert report_bytes == report_path.read_bytes() and not (self.root / '.agent').exists()
        assert v1_bytes == (self.source / f'.agent/skills/versions/{v1["id"]}.json').read_bytes()
        assert not self.fixture.errors
        lab.write_json(self.evidence / 'http-requests.json', self.fixture.all_calls)
        assert_delivery_evidence(self.base)
        self.passed.append('six actual JAR/localHTTP/real Maven SIMULATED cells, identical paired inputs,18 final wire audits, unknown usage and keyless byte-stable report')
        self.passed.append('recorded formal directory/full-guide isolation, other-version/old-experience absence, known126/132 subtotals with unknown totals and8/10/1 batch ledgers rechecked')
        summary = {'kind': 'SIMULATED; reference repair controls, not real model efficacy', 'jar': str(self.jar), 'jarSha256': sha(self.jar),
                   'v1': v1['id'], 'v2': v2['id'], 'httpRequests': len(self.fixture.all_calls), 'passed': self.passed,
                   'report': str(report_path), 'proofRoot': str(self.base)}
        assert len(self.fixture.all_calls) == 44, 'unexpected request accounting'
        lab.write_json(self.base / 'summary.json', summary)
        self.state('finished', summary=str(self.base / 'summary.json'))
        print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)

    def close(self):
        lab.write_json(self.evidence / 'http-requests.json', self.fixture.all_calls)
        lab.write_json(self.evidence / 'fixture-errors.json', self.fixture.errors)
        self.server.shutdown()
        self.server.server_close()


def main(argv=None):
    parser = argparse.ArgumentParser(description='SIMULATED repair-skill delivery check: localhost only, real JAR/Maven and explicit terminal confirmations; no real API Key needed.')
    parser.add_argument('--java', default='java', help='Java17+ executable, preferably an absolute JDK path')
    parser.add_argument('--jar', default=str(REPO / 'target/agent-cli-0.1.0.jar'))
    parser.add_argument('--output', help='new canonical absolute proof directory outside every Git checkout; default: retained temporary directory')
    args = parser.parse_args(argv)
    verifier = None
    try:
        destination = output_path(args.output)
        if not sys.stdin.isatty() or not sys.stdout.isatty():
            raise ValueError('run in a real interactive terminal; pipes cannot approve/save/attest')
        java = shutil.which(args.java)
        jar = Path(args.jar).resolve()
        if not java or not jar.is_file():
            raise ValueError('Java executable and an existing packaged JAR are required')
        lab.describe()
        base = destination or Path(tempfile.mkdtemp(prefix='agent-repair-skills-proof-')).resolve()
        if destination:
            base.mkdir()
        verifier = Verifier(str(Path(java).resolve()), jar, base)
        print('SIMULATED proof retained at ' + str(base), flush=True)
        verifier.run()
        return 0
    except ValueError as invalid:
        if verifier:
            verifier.state('FAILED_OR_INTERRUPTED', reason=type(invalid).__name__ + ': ' + str(invalid))
            print('Proof retained at ' + str(verifier.base), file=sys.stderr)
            print(type(invalid).__name__ + ': ' + str(invalid), file=sys.stderr)
            return 1
        print('Preflight: ' + str(invalid), file=sys.stderr)
        return 2
    except (Exception, KeyboardInterrupt) as failed:
        if verifier:
            verifier.state('FAILED_OR_INTERRUPTED', reason=type(failed).__name__ + ': ' + str(failed))
            print('Proof retained at ' + str(verifier.base), file=sys.stderr)
        print(type(failed).__name__ + ': ' + str(failed), file=sys.stderr)
        return 130 if isinstance(failed, KeyboardInterrupt) else 1
    finally:
        if verifier:
            verifier.close()


if __name__ == '__main__':
    raise SystemExit(main())
