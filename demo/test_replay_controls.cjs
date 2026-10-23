// Offline JavaScript contract check, not a real-browser rendering assertion.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(process.argv[2], 'utf8');
const source = html.match(/<script>([\s\S]*)<\/script>/)[1];
const elements = Object.fromEntries(['seek','out','clock','play','next','speed']
    .map(name => [name, {value: name === 'speed' ? '2' : '0', checked: false}]));
let timer;
vm.runInNewContext(source, {
    document: {getElementById: id => elements[id]},
    performance: {now: () => 0},
    setInterval: callback => { timer = callback; }
});
assert.match(elements.out.textContent, /REAL recording start/);
const initial = Number(elements.seek.value);
elements.next.onclick();
assert.ok(Number(elements.seek.value) > initial);
elements.seek.value = elements.seek.max;
elements.seek.oninput();
assert.match(elements.out.textContent, /Recording end/);
assert.match(elements.out.textContent, /7c3688b1-c815-4de0-b7ae-1ca5282f89df/);
assert.match(elements.out.textContent, /return cents > 10000 \? 0 : 800/);
assert.match(elements.out.textContent, /return cents >= 10000 \? 0 : 800/);
assert.match(elements.out.textContent, /文件版本不符合任务记录/);
assert.match(elements.out.textContent, /已明确批准基于当前文件重新规划/);
elements.play.onclick();
timer();
console.log('OFFLINE replay JS: navigation, seek, actual Diff/conflict/replan/source and end marker PASS');
