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

可在新任务中关闭经验：

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
