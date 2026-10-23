# agent-cli

Lab 01 的 V1 工程基础：一个 Java 17 + Maven 的命令行程序，把「输入 → 响应」逻辑与 I/O 接线分离，便于单元测试。

## 1. 环境要求

| 组件 | 版本 | 验证命令 |
|---|---|---|
| JDK | 17 及以上（本项目以 `--release 17` 编译） | `java -version` |
| Maven | 3.9 及以上 | `mvn -version` |
| Git | 2.x | `git --version` |

> `maven.compiler.release=17` 保证字节码兼容 Java 17；用更高版本 JDK（如 JDK 21/22）构建同样得到 Java 17 字节码。

## 2. 目录结构

```text
agent-cli/
├── .agentignore
├── .gitignore
├── pom.xml
├── README.md
├── scripts/verify-deepseek.ps1        # Lab03 联网验收脚本
└── src/
    ├── main/java/com/agent/
    │   ├── Agent.java
    │   ├── CodingAgentCli.java         # V4：六个角色的装配点（唯一的依赖注入地点）
    │   ├── ConsoleStreams.java
    │   ├── Main.java
    │   ├── roles/                      # 六个角色，每个角色一个文件
    │   │   ├── UserRole.java           #   用户：任务输入 + 确认放行
    │   │   ├── LlmRole.java            #   LLM：系统提示词与消息历史
    │   │   ├── RegistrarRole.java      #   注册员：工具登记与 Schema 导出
    │   │   ├── GuardRole.java          #   守卫：沙箱与高危命令裁决
    │   │   ├── ExecutorRole.java       #   执行员：执行 + 兜底 + 裁剪
    │   │   └── ControllerRole.java     #   控制员：ReAct 循环与终止判定
    │   ├── session/                    # 会话记录（聊天记录持久化）
    │   │   ├── SessionRecorder.java    #   记录接口（NONE = 不记录）
    │   │   ├── SessionStore.java       #   JSONL 追加写 / 读回 / 5 MB 轮转
    │   │   ├── SessionPaths.java       #   目录与命名规则
    │   │   ├── SessionExporter.java    #   导出为可读 markdown
    │   │   └── Redactor.java           #   落盘前的敏感内容过滤
    │   ├── command/                  # 共享命令执行契约与真实进程实现
    │   │   ├── CommandRunner.java
    │   │   ├── CommandResult.java
    │   │   └── ProcessCommandRunner.java
    │   ├── task/                       # 显式任务、独立验收及证据
    │   │   ├── TaskRecord.java
    │   │   ├── TaskStore.java
    │   │   ├── TaskAcceptance.java
    │   │   ├── TaskCoordinator.java
    │   │   ├── TaskProtection.java      # 既有验收输入保护与版本核对
    │   │   └── WorkspaceSnapshot.java
    │   ├── llm/
    │   │   ├── LLMClient.java          # 模型客户端接口（依赖倒置的边界）
    │   │   ├── DeepSeekClient.java     # Chat Completions 实现
    │   │   ├── Message.java            # 四种角色的历史消息
    │   │   ├── ToolCall.java           # 工具调用意图
    │   │   ├── LLMResponse.java        # 归一化响应
    │   │   └── ApiKeyLoader.java       # .env / 环境变量读取
    │   └── tool/
    │       ├── FileToolsDemo.java
    │       ├── IgnoreRules.java
    │       ├── ListFilesTool.java
    │       ├── ReadFileTool.java
    │       ├── RunCommandTool.java     # V4：执行命令（超时 + 进程树清理）
    │       ├── SearchCodeTool.java
    │       ├── Tool.java               # V4：统一 Tool 接口（名字/描述/Schema/风险等级）
    │       ├── ToolRequest.java        # V4：调用入参 + 路径沙箱校验
    │       ├── ToolResult.java         # V4：统一结果（成功与否 + Observation 文本）
    │       ├── ToolSchemas.java        # V4：JSON Schema 构造器
    │       ├── WriteFileTool.java      # V4：创建/覆盖/追加文件
    │       └── ToolRegistry.java       # 把三个 File Tool 适配成 Tool Calling 协议
    └── test/java/com/agent/
        ├── AgentTest.java
        ├── ConsoleStreamsTest.java
        ├── MainOptionsTest.java        # V4：CLI 参数解析
        ├── MainTest.java
        ├── ReActAgentTest.java
        ├── roles/                      # 守卫策略、执行员兜底、控制员循环的离线测试
        ├── llm/
        │   ├── FakeLLMClient.java      # 离线测试替身
        │   ├── MessageTest.java
        │   ├── LLMResponseTest.java
        │   ├── DeepSeekClientTest.java # MockWebServer 离线协议验证
        │   └── ApiKeyLoaderTest.java
        └── tool/
            ├── IgnoreRulesTest.java
            ├── ListFilesToolTest.java
            ├── ReadFileToolTest.java
            ├── SearchCodeToolTest.java
            └── ToolRegistryTest.java
```

| 类 | 职责 |
|---|---|
| `Agent` | 本地模式：`respond(String)` 决定「输入 → 响应」；模型模式：`run(String)` 执行 ReAct 循环 |
| `Main` | I/O 接线：加载 Key、创建对象、逐行读取 stdin，再打印到 stdout（`--upper` 支持输出转大写） |
| `AgentTest` | 用 JUnit 5 验证 `Agent.respond` 的各分支与边界 |
| `ConsoleStreams` | 按运行环境选择 stdin/stdout 编码（显式指定 > 非终端用 UTF-8 > 终端用控制台编码） |
| `MainTest` | 用字节流模拟 GBK / UTF-8 控制台输入，锁定中文输入与 `quit` 行为 |
| `LLMClient` | 模型客户端接口：`chat(messages, tools)` → `LLMResponse`，让 Agent 与具体厂商解耦 |
| `DeepSeekClient` | OkHttp + Jackson 实现：请求构造、Bearer 鉴权、响应解析、401/429/5xx 诊断 |
| `Message` / `ToolCall` / `LLMResponse` | Tool Calling 的数据模型：四种角色、调用意图、`hasToolCalls()` 判据 |
| `ApiKeyLoader` | `.env` 优先、环境变量兜底；`.env` 必须留在 `.gitignore` 中 |
| `ToolRegistry` | 向模型描述工具、解析参数、复用 Lab02 三个 File Tool，并把失败转成错误 Observation |
| `ReActAgentTest` | 用 `FakeLLMClient` 离线验证循环退出、Observation 回传、`MAX_ITERATIONS` 与历史清空 |
| `CodingAgentCli` | V4 装配点：把六个角色按依赖关系接起来，提供交互式与单任务两种运行方式 |
| `UserRole` / `LlmRole` / `RegistrarRole` | 用户（输入 + 确认）、LLM（提示词 + 消息历史）、注册员（工具登记与 Schema） |
| `GuardRole` | 守卫：路径沙箱、受保护文件、高危命令的放行/确认/拒绝裁决（纯函数式，可离线回归） |
| `ExecutorRole` | 执行员：执行工具、把异常转成失败结果、截断超长输出、记录耗时 |
| `ControllerRole` | 控制员：ReAct 循环、工具调用分发、最大步数与失败终止判定 |
| `Tool` / `ToolRequest` / `ToolResult` / `ToolSchemas` | V4 统一工具契约：声明式元信息 + 安全入参 + 统一结果 + Schema 构造 |
| `WriteFileTool` / `RunCommandTool` | 让 Agent 具备「写文件」与「执行命令」能力的两个新工具 |
| `CommandRunner` / `CommandResult` / `ProcessCommandRunner` | 共享进程执行入口与结构化结果，支持真实进程和离线替身 |
| `TaskCoordinator` / `TaskStore` / `TaskAcceptance` | 管理任务生命周期、项目写锁、可靠状态保存和独立 Maven 验收 |
| `ListFilesTool` | 递归列出目录文件；支持最大深度与树形输出；返回 `List<String>` |
| `ReadFileTool` | 读取 UTF-8 文本文件；支持 `offset`/`limit` 分页；返回 `String` |
| `SearchCodeTool` | 搜索包含关键字/正则的行；返回 `List<Match>`（文件 + 行号 + 内容） |
| `IgnoreRules` | 解析 `.agentignore`（gitignore 风格）规则，供上述 Tool 跳过无关路径 |
| `FileToolsDemo` | 命令行演示三个 Tool 的入口 |

## 3. 构建与测试

```bash
mvn test          # 编译 + 运行离线测试，必须 BUILD SUCCESS 且 Failures: 0
mvn clean test    # 清理 target/ 后重新构建
mvn package       # 生成 target/agent-cli-0.1.0.jar（含依赖的 fat jar，清单已配置 Main-Class）
```

## 4. 运行 CLI

推荐用 `exec-maven-plugin` 直接运行（无需手工拼 classpath）：

```bash
mvn -q exec:java
```

也可以直接用编译产物或可执行 jar：

```bash
mvn -q -DskipTests compile
java -cp target/classes com.agent.Main

mvn -q -DskipTests package
java -jar target/agent-cli-0.1.0.jar
```

`--upper`（Lab01 挑战任务 3）把输出统一转成大写：

```bash
java -jar target/agent-cli-0.1.0.jar --upper
```

管道方式（一行输入 → 一行输出）：

macOS / Linux：

```bash
echo "hello" | java -cp target/classes com.agent.Main
```

Windows PowerShell：

```powershell
"hello" | java -cp target/classes com.agent.Main
```

## 5. 交互行为

| 输入 | 输出 |
|---|---|
| `hello`（忽略大小写与首尾空白） | `Hello, Agent!` |
| 空行 / 纯空白 / 无输入（EOF） | `Hello, Agent!` |
| 其它内容（原样回显） | `I heard: <输入>` |
| `quit`（忽略大小写与首尾空白） | 程序退出 |
| `exit` | 与 `quit` 等价 |
| `clear` | 清空对话历史（模型模式下只保留 system 提示词） |

结束交互式输入：macOS / Linux 按 `Ctrl + D`，Windows PowerShell 按 `Ctrl + Z` 后回车。

### 5.1 中文输入与输出编码

CLI 与 Demo 共用 `com.agent.ConsoleStreams` 处理控制台编码：JDK 18 起 `file.encoding` 固定为
UTF-8，但 JDK 19 起 `System.in`/`System.out` 使用 `stdin.encoding`/`stdout.encoding`
（Windows 上等于控制台代码页，简体中文环境通常是 GBK），直接使用默认字符集会同时导致
**输入乱码**（`你好` 变成 `???`）与**输出乱码**。选择规则：

| 优先级 | 选用编码 |
|---|---|
| 1 | 显式指定 `-Dagent.encoding=UTF-8`（或 `GBK` 等），以该值为准 |
| 2 | JVM 探测到的 `stdin.encoding` / `stdout.encoding`（中文 Windows 通常是 GBK；UTF-8 终端与 macOS / Linux 是 UTF-8） |
| 3 | 兜底：`native.encoding`，再兜底 UTF-8 |

默认策略刻意**不做启发式猜测**（例如「输出被重定向就一定按 UTF-8 解码」并不成立：在中文 Windows
上由 IDE 运行窗口捕获输出时，对端往往仍按 GBK 解码）。因此默认行为与「直接用 `System.out`」
一致，需要不同编码时用系统属性显式声明。

若终端显示中文异常，用系统属性强制指定即可：

```powershell
java '-Dagent.encoding=GBK' -cp target/classes com.agent.Main
```

## 6. File Tools（Lab 02）

三个 Tool 让 Agent 具备「读 / 搜索项目代码」的能力。方法签名刻意保持不统一，
这处技术债将在 Lab04 引出统一的 `Tool` 接口。

| Tool | 方法 | 返回类型 | 说明 |
|---|---|---|---|
| `ListFilesTool` | `listFiles(Path root)` | `List<String>` | 递归列出文件，相对路径、`/` 分隔、字典序 |
| `ListFilesTool` | `listFiles(Path root, int maxDepth)` | `List<String>` | 限制递归层数（1 = 只看直接子项） |
| `ListFilesTool` | `tree(Path root)` | `List<String>` | 树形缩进输出 |
| `ReadFileTool` | `readFile(Path path)` | `String` | 读取 UTF-8 文本，非法 UTF-8 抛 `MalformedInputException` |
| `ReadFileTool` | `readFile(Path path, long offset, long limit)` | `String` | 流式分页读取，内存与 `limit` 相关而非文件大小 |
| `SearchCodeTool` | `searchCode(String keyword, Path root)` | `List<Match>` | 子串匹配，返回文件 + 行号 + 内容 |
| `SearchCodeTool` | `searchCodeRegex(Pattern pattern, Path root)` | `List<Match>` | 正则匹配 |

### 6.1 运行 Demo

```bash
mvn -q -DskipTests compile
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO   # 列文件 + 读 README + 搜 TODO
java -cp target/classes com.agent.tool.FileToolsDemo --tree           # 树形列出项目文件
```

Demo 的输出编码按运行环境自适应（见 `com.agent.ConsoleStreams`，规则见 §5.1）。

Demo 的第一行会打印实际生效的编码，便于排查：

```text
== stdout encoding: UTF-8 ==      ← 重定向/IDE 捕获
== stdout encoding: x-mswin-936 == ← 真实 GBK 控制台
```

如果仍显示乱码，按需要显式覆盖（PowerShell 下 `-D` 参数要加引号，否则会被拆成两个参数）：

```powershell
java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
```

### 6.2 忽略规则 `.agentignore`

Tool 遍历目录时按项目根目录的 `.agentignore` 跳过文件，语法与 `.gitignore` 一致：
`#` 开头是注释，`target/` 这类以 `/` 结尾的规则表示目录名（连同其内容一起跳过），
其余按 glob 同时匹配文件名与相对路径（如 `*.class`、`build/**`）。配置文件不存在时使用
`IgnoreRules.defaults()` 兜底。

## 7. LLM Agent（Lab 03）

三个 File Tool 从「用户直接调用」升级为「模型自主选择并调用」：

```text
用户 ──► Agent ──► DeepSeek ──Tool Calling──► File Tools
 ▲                                                │
 └──────────── 最终回答 ◄── Observation ◄─────────┘
```

| 组件 | 职责 | 不负责 |
|---|---|---|
| `DeepSeekClient` | HTTP 通信与 JSON ↔ 对象转换 | 执行工具、控制循环 |
| `ToolRegistry` | 描述 / 查找 / 执行工具 | 决定下一步调用哪个工具 |
| `Agent` | 维护消息历史、控制 ReAct 循环 | 读文件、发 HTTP |
| `Main` | 加载配置、创建对象、CLI 交互 | 业务规则 |

### 7.1 配置 DeepSeek

在仓库根目录创建 `.env`（已被 `.gitignore` 忽略，切勿提交）：

```powershell
[IO.File]::WriteAllText("$PWD\.env", "DEEPSEEK_API_KEY=你的真实Key")
```

也可以改用环境变量 `DEEPSEEK_API_KEY`。可选覆盖项：

| 目的 | 系统属性 | 环境变量 |
|---|---|---|
| 接口地址 | `-Dagent.deepseek.endpoint=...` | — |
| 模型标识 | `-Dagent.deepseek.model=...` | `DEEPSEEK_MODEL` |

默认模型是 `deepseek-chat`（支持 Function Calling），接口为
`https://api.deepseek.com/chat/completions`。指导书原始材料使用智谱 GLM，
本项目按要求改为 DeepSeek；两者请求体同为 OpenAI 兼容格式，差异集中在
`DeepSeekClient` 内部的地址、模型名与错误提示，`Agent` / `ToolRegistry` / `Message` 均无需改动。

### 7.2 运行

```bash
mvn -q -DskipTests package
java -jar target/agent-cli-0.1.0.jar
```

没有配置 Key 时不会报错退出，而是提示并进入本地响应模式（Lab01 行为），
因此 `"hello" | java -jar target/agent-cli-0.1.0.jar` 依然返回 `Hello, Agent!`。

### 7.3 工具与安全边界

| 工具 | 参数 | 复用实现 |
|---|---|---|
| `list_files` | `path`，可选 `maxDepth` | `ListFilesTool` |
| `read_file` | `path`，可选 `offset` / `limit` | `ReadFileTool` |
| `search_code` | `keyword`、`path` | `SearchCodeTool` |

三条约束：所有路径都必须落在项目根目录内（越界直接拒绝）；单个工具结果超过 8000 字符会截断并注明；
工具失败返回错误字符串作为 Observation，而不是抛异常中断整个 Agent。

### 7.4 ReAct 循环与退出条件

```text
有 tool_calls → 保存带 tool_calls 的 assistant 消息 → 逐个执行工具
             → 回传 role=tool（带原始 tool_call_id）→ 下一轮
无 tool_calls → 保存 assistant 最终回答 → 返回并退出
达到 MAX_ITERATIONS（10）→ 强制退出
LLM 调用失败 → 返回可读的错误说明，不崩溃
```

### 7.5 验证方式

离线（不需要 Key，每次改动都可回归）：

```bash
mvn test
```

其中 `DeepSeekClientTest` 用 MockWebServer 验证请求形状、Bearer 鉴权、`content=null` 时的
`tool_calls` 解析与 401/429/5xx 诊断；`ReActAgentTest` 用 `FakeLLMClient` 验证循环控制。

联网验收（需要真实 Key；2026-09-23 已实测通过，脚本可重复执行）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-deepseek.ps1
```

## 8. 六角色 Coding Agent（V4）

Lab03 的 Agent 能读文件、搜代码，但不能写文件、不能执行命令，而且安全边界散落在工具内部。
V4 补上**写文件**与**执行命令**两类工具，并把整条链路拆成六个职责单一的角色：

```text
用户 ──自然语言任务──► 控制员 ──请求──► LLM ──tool_calls──► 控制员
                        │                                     │
                        ├─► 守卫 GuardRole       放行 / 需确认 / 拒绝
                        ├─► 注册员 RegistrarRole  按名字找到工具
                        └─► 执行员 ExecutorRole   真正执行并统一兜底
                                     │
                                     └── Observation 回传 ──► LLM（下一轮）
```

### 8.1 六个角色（每个角色一个文件）

| 角色 | 文件 | 职责 | 不负责 |
|---|---|---|---|
| 用户 | `roles/UserRole.java` | 提供自然语言任务；对守卫的确认请求行使最终决定权 | 解析任务、挑选工具 |
| LLM | `roles/LlmRole.java` | 维护系统提示词与消息历史、发起 `chat` 请求 | 决定执行动作、动手做事 |
| 注册员 | `roles/RegistrarRole.java` | 登记工具、导出 Schema、按名字查找 | 执行工具、安全判断 |
| 守卫 | `roles/GuardRole.java` | 路径沙箱、受保护文件、高危命令的裁决 | 执行工具 |
| 执行员 | `roles/ExecutorRole.java` | 真正调用工具、统一兜底、裁剪超长输出 | 安全判断 |
| 控制员 | `roles/ControllerRole.java` | 驱动 ReAct 循环、解释模型意图、终止判定 | 读文件、发 HTTP、做安全判断 |

装配点只有一个：[`CodingAgentCli`](src/main/java/com/agent/CodingAgentCli.java) 负责「谁依赖谁」，
`Main` 只负责参数解析与启动。

### 8.2 工具与统一接口

三个 Lab02 工具现在都实现统一的 `Tool` 接口（名字、描述、JSON Schema、风险等级、路径参数、`execute`），
并补充写入和执行工具：

| 工具 | 风险 | 说明 |
|---|---|---|
| `read_file` / `list_files` / `search_code` | READ | 复用 Lab02 实现，签名未变，只是多实现了一层接口 |
| `write_file` | WRITE | 创建/覆盖/追加文件，自动创建父目录 |
| `replace_text` | WRITE | 预期原文唯一匹配、文件版本校验、写前 Diff 和精确替换（见 11.3） |
| `run_command` | EXECUTE | 在项目根目录执行命令，默认 60 秒超时；超时会结束**整棵进程树** |

命令执行统一经过 `CommandRunner`，默认实现是 `ProcessCommandRunner`。
`CodingAgentCli` 提供接收 `CommandRunner` 的构造入口，离线测试可替换进程，仍走控制员、守卫和执行员。
`CommandResult` 保留退出码、超时、耗时、原始解码输出及三态输出完整性（`COMPLETE` / `INCOMPLETE` / `UNKNOWN`）。
超时没有正常退出码，未捕获输出标为 `INCOMPLETE`；真实 shell 正常退出只证明取得了当时日志快照，无法证明后台后代已关闭输出，故标为 `UNKNOWN`。
离线替身可明确声明 `COMPLETE`；`outputComplete()` 仅在确认完整时为真，后续验收不能将 `UNKNOWN` 当成完整证据。工具仅对已知不完整输出增加提示，未知状态保持原有显示文案。
原始输出不在进程层裁剪，`RunCommandTool` 只在回传模型时保留原有的 4000 字符上限和截断提示，`ToolResult` 契约保持不变。
超时清理会等待 shell 与已发现的后代退出，再释放临时输出文件。

### 8.3 守卫策略

| 操作 | 结论 |
|---|---|
| 只读（读文件 / 列目录 / 搜代码） | 放行 |
| 写文件（项目内） | 需用户确认 |
| 写 `.env`、`.git/**`、`*.key` / `*.pem` 等 | 直接拒绝 |
| 路径越出项目根目录（`..`、绝对路径） | 直接拒绝 |
| 执行普通命令 | 需用户确认 |
| 高危命令（`rm -rf`、`git reset --hard`、`format`、`del /f`…） | 直接拒绝 |
| 命令引用项目外的绝对路径或上级目录 | 直接拒绝 |

`--yes` 自动同意确认（脚本化场景）；输入非 `y` 或输入结束一律按**拒绝**处理。
需要如实说明的边界：这是「黑名单 + 人工确认」的最小防线，**不是完备沙箱**，
强隔离仍然要靠容器、受限用户或虚拟机。

### 8.4 运行

```bash
# 交互式：直接输入自然语言任务
java -jar target/agent-cli-0.1.0.jar

# 单任务模式（配合 --yes 适合脚本化验收）
java -jar target/agent-cli-0.1.0.jar --yes --task "读取 pom.xml 的 artifactId，写入 demo/id.txt，然后运行 git log --oneline -1"
```

| 参数 | 作用 |
|---|---|
| `--task "..."` / `-t` | 启动管理任务，执行初始检查、编码和独立验收（见第 11 节） |
| `--yes` / `-y` | 自动同意守卫的确认请求 |
| `--root <dir>` | 指定项目根目录（沙箱边界），默认当前目录 |
| `--max-steps N` | 单个任务的最大步数，默认 12 |
| `--local` | 强制回到 Lab01 的本地回显模式 |
| `--upper` | Lab01 挑战任务 3：输出转大写 |

### 8.5 实测链路（2026-10-14）

```text
用户: 读取 pom.xml 取出 artifactId、version、依赖数量；写 demo/agent-summary.md；
      运行 git log --oneline -1 并把结果追加到文件末尾

[控制员] 第 1/14 步：让 LLM 决定下一步
[控制员] 模型请求调用工具: read_file，参数: {"path": "pom.xml"}
[守卫] ALLOW：只读操作: read_file
[执行员] 执行成功（1 ms）
[控制员] 模型请求调用工具: list_files，参数: {"path": ".", "maxDepth": 1}
[守卫] ALLOW：只读操作: list_files
[执行员] 执行成功（20 ms）
[控制员] 第 2/14 步：让 LLM 决定下一步
[控制员] 模型请求调用工具: run_command，参数: {"command": "git log --oneline -1"}
[守卫] CONFIRM：将在项目根目录执行命令: git log --oneline -1   → 已按 --yes 自动同意
[执行员] 执行成功（123 ms）
[控制员] 第 3/14 步：让 LLM 决定下一步
[控制员] 模型请求调用工具: write_file，参数: {"path": "demo/agent-summary.md", ...}
[守卫] CONFIRM：写入操作: write_file 将修改项目文件            → 已按 --yes 自动同意
[执行员] 执行成功（18 ms）
[控制员] 第 4/14 步：让 LLM 决定下一步
[控制员] 模型请求调用工具: read_file，参数: {"path": "demo/agent-summary.md"}   ← 模型自查
[执行员] 执行成功（1 ms）
[控制员] 第 5/14 步：模型未请求工具，任务结束
```

守卫的两次拦截也已实测：读取 `../outside.txt` 被拒（路径越界）、
`rm -rf /tmp/agent-test` 被拒（高危片段 + 项目外路径），Agent 都如实说明了原因而没有执行。

## 9. 会话记录与恢复（聊天记录持久化）

Agent 的对话默认会**落盘保存**，重启后可以接着上次继续聊；记录按「完整 ReAct 轨迹」保存，
包含每次工具调用的参数与结果，方便事后复盘。

```text
用户任务 ──► 控制员 ──► LLM ──► 守卫 ──► 执行员
    │                                      │
    └────────── 每条消息立即追加写 ◄────────┘
                     │
        .agent/sessions/<会话名>.jsonl
```

### 9.1 默认行为的变化（重要）

| 场景 | 现在的行为 |
|---|---|
| 直接启动（无参数） | **自动续上一次会话**：打印「续用会话 <名字>，共恢复 N 条历史消息」 |
| `--new` | 不加载历史，新开一个时间戳会话 |
| `--session <名字>` | 同名会话存在就续上，否则新建 |
| `--resume <id>` | 明确续上指定会话（`latest` 表示最近一个） |
| `clear`（会话中） | 清空对话历史并**另开一个新会话**；旧记录一条都不删 |
| `--local` | 本地回显模式，**不记录**（Lab01 行为保持不变） |

### 9.2 文件与目录

默认目录是项目内的 `.agent/sessions/`（已加入 `.gitignore`，不会误提交）；
用 `--session-dir user` 可切到用户目录 `~/.agent-cli/sessions/`，也可以直接给路径。

每个会话是一到多个 JSONL 分片，一行一个事件：

```jsonl
{"type":"meta","at":"2026-10-16T10:19:42","sessionId":"live-demo","part":1,"resumed":false}
{"type":"message","at":"2026-10-16T10:19:42","role":"user","content":"记住：我最喜欢的颜色是蓝色"}
{"type":"turn","at":"2026-10-16T10:19:43","step":1,"elapsedMillis":984,"promptTokens":971,"completionTokens":5,"tools":"-"}
{"type":"message","at":"2026-10-16T10:19:43","role":"assistant","content":"好的，已记住。"}
```

| 事件 | 内容 |
|---|---|
| `meta` | 会话开始 / 继续记录、分片序号 |
| `message` | 一条历史消息（`role` 为 user / assistant / tool，带 `toolCalls` 或 `toolCallId`） |
| `turn` | 每轮元数据：时间戳、耗时、token 用量、该轮调用的工具 |

两条工程约定：**单条消息立即追加写**（异常中断最多丢当前这一条）；
**单个分片超过 5 MB 就轮转**为 `name.part2.jsonl`，恢复时按顺序全部读回。

### 9.3 敏感内容过滤

落盘前会过滤三类内容（命中即把**整条内容**替换为 `[已过滤：疑似敏感内容]`，并记 `"redacted": true`）：

1. 工具参数里的 `path` 指向 `.env`、`*.key`、`*.pem`、`*.p12`、`*.keystore`、`id_rsa*`；
2. 内容里出现 `password` / `secret` / `token` / `api_key` 等且形如「键 = 值」的行；
3. 形如 `sk-` 开头且长度 ≥ 20 的疑似密钥串。

过滤**只影响落盘文本**，内存中的历史保持原文，因此不影响模型后续推理；
代价是复盘时看到的是占位符。API Key 从不进入对话历史，记录文件里也不会出现。

### 9.4 导出与查阅

```bash
# 导出成人类可读的 markdown（不需要 API Key）
java -jar target/agent-cli-0.1.0.jar --export live-demo > chat.md

# 最近一次会话
java -jar target/agent-cli-0.1.0.jar --export latest
```

导出的 markdown 包含每轮的时间/耗时/token、每次工具调用的参数与结果，以及被过滤条目的标记。

### 9.5 实测（2026-10-16）

```text
# 第一轮：新建具名会话
> 记住一个事实：我最喜欢的颜色是蓝色
Agent > 好的，已记住。

# 第二轮：不带任何参数启动（默认自动续聊）
续用会话 live-demo（live-demo.jsonl），共恢复 2 条历史消息
> 我最喜欢的颜色是什么？
Agent > 蓝色                      ← 历史确实恢复了

# clear：另开新会话，旧记录保留
对话历史已清空，并开始新会话 2026-10-16-102029（旧记录已保留）。
```

### 9.6 新增命令行参数

| 参数 | 作用 |
|---|---|
| `--new` | 强制新建会话，不加载历史 |
| `--resume <id>` | 续上指定会话（`latest` = 最近一个） |
| `--session <名字>` | 具名会话：存在则续、否则新建 |
| `--session-dir <路径\|user>` | 会话目录；`user` 表示用户目录 |
| `--export <id>` | 导出会话为 markdown 后退出（不需要 Key） |

## 10. 实验报告

- [Lab01-Report.md](Lab01-Report.md)：Java Project Bootstrap（V1）
- [Lab02-Report.md](Lab02-Report.md)：File Tools（V2）
- [Lab03-Report.md](Lab03-Report.md)：LLM Client + ReAct Agent + Tool Calling（V3）
- [V4-Coding-Agent-Report.md](V4-Coding-Agent-Report.md)：六角色架构说明与效果展示（V4，含截图位，见 `docs/images/`）
- [HANDOFF.md](HANDOFF.md)：任务交接文档（工程现状、设计决策与坑位、已知问题、下一步）

## 11. 显式任务与独立验收

`--task` 现在启动管理任务，需要 DeepSeek 配置和实际 Maven 项目的 `pom.xml`。任务先运行初始验收，把真实失败详情交给模型修复；模型申请完成后，程序独立执行最终验收，失败则反馈并在累计额度内继续修复。只有当前验收通过才成功退出；不可用验收、模型故障、重复错误或请求耗尽会暂停并返回非零。

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --task "修复计算错误" --yes
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --task "修复计算错误" --accept-command "mvn -q test" --yes
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --tasks
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --task-status <任务标识>
```

默认验收命令为 `mvn test`，每次验收限时 60 秒。自定义命令也必须产生当前 Surefire XML 测试报告；命令退出 0 或模型回答“已完成”都不足以通过。程序会归档旧 `TEST-*.xml`，核对当前报告的测试身份、实际执行数、失败、错误、跳过及输入文件版本。缺失、损坏、零测试、遗漏已有测试或验收期间输入变化都不能得到 `Verified`。真实 shell 的输出完整性可以为 `UNKNOWN`；验收依据完整的当前 XML 报告，不能把日志快照当成通过证据。

基线必须有可解析、实际执行且未跳过的当前测试报告，才进入编码。缺少 Maven、编译失败但没有测试报告、损坏报告或输入版本变化会先暂停，请排除原因后新建任务。真实测试失败仍可进入本次编码修复。

当前报告适配读取项目根的默认 `target/surefire-reports/TEST-*.xml`，不会扫描或移动源码中的报告样本；自定义报告目录及仅在子模块产生报告的聚合项目会因缺少可用报告而暂停。

任务状态与证据存放在项目 `.agent/tasks/`，聊天在 `.agent/sessions/`，按标识关联。任务使用实际项目路径作为身份，复制或移动后须新建任务；同一项目只允许一个管理任务写入。任务状态和证据采用可靠写入，记录故障会停止后续工作。查询不请求模型、不运行工具、不创建或修改任务数据，也不需要 API Key；验证后输入版本改变时，查询显示原验收失效。

普通聊天的 `--resume` 只恢复对话。默认最近聊天跳过 `task-` 会话，显式聊天 ID 和导出仍可使用；载入聊天不会自动继续任务。任务/查询/重新约定参数不能与 `--local`、聊天会话或导出参数混用，`--accept-command` 用于 `--task` 或 `--reagree-acceptance`。

管理任务退出码：`0` 为当前验证通过，`1` 为暂停/未完成，`2` 为参数或模型配置错误，`4` 为执行、存储或查询错误。查询成功返回 `0`，与被查询任务的完成状态无关。经验保存不是任务成功的条件。

当前支持有界修复、独立验收、验收输入保护、明确重新约定及显式任务恢复。恢复入口和安全边界见 11.4。

### 11.1 保护验收输入与重新约定

任务开始时冻结已有 `src/test/` 输入、相关 `pom.xml`、`.mvn/` 及 Maven 包装脚本。写工具不能覆盖或追加这些内容，也不能直接写入任务元数据；`--yes` 不解除保护。可以新增测试文件，最终报告仍须包含原有测试身份，更多通过的测试不能抵消遗漏或跳过的必需测试。

每个工具执行前后核对保护清单。命令删除测试、修改构建配置或新增影响执行的配置时，任务会暂停，停止同一响应中的后续工具。已经产生的文件改动会保留，不自动回滚。这是写入预防和事后检测，不是针对任意恶意脚本的完备沙箱。

确需调整验收时，在交互终端执行：

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --reagree-acceptance <任务标识> --accept-command "mvn -q test"
```

程序显示原/新命令及受保护输入变化，要求完整输入 `approve <任务标识>`。`y`、`--yes`、EOF 和非交互运行都不能代替批准；确认期间输入变化也会拒绝旧提案。此操作不需要模型配置，但批准后会执行新约定的初始验收。

批准后先保存新约定版本，使旧验证失效，再执行新基线。即使基线或存储失败，也不会把历史成功作为新约定的通过凭据。历史约定与证据保留用于审计；旧任务快照仍可读取，查询不会自动迁移文件。

重新约定仅保存新基线并使任务保持 `Paused`，不会自动继续编码，退出码为 `1`；拒绝批准也返回 `1`，执行或存储错误返回 `4`。查看输出及 `--task-status` 可区分结果。当前源码或约定版本改变后，历史 `Verified` 不能证明当前成功。

### 11.2 真实失败反馈与累计预算

管理任务的 `--max-steps` 表示整个任务的模型决策请求总额度，默认 `12`，不会在每次验收失败后重置。每次发送前持久化预留次数；网络失败仍消耗一次，缺失的 token 用量记为未知，不能当作已知的零。普通 REPL 仍按原有单次任务步数执行。

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --task "修复计算错误" --max-steps 20
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --task-budget <任务标识> --max-steps 24
```

第二条命令只明确提高已有暂停任务的总额度，保留消耗和调整历史，不调用模型、不执行命令，也不恢复任务。新额度必须大于旧额度；需要显式 `--max-steps`，不需要模型 Key、交互输入或 `--yes`。旧版未记录累计消耗的任务不能用此入口调整预算。

非交互管理任务遇到普通工具审批需求且未指定 `--yes` 时立即暂停，不读取输入或等待管道关闭。交互终端仍逐次确认，普通聊天行为保持兼容。工具统计包含已尝试但因审批阻塞的调用，操作状态可区分 `BLOCKED` 与真正执行后的结果。

同一核心错误持续三个决策轮次仍未解决时暂停。错误匹配保留测试身份、错误类型和期望值差异，忽略时间及临时目录噪声；无关的成功工具不会清零。第三轮遇到可能已经修好的源码时，先重新执行约定验收，确认错误仍存在才据此暂停。该规范化是可检查的工程启发式，不保证理解所有错误的语义。

`--task-status` 和 `--tasks` 展示累计请求/总额度、工具调用次数、已知输入/输出 token 小计、未知用量请求数、累计执行耗时、预算调整以及核心错误轮次。执行证据保存每轮请求预留与结果、工具调用标识和状态、修改文件的前后内容哈希、关联的失败验收与约定版本；记录写入失败后不继续后续副作用。任务快照使用版本 7，旧版状态仍可查询，但缺少可靠检查点的旧任务不可恢复；旧任务缺失的消耗不可用于评测。

### 11.3 原文校验与写前 Diff

新增 `replace_text`，普通对话和管理任务都可使用。先让模型 `read_file` 读取目标文件，结果开头会给出原始字节的 `文件版本 SHA-256`，再提交局部替换：

```json
{
  "path": "src/main/java/App.java",
  "old_text": "return 1;",
  "new_text": "return 2;",
  "expected_version": "<read_file 返回的 SHA-256>"
}
```

`old_text` 必须非空且恰好匹配一处，包括重叠匹配也不得多于一处；空白原文允许，`new_text` 空串表示删除。`expected_version` 必填，不能猜测；管理任务还会与已记录的文件版本核对。不存在、多个匹配、版本不符或非法路径都在写前拒绝，不请求用户批准。

执行顺序是守卫裁决、只读验证并显示完整 Diff、普通用户确认、重新检查原始版本、原子替换。`--yes` 仍显示 Diff，拒绝或 EOF 不写；管理任务在非交互审批需求时暂停。审批期间文件变化会拒绝旧提案，保留人工修改。文件系统不支持原子替换时失败，不退回可能产生部分内容的覆盖写。新工具拒绝会改变真实目标的符号链接和目录别名；守卫和验收保护仍不等于针对任意并发脚本的完备沙箱。

管理任务在工具前后及最终验收前核对已记录版本，发现外部输入变化会暂停。读取或失败结果不会自动承认外部新版本；只接纳成功写操作在原已知版本上产生的实际变化。暂停会保留当前文件；显式恢复也必须通过版本检查，外部变化可通过 11.6 的显式重新规划处置。

`read_file` 与 `replace_text` 自动识别 UTF-8、UTF-16LE/BE 的 BOM，并保持 BOM；无 BOM 默认严格 UTF-8。无 BOM 的 GBK 或 UTF-16 文件应显式提供相同的 `encoding` 参数，例如 `"encoding": "GBK"`，不会猜测旧编码。新文本无法用原编码表示、指定编码与 BOM 冲突、非法文本字节或 NUL 都会拒绝。已有统一的 LF、CRLF 或 CR 用于新文本的换行；混合换行文件可做不引入换行的局部替换，多行替换会拒绝自动选择格式。预期原文本身仍需与实际内容完全一致。

成功结果包含变更摘要与新文件 SHA-256，管理任务现有证据还关联工具调用和文件前后哈希。新工具与 `write_file` 都服从原有验收输入、构建配置、元数据及敏感路径保护。此功能不提供自动撤销或回滚。

### 11.4 从可靠工具边界显式恢复

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --resume-task <任务标识> --yes
```

`--resume-task` 需要模型配置，并且只能在任务原项目根目录执行；不能与新任务、查询、重新约定、聊天会话、本地模式或导出参数混用。恢复使用已记录的目标、验收约定、完整模型响应、消息、同轮工具位置、累计预算和核心错误计数，不重新加入原始目标。恢复时不能用 `--max-steps` 覆盖总预算；额度不足先用 `--task-budget` 明确提高，再恢复。

任务检查点是恢复依据，与工具意图、结果和文件前后版本一起原子保存并校验完整性；聊天 JSONL 只保留交流记录。已可靠记录的响应和操作结果不再次请求或重放，同轮多调用只继续剩余部分，维持 assistant/tool_call_id 配对。工具尝试次数包含恢复后的重读与再次审批尝试；无法判断是否已执行的预留保守计入统计。

副作用前必须可靠保存意图；保存失败不执行。副作用后完成记录失败立即停止后续工具。不完整的只读操作可以重读；存在意图而没有可靠结果的写入、命令或独立验收命令均显示结果不明并暂停，`--yes` 不能覆盖。已记录的最终模型响应直接进入验收，已记录的验收结果不会重新执行验收命令。本功能不恢复旧进程，也不承诺任意中断位置的恰好一次执行；人工处置入口见 11.5。

恢复先校验项目身份、单写入锁、源码与验收输入的预期版本（包含新增、删除）。外部变化显示涉及路径并暂停，保留当前文件；重新规划见 11.6。缺失、损坏或不完整的任务状态/证据不回退旧快照猜测成功；旧版任务可查询，恢复会明确暂停。必要内容已脱敏而无法忠实继续时也暂停。未知操作和外部版本冲突不会靠提高预算而消失。

本次启动的普通审批选择生效，不继承原进程的 `--yes`。未使用 `--yes` 的非交互恢复遇到普通审批就暂停，不等待输入。返回码沿用任务约定：当前 Verified 为 `0`，暂停为 `1`，配置/参数错误为 `2`，存储、执行或检查点损坏为 `4`。普通 `--resume` 仍只载入聊天，不继续旧任务；默认最近聊天排除任务会话，显式选择和导出保持兼容。

### 11.5 显式处置结果不明的操作

先用 `--task-status <任务标识>` 查看操作 ID、未知状态和已记录进程；在交互终端执行：

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --retry-operation <任务标识> --operation <操作ID>
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --abandon-task <任务标识>
```

这两个入口不需要模型 Key，也不立即运行工具或验收。重试提案展示原未知意图、当前现场的文件版本差异及重复副作用风险，需要完整输入 `retry <任务标识> <操作ID>`。命令和独立验收还必须先自行检查旧进程树，完整输入 `stopped <任务标识> <操作ID>`；`y`、`--yes`、EOF 都不能替代确认，非交互运行立即暂停，不读取输入或等待管道结束。

管理命令记录观察到的 shell/后代 PID 与启动时间；任何已记录的旧进程仍存活时拒绝重试，并在最终确认后再次检查。旧检查点可能没有 PID，短命或已脱离树的后代也未必被完整观察到，所以旧 Agent 退出、shell 退出或没有记录到进程都不证明整个命令已停止。用户必须核查这些进程；此入口不自动杀进程。进程身份记录失败也会停止后续副作用，而不会被包装成一个已完成的失败工具结果。

批准重试会原子保存人工决定、审阅的现场版本和新操作 ID；原未知操作及历史消耗保留。新尝试继续使用原模型 tool_call_id 的协议槽位，不伪造旧操作成功结果。任务仍为 Paused，需要另行 `--resume-task <任务标识>` 执行；每次再次中断都需要新的确认。普通工具审批仍按恢复时的选项执行。独立验收重试会重新执行命令并核对新报告，人工确认不会直接产生 Verified。

现场版本的接纳仅针对明确审阅的未知操作处置；存在未知操作且受保护验收输入变化时，不能批准重试，也不能先重新约定绕过停止确认；可保留轨迹并放弃后新建任务。确认期间文件变化或处置记录保存失败时不批准重试。精确替换继续验证原来的 expected_version，若旧提案已过期会拒绝，之后由模型读取现状提出新修改。一般外部变更按 11.6 显式重新规划。

放弃需要完整输入 `abandon <任务标识>`，保存 Abandoned 状态和人工决定，保留全部文件及未知轨迹，不自动回滚，也不继续任务。旧进程仍须另行核查处理。未批准放弃时保留原有 Verified 状态，不使当前成功失效。两个处置入口成功保存决定或暂停均返回 `1`；无效目标、损坏状态或存储错误返回 `4`。查询返回码仍为 `0`，可据输出区分实际状态。新快照使用 schema 6；既有 schema 4 检查点继续支持恢复，旧未知命令即使没有 PID 也仍须独立停止确认。

### 11.6 保留人工修改并重新规划

恢复检测相关文件的新增、删除和内容变化，显示路径与前后 SHA-256 并暂停；正常生成目录和任务元数据不算源码冲突。本任务已可靠记录的成功写入按预期版本继续。检测到人工修改时保留现场文件，可以明确放弃任务，或在交互终端执行：

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --replan-task <任务标识>
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --resume-task <任务标识> --yes
```

重新规划不需要模型 Key，不运行工具写入或验收命令。第一条命令展示变化，要求完整输入 `replan <任务标识>`；`y`、`--yes`、EOF、非交互运行均不批准，非交互不读取输入。确认期间文件再变会拒绝旧提案。已有未知写入、命令或验收结果须先按 11.5 处置；受保护测试或构建输入变化须先按 11.1 重新约定，不能以重新规划绕过。

批准后原子保存审阅版本和 REPLAN 历史，取消旧待执行调用并记录 `CANCELLED` 结果，保持模型工具消息配对；取消只表示不再执行，不表示成功。已完成结果和旧证据保留，人工修改不归入 Agent 的操作差异。原目标、请求总额度、累计消耗与核心错误计数保留，旧成功立即失效，任务保持 Paused。随后显式恢复会重新验收当前基线并向模型提供当前文件上下文；最终仍须新的完成声明和独立验收。预算耗尽须另行明确提高总额度。

上下文优先重新读取发生变化的现存文件，再读取当前主源码，记录当前字节哈希；大文件、敏感内容、不明确编码与超过约 24000 字符的上下文不自动附加全文，明确提示模型重新读取。默认文本读取遵循 11.3 的编码规则。重新规划只接纳这次明确审阅的版本，不提供自动覆盖、还原或回滚。返回 `1` 表示仍暂停，`4` 表示状态、执行或存储错误；已放弃任务不能重新规划。

### 11.7 显式提炼和管理失败经验

完成一次修复后，在交互终端显式提炼，审核模型建议并完整输入 `save <来源任务标识>` 才保存：

```powershell
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --extract-experience <来源任务标识>
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --experiences
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --experience <经验标识>
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar --root E:/demo-project --disable-experience <经验标识>
```

提炼要求同一验收约定下存在真实初始测试失败、可核实的成功修复调用及文件版本链，以及当前有效的最终验收。无初始失败、只有模型声称修复、人工改动无法与已记录操作对应、过期或损坏证据、拼接旧验收约定都不能提炼。模型只能生成适用条件、错误特征、处理建议和不适用情形，来源由程序独立绑定并显示。

提炼单独请求一次模型，需模型 Key；它不运行工具、不修改来源任务或重置任务预算。不会在任务完成后自动提炼；非交互入口立即返回 `1`，不请求模型、不读取输入。`--yes`、`y`、EOF 不能批准保存；拒绝保存不影响来源任务成功。确认期间现场或来源变化会拒绝旧建议。

可信经验保存在本项目 `.agent/experiences/`，含项目身份、来源任务/修复调用/失败与验证证据引用、明确确认时间和启用状态，原子写入并校验完整性。新文本及结构化字段使用现有敏感规则检查；必要内容含敏感信息或已过滤而不可用时拒绝保存，不复制原始密钥或完整执行日志。无法完整提供的超长来源也拒绝提炼。过滤属于工程规则，不保证识别任意秘密格式。

列出、查看、禁用不需要模型 Key。展示建议、来源及可用状态；后续工作区变化不抹去历史已验证来源，来源记录丢失或变更则标为不可用。跨项目复制及损坏经验会拒绝读取。禁用保留来源与确认记录，不提供自动重新启用。成功保存或管理返回 `0`，未确认返回 `1`，模型配置错误返回 `2`，来源/建议/存储错误返回 `4`；普通任务仍按独立验收判定成功。经验检索与注入见11.8。
### 11.8 在新失败中参考经验

管理任务在初始或新类型的测试失败时，确定性检索同项目已确认、启用且来源有效的经验。错误指纹优先，关键词和来源修复模块参与筛选与评分；同分按经验ID排序，相同建议去重，最多三条。终端和模型上下文显示匹配原因、来源、适用条件与局限；无匹配正常继续，损坏单条记录不阻断修复。建议不执行旧修改，不改变目标、守卫或验收，模型即使参考了误导建议也必须通过当前独立验收。

适用性是保守的工程启发式：来源修复涉及的模块须仍存在；明确文件条件可写为 `requires: module/src/main/java/App.java` 或 `必须存在: module/src/main/java/App.java`，按实际快照精确查验，报错提及路径不代表文件存在。其他条件按可识别关键词核对，不保证理解任意自然语言前提；无法匹配时不插入建议。

新任务可用 `--task "修复目标" --no-experiences` 关闭经验。开关、已处理失败指纹和已选经验ID持久化，关闭时不读取或附加建议；相同失败不逐轮追加。恢复沿用原开关，不能用该参数覆盖。新快照使用schema7并校验这些字段，旧记录默认启用。恢复时重新核查已经注入的建议属于后续#12，本票不承诺该能力。
