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
| `ListFilesTool` | 递归列出目录文件；支持最大深度与树形输出；返回 `List<String>` |
| `ReadFileTool` | 读取 UTF-8 文本文件；支持 `offset`/`limit` 分页；返回 `String` |
| `SearchCodeTool` | 搜索包含关键字/正则的行；返回 `List<Match>`（文件 + 行号 + 内容） |
| `IgnoreRules` | 解析 `.agentignore`（gitignore 风格）规则，供上述 Tool 跳过无关路径 |
| `FileToolsDemo` | 命令行演示三个 Tool 的入口 |

## 3. 构建与测试

```bash
mvn test          # 编译 + 运行单元测试（当前 107 个用例），必须 BUILD SUCCESS 且 Failures: 0
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
并新增两个工具：

| 工具 | 风险 | 说明 |
|---|---|---|
| `read_file` / `list_files` / `search_code` | READ | 复用 Lab02 实现，签名未变，只是多实现了一层接口 |
| `write_file` | WRITE | 创建/覆盖/追加文件，自动创建父目录 |
| `run_command` | EXECUTE | 在项目根目录执行命令，默认 60 秒超时；超时会结束**整棵进程树** |

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
| `--task "..."` / `-t` | 跑完一个任务即退出 |
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

## 9. 实验报告

- [Lab01-Report.md](Lab01-Report.md)：Java Project Bootstrap（V1）
- [Lab02-Report.md](Lab02-Report.md)：File Tools（V2）
- [Lab03-Report.md](Lab03-Report.md)：LLM Client + ReAct Agent + Tool Calling（V3）
- [V4-Coding-Agent-Report.md](V4-Coding-Agent-Report.md)：六角色架构说明与效果展示（V4，含截图位，见 `docs/images/`）
- [HANDOFF.md](HANDOFF.md)：任务交接文档（工程现状、设计决策与坑位、已知问题、下一步）
