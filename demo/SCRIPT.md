# 九分钟课程演示脚本

两项主线：可靠的任务继续执行；可追溯、经人确认的失败经验。自动修复是串起两者的场景。正式现场依次执行下列步骤，使用独立工作区，不在agent-cli仓库中运行修复。

| 时间 | 演示与讲述 | 核验证据 |
|---|---|---|
| 00:00–00:45 | 用户目标与两项主线；说明现场/备份/离线测试标签 | CLI版本、环境 |
| 00:45–01:30 | `mvn test`展示满额免运费临界点的真实失败 | 16项中1失败，不改测试追求通过 |
| 01:30–02:30 | 启动有界任务，展示真实工具调用与暂停 | 请求额度与已完成工具ID |
| 02:30–03:15 | 显式恢复；预算耗尽不自动扩大，展示累计额度与已完成操作 | 不重复执行已完成工具；未知副作用不自动重放 |
| 03:15–04:15 | 人工添加注释后恢复，Agent因版本变化暂停；审核重新规划 | 人工注释、版本差异、明确批准 |
| 04:15–05:15 | 恢复修复，查看精确替换与Diff及独立最终验收 | before/after哈希、16项通过、Verified |
| 05:15–06:30 | 从真实失败与对应修复提炼建议，显示来源并人工确认 | 来源任务、失败/成功证据、修复操作、适用限制 |
| 06:30–07:45 | 受控重置同一缺陷后启动新任务，观察经验选择与新验收 | 不能把经验开关开启当作命中；若无命中就展示限制 |
| 07:45–09:00 | 展示十八次真实对照、局限与工程边界 | 两组9/9；重放零命中，不宣称复用收益 |

## 可复现命令

在已打包的仓库根打开PowerShell，使用真实JDK路径替换 `$java`；下列占位ID从实际输出获取，不能复制旧任务ID到新工作根。

```powershell
$java = 'java'
$jar = (Resolve-Path target/agent-cli-0.1.0.jar).Path
$root = 'E:/agent-course-demo' # 父目录存在，工作根尚不存在
python demo/prepare.py $root
Push-Location $root
mvn test
Pop-Location
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --task '修复满10000分免运费，保留测试与人工注释' --accept-command 'mvn test' --max-steps 2 --yes
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --task-status <任务ID>
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --resume-task <任务ID> --yes
# 明确决定提高累计总预算；不会清零已消耗请求
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --task-budget <任务ID> --max-steps 8
Add-Content -Encoding utf8 ($root + '/src/main/java/demo/ShopRules.java') '// human note: preserve this comment'
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --resume-task <任务ID> --yes
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --replan-task <任务ID>
# 审核后在真实交互终端输入 replan <任务ID>
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --resume-task <任务ID> --yes
# 本录制中，该任务包含人工改动，来源核验会拒绝将它直接归为Agent修复；不消耗提炼请求。
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --extract-experience <任务ID>
function Reset-ShippingDefect {
    $path = Join-Path $root 'src/main/java/demo/ShopRules.java'
    $text = [IO.File]::ReadAllText($path)
    $good = 'return cents >= 10000 ? 0 : 800;'
    if ([regex]::Matches($text,[regex]::Escape($good)).Count -ne 1) { throw '重置目标不唯一' }
    [IO.File]::WriteAllText($path,$text.Replace($good,'return cents > 10000 ? 0 : 800;'),[Text.UTF8Encoding]::new($false))
}
Reset-ShippingDefect # 明确告知观众：重新引入原缺陷，不改测试或人的注释
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --task '直接读取ShopRules.java并精确修复免运费边界，保留测试和注释；无需git或tail，由管理任务独立验收' --accept-command 'mvn test' --max-steps 4 --yes
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --extract-experience <独立来源任务ID>
# 审核具体内容，接受才输入 save <独立来源任务ID>，--yes与管道不能代替
Reset-ShippingDefect
$used = ((Get-ChildItem ($root+'/.agent/tasks') -Filter state.json -Recurse | ForEach-Object {
    (Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json).progress.requests
}) | Measure-Object -Sum).Sum + 1 # 本脚本只提炼一次；其token未知也不能记零
$remaining = [Math]::Min(3,12-$used)
if ($remaining -lt 1) { throw '已达录制请求上限；暂停，不自动增加额度' }
& $java '-Dagent.encoding=UTF-8' -jar $jar --root $root --task '引用已确认来源经验，read_file确认当前版本、replace_text修复同一边界；不要git/tail或额外run_command，交管理任务独立验收后总结，保留测试和注释' --accept-command 'mvn test' --max-steps $remaining --yes
```

`prepare.py`在约定验收前为一个断言增加业务描述，帮助讲解中文边界；期望值、规则和16项测试身份不变。这是独立的`repair-lab-demo-1`，不是十八次正式对照使用的冻结模板。开始任务后不再改测试/pom。首次修复可以直接成功，不能人为让模型写错制造故事。

重复演示前明确重置缺陷，保留来源证据；不手工改写检查点、已保存经验或验收记录。后续命令和实际录制索引见本目录README。

## 现场分支与备份

模型慢时先讲检查点与证据，不无限提高额度。超预算、网络错误、未命中、暂停均如实展示；不要临时放宽验收。切换备份时先明确说“下面是标注时间的真实录制回放，不是当前现场”。离线Mock/Fake测试只能证明工程机制，不能冒充DeepSeek修复。小样本与未实际命中不能证明经验普遍有效，测试通过也不代表全面正确。
