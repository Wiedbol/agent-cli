# AI Coding Agent CLI

基于 Java 与 DeepSeek 的命令行编程助手，可以阅读和搜索项目、修改代码、执行命令，并通过独立测试判断任务是否完成。任务进度可持久化，在中断后显式恢复；经过验证的修复可以提炼为用户确认的失败经验，供后续任务参考。

## 功能

- **项目操作**：列出文件、读取内容、搜索代码、创建或写入文件、局部文本替换、运行命令。
- **可审阅编辑**：局部替换校验原文与文件版本，写入前展示 Diff，保持已有编码和换行格式。
- **独立验收**：先运行基线测试，修复后再次执行约定验收，模型回答“完成”不代表成功。
- **任务恢复**：保存目标、工具结果、文件版本、累计预算与消息历史，恢复时继续可靠检查点。
- **人工处置**：检测外部修改和结果不明的操作，由用户明确选择重新规划、重试或放弃。
- **失败经验**：提炼、确认、查看和禁用有来源的修复经验；失败时检索最多三条相关建议，恢复时重新检查有效性。
- **聊天记录**：保存和恢复普通会话，支持具名会话与 Markdown 导出。

## 环境要求

需要可重复的修复练习时，可使用[独立示例项目 Repair Lab](examples/repair-lab/README.md)，其中提供固定测试、外部工作区初始化和本地验证命令。

修复技能闭环使用独立的 [Repair Lab v2](examples/repair-lab-v2/README.md)，区分来源、初始开发、反馈、必要回归及三个正式样例；历史证据和参考答案保存在模型工作根之外，提供离线核验。

比较经验开关的效果时，可使用[隔离评测运行器](examples/repair-lab/EVALUATION.md)，按三场景、两组、三次重复记录十八次运行及各自现场。提供独立离线模拟入口，模拟结果不代表真实模型表现。

课程展示与复核可使用[演示交付包](demo/README.md)，含九分钟脚本、真实终端录制备份、课程报告和离线打包检查；[真实对照结果](examples/repair-lab/results/2026-10-23/README.md)单独说明已观察结果与局限。

| 组件 | 要求 |
|---|---|
| Java | JDK 17 或更高版本 |
| Maven | 建议 3.9 或更高版本，加入 PATH |
| Git | 从源码获取项目时需要 |
| 模型服务 | 可用的 DeepSeek API Key 与网络连接 |
| 被操作项目 | 普通聊天可指定项目目录；管理任务要求 Maven 项目及根目录 `pom.xml` |

以下命令使用 PowerShell。其他终端请按其环境变量和参数引用方式调整。构建首次运行需要下载 Maven 依赖；模型调用可能产生服务费用。

## 部署与快速开始

### 获取并构建

```powershell
git clone https://github.com/Wiedbol/agent-cli.git
cd agent-cli
mvn -B clean test
mvn -B '-DskipTests' package
```

生成含依赖的可执行文件：`target/agent-cli-0.1.0.jar`。运行时需要 Java，无需额外安装项目的依赖库；被操作项目的构建工具仍需单独准备。

### 配置模型

为当前 PowerShell 会话设置 API Key：

```powershell
$env:DEEPSEEK_API_KEY = '替换为你的API Key'
```

也可以在**启动程序时的工作目录**创建 `.env`：

```dotenv
DEEPSEEK_API_KEY=替换为你的API Key
```

读取顺序为当前工作目录的 `.env` 优先、环境变量兜底。`--root` 指定操作目录，不改变 `.env` 的查找位置。请勿提交包含真实密钥的 `.env`。

默认模型为 `deepseek-chat`。如需指定其他支持工具调用的兼容模型，可以设置 `DEEPSEEK_MODEL`，或在 `-jar` 前传入 `'-Dagent.deepseek.model=模型名称'`。系统属性优先于模型环境变量。

### 启动

```powershell
$agentJar = (Resolve-Path '.\target\agent-cli-0.1.0.jar').Path
$projectRoot = 'E:/your-maven-project'
java '-Dagent.encoding=UTF-8' -jar $agentJar --root $projectRoot
```

进入后直接输入目标，例如“阅读项目，解释订单金额的计算逻辑”。`exit` 或 `quit` 退出，`clear` 清空对话并开始新会话。

本文后续示例沿用 `$agentJar` 和 `$projectRoot`，尖括号中的任务ID、操作ID等是占位符，运行时请替换为实际值。PowerShell 中的 `-D...` 参数应加引号，并放在 `-jar` 前。中文乱码时请确认终端支持 UTF-8；确需使用 GBK 控制台时可指定 `'-Dagent.encoding=GBK'`。

无需模型时可检查本地输入输出模式：

```powershell
java '-Dagent.encoding=UTF-8' -jar $agentJar --local
```

本地模式只进行输入响应，不执行智能编程任务。未配置 Key 时，普通聊天会进入本地模式；管理任务不会因此降级为成功。

## 普通聊天与管理任务

| 方式 | 用途 | 完成判定 |
|---|---|---|
| 默认交互模式 | 读代码、讨论方案、交互操作项目 | 对话回答，不提供管理任务的独立完成保证 |
| `--task` | 围绕一个目标执行、验收、暂停及恢复 | 当前约定验收通过，状态为 `Verified` |
| `--resume` | 恢复聊天内容 | 不继续旧任务 |
| `--resume-task` | 显式恢复管理任务 | 使用原目标、约定与累计预算继续执行 |

### 管理任务的知识模式

新任务可用 `--knowledge-mode none|experience|skill` 明确选择知识范围：

| 模式 | 行为 |
|---|---|
| `experience`（默认） | 使用来源有效、启用且符合条件的失败经验 |
| `none` | 不注入历史经验，不提供技能目录或加载入口 |
| `skill` | 仅发现已批准技能，按需加载固定版本；不自动注入旧经验，无匹配时独立修复并验收 |

```powershell
java -jar $agentJar --root $projectRoot --task '修复金额计算' --knowledge-mode none
java -jar $agentJar --help
```

模式随任务保存，`--resume-task` 沿用原模式，不能用参数覆盖。`--no-experiences` 保留为 `none` 的兼容别名；它与 `--knowledge-mode` 不能混用，模式选项也不能重复指定。状态查询显示实际模式，无需模型 Key；新格式模式缺失、非法或互相矛盾时报告状态损坏，不能默认为经验模式继续。

管理任务的通用读取、列举、搜索工具不能访问根目录 `.agent/` 中的知识和历史记录；需要查看任务或经验时使用对应 CLI 查询。普通项目文件及普通聊天的工具行为保持不变。

旧任务继续按原经验开关解释模式。若恢复上下文包含旧工具成功读取元数据或扫描其祖先目录的记录，由于旧版未记录逐项可见性，任务会保守暂停并提示新建任务；普通源码读取记录仍可恢复，原轨迹与模式保留。

### 从经验生成技能候选

先用已有的 `--experiences` 查看来源，再显式创建批次并复制返回的批次 ID。以下操作仅生成待审阅候选，不会启用技能：

```powershell
java -jar $agentJar --root $projectRoot --start-skill-batch --request-limit 2
java -jar $agentJar --root $projectRoot --generate-skill '经验ID1,经验ID2' --skill-batch '批次ID'
java -jar $agentJar --root $projectRoot --skill-candidates
java -jar $agentJar --root $projectRoot --skill-candidate '候选ID'
java -jar $agentJar --root $projectRoot --skill-batch '批次ID'
java -jar $agentJar --root $projectRoot --skill-batches
```

只接受一至十条不重复、已确认且启用、来源证据有效的同项目经验；程序独立检查真实失败、修复意图与结果、文件版本链及最终验收。候选列出适用与不适用条件、诊断、修复和验证流程，绑定来源摘要并标记迁移能力未验证。相同建议独立保存，不自动合并。结构化记录与可审阅 Markdown 位于项目 `.agent/skills/`；改写内容或来源会使查询报损坏或不可用。

每次显式生成最多请求一次模型。发送前预留并保存累计消耗，失败、无效响应和中断后结果未知的请求均占额度；用量未返回时显示未知，不能当作零。额度耗尽暂停，重启不清零，也不自动重试或提高额度。可显式新建批次，旧账本保留。查询与批次创建无需 Key；只有生成需要 Key。

生成时可同时加 `--revise-skill '已发布版本ID' --revision-reason '修订原因'`，绑定同项目可用父版本和原内容摘要；缺少已发布版本时拒绝生成。生成候选本身不会启用；`--yes` 不适用于技能命令。

### 指定候选的隔离开发试用

候选只在显式试用中可加载，不对普通 SKILL 任务启用。先在 [Lab v2](examples/repair-lab-v2/README.md) 来源阶段完成修复、确认经验、生成候选和有剩余额度的批次，再用控制器 `reset` 准备开发样例。来源现场连同候选、其他知识及旧批次账本被保存在控制根的 `history/<ID>/workspace`；当前工作根保持干净。

以下是操作者显式启动入口，生成和试用的模型请求均需要事先批准对应批次额度；本仓库离线测试使用替身：

```powershell
java -jar $agentJar --root E:/repair-v2-work `
  --trial-skill '候选ID' --skill-batch '来源批次ID' `
  --trial-control E:/repair-v2-control `
  --trial-source 'E:/repair-v2-control/history/来源归档ID/workspace' `
  --trial-catalog E:/agent-cli/examples/repair-lab-v2/MANIFEST.json `
  --trial-max-steps 4

java -jar $agentJar --root E:/repair-v2-work --skill-trials --trial-control E:/repair-v2-control
java -jar $agentJar --root E:/repair-v2-work --skill-trial '试用ID' --trial-control E:/repair-v2-control
```

路径须规范、绝对且相互隔离。只接受冻结清单中的初始开发、反馈开发或必要回归输入，拒绝正式场景、旧 `.agent`、构建产物、输入变动或不足的批次额度。归档核验沿原项目身份检查真实任务、失败、修复意图/结果、文件版本链和验收，不能仅凭有效标志加载知识。来源归档的任务/经验视图禁止写入；可信控制层可在核验项目身份后写入技能候选、审核、版本和批次元数据。

独立失败基线可用后，指定候选的精简目录进入原任务反馈。Agent 可不选，或调用 `load_skill`，用目录中的 ID 与完整 SHA-256 快照版本取得完整流程；错误 ID/版本、任意路径、重复全文加载被拒绝或去重。读取后可用 `declare_skill` 声明 ACCEPT/REJECT 和原因；未读取、读取但未声明、声明采纳、声明拒绝分别记录。模型声明不能代替实际操作或独立验收。

试用沿用现有守卫、文件工具和独立验收，只允许冻结的 `mvn test` 命令，写入测试/构建配置、目标及项目标记受保护。它在限定根中自动确认普通业务写入；命令限制依赖可信样例，不提供操作系统沙箱。

每次最终消息/工具组装后，先保存批次预留、请求摘要、候选关联与脱敏消息，再发送。试用任务上限不得超过批次剩余额度，失败和结果不明请求仍占额度，未知 token 明确标记。HTTP 请求体禁止自动重发，同一试用允许多次分别预留的请求。查询无需 Key，展示目录/加载/声明事件、工具结果、验收、完整请求审计及任务和批次消耗；详细证据在控制根 `skill-trials/<ID>/`。

来源、候选、身份或冻结输入失效会暂停；必要审计保存失败停止后续操作。进程中断后可查看已保存预留与未知结果，查询不会重发。当前版本不自动恢复试用；普通 `--resume-task` 和 `--task-budget` 也不能绕过试用绑定。试用成功仅代表该开发任务通过验收，候选仍未启用、迁移能力仍未获批准。

知识前提在最终回复和验收完成后仍会核对。结果归档完整校验后一次发布；归档未完成时，查询仍可展示当前任务的已保存状态与未知请求，不把部分复制当作最终完成。

### 普通聊天会话

```powershell
java -jar $agentJar --root $projectRoot --new
java -jar $agentJar --root $projectRoot --session code-review
java -jar $agentJar --root $projectRoot --resume latest
java -jar $agentJar --root $projectRoot --export latest
```

默认保存到操作项目的 `.agent/sessions/`，可用 `--session-dir user` 保存到用户目录的 `.agent-cli/sessions/`，或用 `--session-dir <路径>` 指定其他目录。默认最近聊天不会选中管理任务会话；显式选择或导出任务会话仍可查看交流记录。导出的Markdown写入标准输出，可在命令末尾加 `> conversation.md` 保存到文件。

## 执行与验收任务

### 开始任务

```powershell
java -jar $agentJar --root $projectRoot --task '修复金额计算的边界错误'
```

默认验收命令为 `mvn test`，单次验收限时 60 秒。开始时运行基线，得到可用的真实测试结果后再进入修复；修复后独立验收，失败时反馈给模型继续处理。请求额度耗尽、重复错误、证据不可用或需要人工决定时暂停。

```powershell
java -jar $agentJar --root $projectRoot --task '修复金额计算' --accept-command 'mvn -q test' --max-steps 20
```

`--max-steps` 在管理任务中表示整个任务的累计模型请求额度，默认 12；恢复和验收失败都不会重置。API 请求失败仍占用预留次数，未知 token 用量不会按已知零值统计。

自定义验收命令也必须生成根目录 `target/surefire-reports/TEST-*.xml` 中的当前测试报告。程序检查测试身份、执行数、失败、错误、跳过及输入版本；退出码为零或模型声明成功均不足以通过。缺少可用报告、零测试、跳过或遗漏必需测试、验收期间输入变化均不能得到 `Verified`。

当前不支持仅在子模块生成报告的聚合验收或任意自定义报告目录。请先保证目标项目能在上述报告位置产生有效测试结果。

### 查看状态与恢复

```powershell
java -jar $agentJar --root $projectRoot --tasks
java -jar $agentJar --root $projectRoot --task-status <任务ID>
java -jar $agentJar --root $projectRoot --resume-task <任务ID>
```

查询不需要 API Key，不运行模型或验收。状态显示目标、验收约定、暂停原因、预算、工具操作、错误轮次和证据。恢复需要模型配置，并且必须在原来的实际项目根目录执行。

| 状态 | 含义 |
|---|---|
| `Created` | 已创建 |
| `Running` | 正在推进任务 |
| `Verifying` | 正在运行验收 |
| `Paused` | 尚未完成，需要检查原因并显式继续 |
| `Verified` | 当前文件通过约定验收 |
| `Abandoned` | 已明确放弃，不能继续恢复 |

文件或验收输入变化会使历史成功失效。任务不能通过复制元数据到另一个目录来迁移；同一项目只允许一个管理写入者。

额度不足时先明确提高**总额度**，再恢复：

```powershell
java -jar $agentJar --root $projectRoot --task-budget <任务ID> --max-steps 24
java -jar $agentJar --root $projectRoot --resume-task <任务ID>
```

提高额度本身不会调用模型或执行命令，也不会清空累计消耗。

### 正反例审核与不可变版本发布

候选发布需要同一候选摘要、冻结开发清单及模型/调用配置下的两项试用：相关新缺陷通过独立验收；不适用反例加载后明确拒绝错误步骤，正常修复并通过验收。失败、未知、缺记录或未实际加载的试用不能过关。反例判据来自试用前冻结的外部清单，不使用模型裁判。

来源归档也是本项目的技能库。以下命令的工作根、控制根和来源根沿用上节，来源根始终指向最初保存候选的归档，不随每次开发重置而改选：

```powershell
$library = @('--root', 'E:/repair-v2-work', '--trial-control', 'E:/repair-v2-control',
             '--trial-source', 'E:/repair-v2-control/history/来源归档ID/workspace')
java -jar $agentJar @library --skill-review '候选ID' --skill-digest '完整候选摘要' `
  --related-trial '相关试用ID' --counterexample-trial '反例试用ID'
java -jar $agentJar --root E:/repair-v2-work --trial-control E:/repair-v2-control `
  --attest-skill-trial '反例试用ID' --review-note '逐项核对冻结判据、拒绝声明和实际修改后的具体结论'
java -jar $agentJar @library --approve-skill '候选ID' --skill-digest '完整候选摘要' `
  --related-trial '相关试用ID' --counterexample-trial '反例试用ID'
java -jar $agentJar @library --skill-versions
java -jar $agentJar @library --skill-version '版本ID'
java -jar $agentJar @library --reject-skill '其他候选ID' --skill-digest '该候选完整摘要' --review-note '拒绝原因'
```

查看会展示完整指南、前后字段差异、来源、冻结反例判据、模型声明、全部操作参数和文件变化、独立验收、请求审计和已知/未知消耗。操作者须核验实际轨迹没有执行被拒绝的错误步骤，不能只看一句 REJECT 或最终通过；核验需要输入打印出的 `attest 试用ID 候选摘要 证据摘要`。随后发布还需另行输入 `approve 候选ID 候选摘要`。只有真实交互终端接受这些确认，管道、EOF、`--yes` 和查看操作不能替代批准。人工核验不自动触发发布。

审核期间会重新检查候选、来源及试用证据；变化使旧核验失效。拒绝保留记录且不改变来源任务的 Verified。批准后保存不可变 Markdown、版本及批准依据；最终版本文件原子写入前都不可用。新版保留技能身份、父版本和修订原因，成为未来任务的默认版本，旧版不自动删除或失效。直接改写内容或破坏审批证据会被拒绝。普通任务仅能加载已批准版本；隔离试用仍需显式指定候选。

所有审核、核验、批准、拒绝和版本查询均无需 Key，不运行模型或验收。可用同一组 `@library` 参数执行 `--skill-candidate`、`--skill-candidates`、`--skill-batch`、`--skill-batches`、`--start-skill-batch` 和 `--generate-skill`；修订仍需显式有界批次和模型授权，新候选必须重新完成试用和审核。

### 普通任务按需使用已发布技能

在同一项目的普通管理任务中选择 SKILL 模式。使用上节可信归档技能库时，显式提供两项规范绝对路径：

```powershell
java -jar $agentJar --root E:/repair-v2-work --task '修复另一模块的业务边界' `
  --knowledge-mode skill --skill-control E:/repair-v2-control `
  --skill-source 'E:/repair-v2-control/history/来源归档ID/workspace' --max-steps 8
java -jar $agentJar --root E:/repair-v2-work --task-status '任务ID'
java -jar $agentJar --root E:/repair-v2-work --task-budget '任务ID' --max-steps 12
java -jar $agentJar --root E:/repair-v2-work --resume-task '任务ID'
```

这两项技能库参数只用于新建 SKILL 任务；恢复读取原检查点，不能用命令行换库或切换知识模式。不提供时检查项目本地技能库，空库也可正常独立修复。归档仍须匹配真实项目和来源身份，不能跨目录复制元数据冒用。

只有真实、可用的失败验收才触发发现；通过的基线或基础设施故障不会给出目录。程序先核对批准状态、来源及显式文件/SHA-256 条件，再以目标和失败信息中的关键词确定性排序，最多展示三项摘要、适用限制和匹配理由。不把来源文件名相同当作迁移硬要求。文本匹配是启发式筛选，不能保证理解全部自然语言适用条件；`requires: 路径`/`必须存在: 路径` 和完整文件摘要按实际快照严格核对。

Agent 可不加载，或按目录中的**技能 ID / 固定版本 ID**调用同一个 `load_skill` 工具，全文只作为工具结果进入对话。普通任务不能加载未批准候选、任意路径或未提供的版本。`declare_skill` 单独记录 ACCEPT/REJECT 与理由；未加载、已加载但未声明、声明采纳、声明拒绝分别可查。后续验收失败可发现尚未使用的相关技能，同任务最多加载三种技能，已有可靠读取结果不会重复加入全文。

任务固定实际读取的版本、版本/候选/指南摘要；新发布只影响后续任务，不替换进行中任务的内容。恢复沿用固定内容、原请求额度与操作游标，不重放已完成写入。查询无需 Key，不运行模型或验收，展示目录/加载/声明事实、实际操作、独立验收及最终组装的消息与工具审计。

### 停用版本与保留现场恢复

先用 `--skill-version` 查看**版本摘要**（与候选摘要不同），再对具体版本进行独立交互确认：

```powershell
java -jar $agentJar @library --disable-skill '版本ID' --skill-digest '版本摘要' --review-note '停用原因'
java -jar $agentJar @library --enable-skill '同一版本ID' --skill-digest '原版本摘要' --review-note '复核原内容与来源后的恢复原因'
```

分别完整输入 `disable 版本ID 版本摘要` 或 `enable 版本ID 版本摘要`。无需 Key，不运行模型或验收；管道、EOF、`--yes` 不能代替确认。变更只原子更新独立的可用性历史，保留全部批准文件和来源。停用最新版本不会自动退回旧默认，停用某版也不会删除其父子版本历史。

恢复任务时会重新检查固定版本的状态、内容、项目、来源及当前硬前提。原版停用、缺失或损坏时，在旧待执行动作前暂停，`--task-status` 可查看原因；修复原证据或合法重新启用原版后，再显式 `--resume-task`，不提供换版绕过。外部源码修改仍须 `--replan-task` 明确批准，保留预算与历史并取消旧调用；重新检查适用性仍使用原版。

无法恢复时可按既有交互流程 `--abandon-task` 放弃，从保留的当前文件开始新任务。完成的写入/命令不重放；结果不明的副作用仍须独立人工处置。可靠技能结果可复用；不确定读取只允许重读原固定版本。必要状态写入失败会停止，损坏的新格式不会被当作旧版默认启用。

目录选择、加载、声明使用的模型轮次全部计入既有任务累计账本；没有额外检索模型或模型裁判。发送前保存请求预留和最终请求副本，SKILL HTTP 请求禁止自动重发；失败和结果不明保留消耗，缺失 token 用量显示未知。必要审计保存失败会停止后续请求和操作。批准时间或默认版本记录损坏时不自动退回旧默认；已固定的健康旧版按其自身证据验证。无匹配、未使用技能或任务通过均不单独证明知识带来了收益。

### 反馈登记与显式修订

确认合格经验后会登记“待提炼”项；普通 SKILL 任务暂停、验证通过或明确放弃后，会按任务和加载版本登记“待审查”项。未加载也如实记录。恢复更新同一事项，重复查询不写文件、不调用模型或验收。事实包括模型声明、真实操作及文件变化、独立验收、人工处置和已知/未知消耗；未声明、拒绝和不同修复路径只是审查提示，不自动判定技能有效或有害。

```powershell
java -jar $agentJar --root E:/repair-v2-work --skill-feedbacks
java -jar $agentJar @library --skill-feedbacks
java -jar $agentJar --root E:/repair-v2-work --register-skill-feedback
java -jar $agentJar @library --start-skill-batch --request-limit 4
java -jar $agentJar @library --revise-from-feedback '反馈ID1,反馈ID2' --skill-batch '批次ID' `
  --revise-skill '目标父版本ID' --revision-reason '依据选定事实的具体改动理由' `
  --feedback-catalog E:/agent-cli/examples/repair-lab-v2/MANIFEST.json
```

`--register-skill-feedback` 只为当前项目补齐已有合格来源和结束任务的事实，供升级或登记中断后使用，不提炼或保存新经验。查询、补齐均无需 Key。需要模型的反馈修订仍须显式有界批次；示例额度按实际授权设置，耗尽不自动扩额。失败、未知发送、无合理修订均保留预留和用量；模型可返回 `{"noRevision":"原因"}`，相同指南也不会强造新版本。

修订只使用明确选择的反馈和固定父版本，不自动混入其他技能。请求与候选保存选定事实快照、来源、开发清单摘要、改动理由和内容差异；任务后续更新待办不会改写已选快照。新确认经验需继续通过既有人工确认和来源检查，使用记录本身不等于允许自动提炼。

Lab 输入必须提供 `--feedback-catalog`，核对实际初始文件与冻结开发场景，拒绝正式、未知和不匹配的结果。含新确认经验的 Lab 反馈需先通过现有重置流程保留原任务归档，再加 `--feedback-source '该归档/workspace'`；控制与技能主来源仍沿用 `@library`，不能把历史源码复制回模型工作根。当前工作区的纯使用事实可直接冻结，之后正常重置试用。

修订候选仍未启用，必须重新进行相关开发试用和不适用反例核验。新发布还要覆盖冻结清单 `required-regression` 的所有必要旧开发样例；主正反例已经覆盖的场景无需重复，其他试用通过 `--regression-trials '旧试用ID1,旧试用ID2'` 绑定到 `--skill-review` / `--approve-skill`。配置、清单、候选摘要或证据不符都会阻止批准。最终仍须精确人工确认，原版与旧批准记录保留。

## 审批与异常处理

普通写入、命令操作可能要求确认。可信任务可加 `--yes` 跳过这些普通审批，但它不能批准下列独立决定。非交互运行遇到必须人工确认的情况会暂停，不等待管道输入。

| 情况 | 命令 | 必须在交互终端完整输入 |
|---|---|---|
| 人工修改代码后重新规划 | `--replan-task <任务ID>` | `replan <任务ID>` |
| 合法调整验收约定 | `--reagree-acceptance <任务ID> --accept-command 'mvn test'` | `approve <任务ID>` |
| 为结果不明的操作建立新重试 | `--retry-operation <任务ID> --operation <操作ID>` | `retry <任务ID> <操作ID>` |
| 放弃任务 | `--abandon-task <任务ID>` | `abandon <任务ID>` |
| 保存经验 | `--extract-experience <任务ID>` | `save <任务ID>` |

这些命令同样需要 `--root $projectRoot`；除提炼建议外，上表的入口不需要模型 Key。`y`、EOF 和 `--yes` 不能代替完整确认。

- **外部修改**：展示文件新增、删除及内容版本差异并保留现场。重新规划取消旧待执行调用、重新读取当前内容，仍需另行恢复并验收，不会自动还原文件。
- **验收变化**：已有测试与影响验收的构建配置受保护，普通工具不能擅自覆盖。新增测试可以增强覆盖，但不能替代必需测试。合法变化需要独立重新约定，旧成功随即失效。
- **未知副作用**：没有可靠完成记录的写入、命令和验收不会自动重放。命令或独立验收的重试还需先核查旧进程树，输入 `stopped <任务ID> <操作ID>`；已记录进程仍存活时会拒绝重试。程序不会自动杀死旧进程，也不能凭 Agent 退出断定后代命令已停止。
- **确认后继续**：重新规划、重新约定和重试批准后仍保持暂停，需要显式 `--resume-task`。验收重新约定会先运行新基线；未知操作与验收输入冲突不能靠重新约定绕过处置。

## 失败经验

只有具有真实初始失败、对应修复调用和当前有效验收证据的任务，才可提炼经验。程序核实来源后，请求模型生成适用条件、错误特征、处理建议和不适用情形；用户审核并明确确认后保存。

```powershell
java -jar $agentJar --root $projectRoot --extract-experience <来源任务ID>
java -jar $agentJar --root $projectRoot --experiences
java -jar $agentJar --root $projectRoot --experience <经验ID>
java -jar $agentJar --root $projectRoot --disable-experience <经验ID>
```

提炼需要模型 Key 和交互终端；不会在任务结束后自动提炼或保存。拒绝保存不影响任务成功。列出、查看和禁用无需模型配置；禁用保留历史记录。

新失败出现时，程序从同项目已确认、启用且来源有效的经验中确定性匹配，最多选择三条并去重，显示匹配原因与来源。无匹配时正常继续。匹配采用错误指纹、关键词和模块启发式，不保证理解任意自然语言条件；明确文件前提可写为 `requires: module/src/main/java/App.java` 或 `必须存在: module/src/main/java/App.java`，按实际文件快照核对。

恢复任务时撤下旧经验块并重新核查，禁用、损坏、来源缺失或不适用的建议不会继续作为经验注入。经验只供参考，不执行历史 Diff，不修改当前目标、守卫或验收约定。

适用条件中出现完整64位文件SHA-256时，需在当前文件快照中找到该版本；不将它当作错误消息中的关键词。自然语言条件仍采用保守启发式，不能替代严格的文件前提与独立验收。

可在新任务中通过兼容别名关闭经验（等价于 `--knowledge-mode none`）：

```powershell
java -jar $agentJar --root $projectRoot --task '修复金额计算' --no-experiences
```

开关持久化，恢复不能用参数覆盖；关闭后不读取或注入经验。

## 数据与安全边界

| 位置 | 内容 |
|---|---|
| `.agent/sessions/` | 对话与工具交流记录 |
| `.agent/tasks/` | 管理任务检查点、操作轨迹与验收证据 |
| `.agent/experiences/` | 经确认的经验与来源引用 |
| `.agentignore` | 文件浏览、搜索等工具的忽略规则 |

请保留任务和经验的完整关联数据，不手工修改检查点或证据。来源丢失或损坏会影响恢复与经验可用性；`.agentignore` 也不等于管理任务的验收或版本检查排除清单。

路径校验、敏感文件保护、高危命令拦截和落盘脱敏用于降低误操作风险，不能替代操作系统级隔离。脱敏是规则匹配，不能保证发现任意秘密；被过滤而无法可靠恢复或提炼的必要内容会导致拒绝。请在可信项目中运行，审阅修改和命令，不把密钥交给模型处理。任务恢复不承诺任意中断位置的恰好一次执行，也不提供自动回滚。

## 退出码与常见问题

| 退出码 | 含义 |
|---|---|
| `0` | 当前任务验证通过，或查询、经验管理操作成功 |
| `1` | 任务暂停、未完成或未明确批准；已批准的重规划等操作仍可能返回此码，因为任务尚未完成 |
| `2` | 参数或模型配置错误 |
| `4` | 执行、存储、来源或检查点错误 |

查询返回 `0` 不表示被查询任务已完成，请看实际状态。普通聊天正常退出也不代表某个管理任务已通过验收。

- **没有模型 Key**：核对当前工作目录的 `.env` 或 `DEEPSEEK_API_KEY`；`--root` 不改变密钥查找位置。
- **找不到 Maven 或没有报告**：在目标项目手动运行验收命令，确认 PATH、`pom.xml` 和 Surefire 报告位置。
- **命令返回 1**：查看暂停原因、未完成操作与预算；不要将提高预算当成解决文件冲突或未知副作用的方法。
- **恢复时报损坏或项目不符**：不要复制、改写证据或回退快照猜测成功；保留现场与错误信息进行检查。
- **自动审批没有生效**：`--yes` 仅针对普通工具审批；独立确认需要真实交互终端，管道输入不能代替。

任务、查询、处置与经验管理入口不能互相混用，也不能与本地模式、聊天恢复或导出参数混用。一次只执行一个工作流。
