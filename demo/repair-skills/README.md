# 修复技能的离线交付与操作指引

本页对应修复技能闭环的工程交付。`SIMULATED` 表示本机 HTTP 替身返回确定的操作及参考修复，JAR 仍真实执行守卫、来源检查、独立 Maven 验收、交互审核和请求审计。它不证明模型迁移收益，也不表示六次真实模型实验已完成。旧课程演示、十八次经验对照和历史结果保持原样。

## 一条可复现的离线流程

需要 Java 17+、Python 3.10+、Maven，以及已缓存的 [Lab v2](../../examples/repair-lab-v2/README.md) 依赖。脚本只依赖 Python 标准库，不安装插件或读取真实 Key。Maven 通过 `MAVEN_ARGS=-o -B` 离线执行。

在仓库根的**真实交互终端**运行，`--output` 必须是尚不存在、父目录已存在、位于任何 Git 检出之外的规范绝对目录：

```powershell
$java = 'E:/java/bin/java.exe'
$agentJar = (Resolve-Path 'target/agent-cli-0.1.0.jar').Path
python demo/verify_repair_skills.py --java $java --jar $agentJar --output E:/repair-skills-proof-new
```

也可省略 `--output`，脚本创建并保留独立临时目录，开始时打印路径。已有目录、路径别名和仓库内目录会拒绝。脚本不编译项目、不运行完整回归、不自动提交或推送。仅当 JAR 不存在或产品代码变化需要交付时，先按 [仓库说明](../../README.md) 执行相关测试，再 `mvn -B '-DskipTests' package`。

脚本覆盖以下全过程，实际执行出错、拒绝确认或断言失败都会非零退出并保留现场：

1. 独立来源任务的初始失败、参考修复及独立通过；本机 HTTP 生成经验建议，操作者输入 `save <任务ID>` 才保存。
2. 有界生成候选、查询来源/候选/账本；相关新缺陷与不适用反例分别试用。操作者检查实际轨迹，输入 `attest <试用ID> <候选摘要> <证据摘要>`，再另行 `approve <候选ID> <候选摘要>` 发布 v1；另一个候选明确拒绝。
3. 普通 SKILL 任务发现并按需加载 v1，记录声明、实际修复及验收；单独确认新经验后，形成 SOURCE 和 USE 反馈。查询与显式补齐不调用模型、不重复改写事实。
4. 只选择这两项开发反馈修订 v1；相关开发、反例和必要旧开发回归均通过后，再逐项核验并批准 v2。缺少旧回归会阻止审核；无修订输出消耗预留但不强造候选。v1 原记录保持不变。
5. 冻结 v1/v2 和三类正式输入，经同一目录、加载、审计路径执行六格 SIMULATED 对照，核对配对输入、实际 HTTP 消息/工具与最终审计、未知用量及只读报告。

全流程有七次终端确认：两次经验保存、两次反例核验、两次发布、一次拒绝。每次显示要输入的精确行及复核文件路径；操作者应先阅读，而非只复制确认行。管道、EOF、`--yes` 均不能替代这些确认。脚本没有自动批准选项；普通业务参考写入才使用 `--yes`。

替身预定总计 44 次 HTTP 请求，分为：来源修复/提炼 3、v1 批次 8、后续使用/提炼 4、v2 批次 10、无修订批次 1、六格 18。数量包含加载和声明开销；异常或额外请求不会被当成模拟成功。所有请求仅发往脚本绑定的 `127.0.0.1`，子进程 Key 为虚拟值。参考方法没有作为未记录知识注入请求，而是替身明确提出的 `write_file` 操作；真实模型能否自行提出这些修复尚未验证。

## 证据与只读复核

输出根保留以下内容，不自动删除，也不支持整批自动续跑：

| 路径 | 内容 |
|---|---|
| `verification-state.json` | 当前阶段、精确确认行、JAR SHA-256、失败或完成状态 |
| `summary.json` | 全部成功后才生成的 SIMULATED 汇总、版本及请求数 |
| `evidence/*.txt`、`evidence/http-requests.json` | 实际 CLI 输出与本机 HTTP 请求，完整审计不会反复打印到摘要 |
| `control/history/*/workspace` | 原来源、使用任务和反馈现场；保持原项目身份，不复制回模型工作根 |
| `control/skill-trials/*` | 试用、冻结反例判据、实际修改及独立验收原件 |
| `formal-inputs.json`、`evaluation-config.json` | 正式业务输入和固定版本、模型、顺序及额度配置 |
| `evaluation/report.json`、`evaluation/run-N` | 六格结果、每次预留/审计、原始现场及哈希 |

查询无需 Key，不运行模型或 Maven：

```powershell
$work = 'E:/repair-skills-proof-new/workspace'
$control = 'E:/repair-skills-proof-new/control'
$source = 'E:/repair-skills-proof-new/control/history/实际来源归档ID/workspace'
$library = @('--root', $work, '--trial-control', $control, '--trial-source', $source)
& $java -jar $agentJar @library --skill-versions
& $java -jar $agentJar @library --skill-version '实际版本ID'
& $java -jar $agentJar @library --skill-candidate '实际候选ID'
& $java -jar $agentJar @library --skill-batch '实际批次ID'
& $java -jar $agentJar @library --skill-feedbacks
& $java -jar $agentJar --root $work --skill-trials --trial-control $control
& $java -jar $agentJar --root $work --skill-trial '实际试用ID' --trial-control $control
& $java -cp $agentJar com.agent.evaluation.SkillEvaluationMain --report E:/repair-skills-proof-new/evaluation
```

这些 ID/来源路径来自保存的输出和记录，不猜测“最新”版本。报告会检查每格原始归档哈希；Verified 还要对应实际任务、固定版本、初始输入和独立验收。反例观察保留全部验收和实际操作，早先破坏约束不能被最后修好抹去；原语法失败、REJECT 声明或最终通过均不能单独证明技能误用或无误用，证据不足保留 UNKNOWN。

## 三种知识模式与恢复

新管理任务通过 `--knowledge-mode none|experience|skill` 选择，默认 `experience`：

- `none` 不使用历史知识，来源复核脚本显式使用此模式。
- `experience` 只使用合格经验建议，不自动加载技能；普通聊天保持原有工具规则。
- `skill` 在独立失败后显示最多三个相关的已批准默认版本，由模型选择是否 `load_skill`，再声明 ACCEPT/REJECT。声明和通过均不单独证明收益。

普通任务示例（使用自己的项目根；已归档 Lab 任务不可复制检查点回去恢复）：

```powershell
& $java -jar $agentJar --root E:/my-project --task '修复业务边界' --knowledge-mode skill --max-steps 8
& $java -jar $agentJar --root E:/my-project --task-status '任务ID'
& $java -jar $agentJar --root E:/my-project --resume-task '原任务ID'
& $java -jar $agentJar --root E:/my-project --task-budget '原任务ID' --max-steps 12
```

恢复沿用原模式、实际读取的版本和累计预算。新版本发布不会替换进行中任务的版本；停用、缺失、损坏或来源失效会暂停并保留现场。查询后可修复原证据、通过 `--enable-skill` 明确恢复原版，或放弃旧任务、以当前业务文件新建任务；没有静默换版绕过。外部源码改变需 `--replan-task` 明确批准，结果不明命令不能自动重放。具体处置参数见 `java -jar ... --help` 及 [完整用户说明](../../README.md)。

版本摘要与候选摘要不同。`--disable-skill` / `--enable-skill` 使用 `--skill-version` 输出的**版本摘要**；候选试用/核验/发布使用候选摘要，反例还绑定证据摘要。文件 SHA-256 不可直接替代这些结构化内容摘要。

## 新 Lab 阶段与真实运行前审查

本轮若选用 `deepseek-flash`，客户端显式关闭思考模式，以适配现有工具消息协议；可通过 `'-Dagent.deepseek.model=deepseek-flash'` 指定，默认模型名称保持原值。管理任务和经验提炼均按一次逻辑请求只发送一次处理，连接中断或拒绝不会由 HTTP 层自动重试。更换模型或协议设置需要在真实额度清单中明确记录。

完整输入及预期失败见 [Lab v2 MANIFEST](../../examples/repair-lab-v2/MANIFEST.json)，控制器和参考答案必须留在模型根外：

| 阶段 | 样例 |
|---|---|
| 来源 | `source-delivery` |
| v1 初始开发 | `dev-points`、`dev-whitespace` |
| 后续使用反馈 | `feedback-product` |
| 必要旧开发回归 | `dev-points`、`dev-whitespace` |
| 正式两类迁移和一类反例 | `formal-bulk`、`formal-remaining`、`formal-grammar` |

```powershell
python examples/repair-lab-v2/control/lab_v2.py describe
python examples/repair-lab-v2/control/lab_v2.py inspect --workspace $work --archive $control
```

真实运行前，操作者在模型根之外核对：规范根/稳定项目身份；来源及开发记录完整；v1/v2 的父链、候选摘要、正反例及必要回归；两组相同的初始输入与冻结验收；目录只暴露本组版本；模型/端点/顺序/重复次数及任务和整批额度。不要将完整来源、另一版本、旧聊天、参考答案或正式结果交给生成/修订模型。

填写 [真实请求预算清单模板](real-run-budget.template.json)，逐阶段列出来源、提炼、生成、开发使用、修订、回归和六格请求额度。相同批次的共享总额度只计一次；预留、失败和结果不明都占额度，不能通过新建任务或重启清零。请求上限不等于精确 token 或金钱上限；已知小计与未知项并列，历史未记录的提炼消耗不能补成零。

模板默认 `approved: false`，所有额度未填写；它是人工审查清单，不能作为 `SkillEvaluationMain` 的可执行配置，也不会自动授权调用。离线夹具的 v1/v2 不能冒充真实开发成果。另行批准真实开发及具体额度后才使用真实模型；没有有依据且通过审核的 v2，就如实报告缺失前提，不强造修订。正式结果不能反过来选择或修改本轮 v2；后续若用于开发，应另外建立未观察过的正式集合。

## 验收范围与限制

工程完成与真实探索完成分开。离线复核全绿不表示六次真实实验已执行，也不要求得到正向收益。路径/守卫和命令限制面向可信样例，不是操作系统沙箱；脱敏不保证发现任意秘密。中断、超时或存储失败时保留未知与原始证据，不把半完成运行算成功，不承诺任意中断的恰好一次执行。

日常按改动范围选择测试，不因任务结束自动全量。帮助、脚本和打包验证可以这样执行；现成且未改变的核心回归证据应直接复用：

```powershell
mvn -B '-Dtest=MainOptionsTest' test
python -m unittest discover -s demo -p test_repair_skills.py -v
python -m unittest discover -s examples/repair-lab-v2/control -p test_lab_v2.py -v
python -m unittest discover -s examples/repair-lab/control -p 'test_*.py' -v
python demo/verify_packaged.py $java $agentJar
```
