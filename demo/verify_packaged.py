"""Offline packaged compatibility checks; local HTTP fixture, never DeepSeek."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading


def verify(java, jar):
    calls = []
    mode = 'chat'

    class Model(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            calls.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            message = {'role': 'assistant', 'content': 'OFFLINE-MOCK 中文'}
            if mode == 'guard' and len(calls) == 1:
                message['tool_calls'] = [{'id': 'blocked-write', 'type': 'function', 'function': {
                    'name': 'write_file', 'arguments': '{"path":".env","content":"blocked"}'}}]
            payload = json.dumps({'choices': [{'message': message}],
                                  'usage': {'prompt_tokens': 1, 'completion_tokens': 1}}).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    results = []
    try:
        with tempfile.TemporaryDirectory(prefix='agent-packaged-') as folder:
            root = Path(folder)
            env = os.environ.copy()
            env['DEEPSEEK_API_KEY'] = 'offline-fixture-only'

            def run(tail, input='', codec='utf-8'):
                command = [java, '-Dagent.encoding=' + codec,
                           f'-Dagent.deepseek.endpoint=http://127.0.0.1:{server.server_port}/chat/completions',
                           '-jar', jar, '--root', str(root), *tail]
                completed = subprocess.run(command, cwd=root, env=env, input=input.encode(codec),
                                           capture_output=True, timeout=30)
                assert completed.returncode == 0, completed.stderr.decode(codec, errors='replace')
                return completed.stdout.decode(codec)

            assert '你好' in run(['--local'], '你好\nexit\n')
            assert '中文' in run(['--local'], '中文\nexit\n', 'gbk')
            results.extend(['local UTF-8', 'local GBK'])
            assert 'OFFLINE-MOCK' in run(['--new'], 'first-turn\nexit\n')
            assert 'OFFLINE-MOCK' in run(['--resume', 'latest'], 'second-turn\nexit\n')
            assert 'first-turn' in str(calls[-1]['messages'])
            exported = run(['--export', 'latest'])
            assert 'first-turn' in exported and 'second-turn' in exported
            results.extend(['chat persistence/resume', 'explicit export'])
            mode = 'guard'
            calls.clear()
            run(['--new', '--yes'], 'attempt forbidden write\nexit\n')
            assert not (root / '.env').exists()
            assert any('blocked-write' == m.get('tool_call_id') for m in calls[-1]['messages'])
            results.append('guard blocked .env despite --yes')
            completed = subprocess.run([java, '-cp', jar, str(Path(__file__).with_name('PackagedProcessCheck.java').resolve()),
                                        str(root)], capture_output=True, timeout=30)
            assert completed.returncode == 0, completed.stderr.decode(errors='replace')
            results.append('timeout child termination/output cleanup')
    finally:
        server.shutdown()
        server.server_close()
    return {'kind': 'OFFLINE packaged checks; local HTTP fixture, no real model', 'passed': results}


if __name__ == '__main__':
    print(json.dumps(verify(sys.argv[1], str(Path(sys.argv[2]).resolve())), ensure_ascii=False, indent=2))
