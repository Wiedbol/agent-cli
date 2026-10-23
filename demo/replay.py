"""Convert a genuine PowerShell transcript to a local event-timed HTML backup."""
from datetime import datetime
import json
from pathlib import Path
import re
import sys

MARK = re.compile(r'^@@EVENT (\S+) (.*)$')
SECRET = re.compile(r'(?i)(password|passwd|secret|token|api[_-]?key|private_key)\s*[:=]\s*\S+|\bsk-[A-Za-z0-9_-]{20,}')


def parse(text, replacements):
    events = []
    origin = None
    for line in text.splitlines():
        match = MARK.match(line)
        if match:
            instant = datetime.fromisoformat(match[1])
            origin = origin or instant
            seconds = (instant - origin).total_seconds()
            if events and seconds < events[-1]['seconds']:
                raise ValueError('Recording clock moved backwards')
            events.append({'at': match[1], 'seconds': seconds, 'text': match[2] + '\n'})
        elif events:
            if line.startswith('****************') or line.startswith('PowerShell transcript end') or line.startswith('End time:'):
                continue
            events[-1]['text'] += line + '\n'
    if not events:
        raise ValueError('No genuine timestamp markers')
    for event in events:
        for old, new in sorted(replacements.items(), key=lambda item: -len(item[0])):
            event['text'] = event['text'].replace(old, new).replace(old.replace('\\', '/'), new)
        event['text'] = '\n'.join('[已过滤：疑似敏感内容]' if SECRET.search(line) else line
                                  for line in event['text'].split('\n'))
    return events


def html(events):
    data = json.dumps(events, ensure_ascii=False).replace('<', '\\u003c')
    return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>真实终端录制备份</title>
<style>body{background:#111827;color:#e5e7eb;margin:24px;font:16px system-ui}button,select{padding:8px;margin:4px}pre{white-space:pre-wrap;background:#030712;padding:18px;line-height:1.5}input{width:60%}</style>
<h1>真实终端录制备份 · 非当前现场</h1>
<p>真实终端工具输出分块回放；时间为工具接收时刻，非逐字符桌面视频。路径已匿名化、敏感行已过滤；工具原有截断与命令行编辑伪影保留。另一次只读状态核验单独标注。审批等待空档保留，可用“下一事件”跳过。</p>
<button id="play">播放/暂停</button><button id="next">下一事件</button>
<select id="speed"><option>1</option><option selected>2</option><option>4</option><option>8</option></select>倍速（默认2倍；时间轴仍为原始时间）
<input id="seek" type="range" min="0" value="0" step="0.1"><span id="clock"></span><pre id="out"></pre>
<script>const events=DATA;let now=events[0].seconds,playing=false;const end=events.at(-1).seconds;
const seek=document.getElementById('seek'),out=document.getElementById('out'),clock=document.getElementById('clock');seek.max=end;
function draw(){out.textContent=events.filter(e=>e.seconds<=now).map(e=>'['+e.at+'] '+e.text).join('\\n');clock.textContent=now.toFixed(1)+' / '+end.toFixed(1)+' 秒';seek.value=now;}
document.getElementById('play').onclick=()=>playing=!playing;
document.getElementById('next').onclick=()=>{now=(events.find(e=>e.seconds>now)||events.at(-1)).seconds;draw();};
seek.oninput=()=>{now=Number(seek.value);draw();};let last=performance.now();setInterval(()=>{const t=performance.now();if(playing){now=Math.min(end,now+(t-last)/1000*Number(document.getElementById('speed').value));draw();if(now===end)playing=false;}last=t;},100);draw();</script></html>'''.replace('DATA', data)


if __name__ == '__main__':
    if sys.argv[1] == '--events':
        events = json.loads(Path(sys.argv[2]).read_text(encoding='utf-8'))
        destination = Path(sys.argv[3])
    else:
        transcript, mapping, destination = map(Path, sys.argv[1:])
        events = parse(transcript.read_text(encoding='utf-8-sig'), json.loads(mapping.read_text(encoding='utf-8')))
    destination.write_text(html(events), encoding='utf-8')
