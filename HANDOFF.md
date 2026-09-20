# agent-cli 任务交接文档

> 用途：把当前工程状态、设计决策、已知坑位与后续步骤完整交给另一个会话 / 另一位协作者。
> 生成时间：2026-09-20（Lab03 完成后更新） ｜ 仓库：`E:\agent-cli` ｜ 分支：`master` ｜ LAB03 代码提交：`6638a40`

---

## 0. 接手后的前 15 分钟（建议顺序）

1. 读 [`README.md`](README.md)（工程说明与命令）、[`Lab01-Report.md`](Lab01-Report.md)、[`Lab02-Report.md`](Lab02-Report.md)、[`Lab03-Report.md`](Lab03-Report.md)（课程要求与已交付内容）。
2. 跑一次基线：`mvn clean test` —— 应为 **107 个用例通过、BUILD SUCCESS**（首次构建需联网拉 Jackson / OkHttp / slf4j / shade）。
3. 跑一次人工验收：

   ```powershell
   "hello" | java -cp target/classes com.agent.Main
   java -cp target/classes com.agent.tool.FileToolsDemo --tree
   ```

   第一次输出 `Hello, Agent!`；第二条首行应为 `== stdout encoding: GBK ==` 且中文正常。
   启用 LLM Agent 需要 DeepSeek Key（见 §4.1）；没有 Key 时会打印提示并进入本地响应模式。
4. **动代码前先读 §6「关键设计决策与坑位」** —— 其中编码问题已经反复踩过三次，不要再改回启发式猜测。
5. 需要 `git add/commit` 时注意：本工作区的 `.git` 对沙箱是只读的，**任何写 Git 的命令都要申请提权**（Maven 首次联网同理）。

---

## 1. 项目是什么

课程项目 **AI Coding Agent CLI** 的渐进式实现，每个 Lab 在上一版基础上增量演进：

```text
V1（Lab01）        Maven + Java 17 + CLI + JUnit + Git          ✅ 已完成
V2（Lab02）        + ListFilesTool / ReadFileTool / SearchCodeTool  ✅ 已完成
V3（Lab03）        + DeepSeekClient / ToolRegistry / ReAct Agent     ✅ 已完成（待联网验收）
Lab04             把三个 Tool 抽象成统一 Tool 接口               ⏳ 待做
```

当前处于 **V3（Lab03）完成、等待 Lab04 材料** 的状态。实验指导书已下载：
`d:\EdgeDownload\Lab 03-a：LLM Client + ReAct Agent + Tool Calling（GLM-5.2）.md` 与 `Lab 03-b：....md`。

> **与指导书的差异（务必先看）**：指导书以智谱 GLM-5.2 为例，本项目按要求改用 **DeepSeek**
> （`deepseek-chat`，OpenAI 兼容 Chat Completions）。厂商差异被收敛在 `DeepSeekClient` 一个类里，
> 其余协议与循环代码不感知厂商，详见 §6.6 与 [`Lab03-Report.md`](Lab03-Report.md) §2.5。

---

## 2. 环境与工具链（实测值，不是推测）

| 项 | 值 | 备注 |
|---|---|---|
| OS / Shell | Windows 11 + PowerShell 5.1 | `[Console]::OutputEncoding` 为 CP936 |
| JDK | **22.0.2**（本机唯一 JDK） | 工程用 `--release 17` 编译，产物字节码 major version 61 |
| Maven | 3.9.16 | 配置了阿里云镜像 `maven.aliyun.com`；**首次构建必须联网** |
| Git | 2.47.1.windows.1 | 分支 `master`，**未配置 remote**（纯本地仓库） |
| JVM 属性 | `file.encoding=UTF-8`、`native.encoding=GBK`、`stdout.encoding=GBK` | **本项目三次编码故障的共同根源**，见 §6.1 |

---

## 3. 目录结构与文件职责

```text
agent-cli/
├── .agentignore              # File Tool 的忽略规则（gitignore 风格）
├── .gitignore                # 忽略 target/、.env 等
├── pom.xml                   # GAV: com.agent:agent-cli:0.1.0
├── README.md                 # 工程说明（环境/构建/运行/编码规则/Tool 签名表/Lab03 章节）
├── HANDOFF.md                # 本文档
├── Lab01-Report.md / .pdf    # 实验一报告（md 在 a0e4149 被精简过，见 §7）
├── Lab02-Report.md / .pdf    # 实验二报告（同上）
├── Lab03-Report.md           # 实验三报告（Lab03 交付物，md 为准）
├── scripts/
│   └── verify-deepseek.ps1   # Lab03 联网验收脚本（需 .env 里的 DEEPSEEK_API_KEY）
└── src/
    ├── main/java/com/agent/
    │   ├── Agent.java            # 本地模式 respond() + 模型模式 ReAct run()（MAX_ITERATIONS=10）
    │   ├── ConsoleStreams.java   # 控制台编码策略（stdin/stdout/stderr）
    │   ├── Main.java             # CLI：加载 Key + 对象接线 + REPL（clear/exit/quit/--upper）
    │   ├── llm/
    │   │   ├── LLMClient.java        # 接口 + ToolDef(name, description, parameters)
    │   │   ├── DeepSeekClient.java   # OkHttp + Jackson，Bearer 鉴权，401/402/429/5xx 诊断
    │   │   ├── Message.java          # system/user/assistant/tool 四种角色
    │   │   ├── ToolCall.java         # id + function{name, arguments(JSON 字符串)}
    │   │   ├── LLMResponse.java      # content + toolCalls + usage，hasToolCalls() 是唯一判据
    │   │   └── ApiKeyLoader.java     # .env 优先（去 BOM/引号/注释），环境变量兜底
    │   └── tool/
    │       ├── IgnoreRules.java    # .agentignore 解析与判定
    │       ├── ListFilesTool.java  # 递归列出 / 深度限制 / 树形输出
    │       ├── ReadFileTool.java   # 读取 UTF-8 / 分页读取
    │       ├── SearchCodeTool.java # 关键字 / 正则搜索，返回 Match 列表
    │       ├── ToolRegistry.java   # 三个 File Tool → 统一 execute(name, jsonArgs) -> String
    │       └── FileToolsDemo.java  # 命令行演示入口
    └── test/java/com/agent/
        ├── AgentTest.java           # 9
        ├── ConsoleStreamsTest.java  # 5
        ├── MainTest.java            # 11（含 --upper / clear / exit）
        ├── ReActAgentTest.java      # 11（FakeLLMClient 离线验证循环）
        ├── llm/
        │   ├── FakeLLMClient.java      # 测试替身：脚本化响应 + 记录请求
        │   ├── MessageTest.java        # 6
        │   ├── LLMResponseTest.java    # 4
        │   ├── DeepSeekClientTest.java # 9（MockWebServer 离线协议验证）
        │   └── ApiKeyLoaderTest.java   # 6
        └── tool/
            ├── IgnoreRulesTest.java     # 5
            ├── ListFilesToolTest.java   # 8
            ├── ReadFileToolTest.java    # 9
            ├── SearchCodeToolTest.java  # 10
            └── ToolRegistryTest.java    # 14
```

### 关键公开 API（改签名前先看这里）

| 类 | 方法 / 成员 | 说明 |
|---|---|---|
| `Agent` | `respond(String)`；`run(String)`；`clearHistory()`；`history()`；`isLlmEnabled()`；`MAX_ITERATIONS=10`；`SYSTEM_PROMPT` | `new Agent()` = 本地模式（`run` 退化为 `respond`）；`new Agent(LLMClient, ToolRegistry[, PrintStream])` = ReAct 模式；`trace` 传 `null` 表示静默 |
| `Main` | `main(String[])`、`run(Agent, BufferedReader, PrintStream)`、`run(..., boolean upperCaseOutput)`、`hasUpperFlag(String[])`、`createAgent()` | 均包级可见，便于单元测试；缺 Key 时降级为本地模式而不是退出 |
| `ConsoleStreams` | `charset(forced, detected, fallback)`、`stdinCharset()`、`stdoutCharset()`、`stderrCharset()`、`newStdinReader()`、`configureStdout()`、`configureStderr()`、`ENCODING_PROPERTY="agent.encoding"` | 编码策略单一入口，见 §6.1 |
| `LLMClient` | `LLMResponse chat(List<Message>, List<ToolDef>) throws IOException`；`record ToolDef(String name, String description, JsonNode parameters)` | 依赖倒置的边界：Agent 只认接口，`FakeLLMClient` 才能替换真实模型 |
| `DeepSeekClient` | `DeepSeekClient(apiKey[, endpoint, model, OkHttpClient])`、`DEFAULT_ENDPOINT`、`DEFAULT_MODEL`、`defaultEndpoint()`、`defaultModel()`、`describeHttpError(code, body)` | 厂商耦合点全部在这里；`agent.deepseek.endpoint` / `agent.deepseek.model` / `DEEPSEEK_MODEL` 可覆盖 |
| `Message` / `ToolCall` / `LLMResponse` | `system/user/assistant/tool` 工厂、`name()`/`arguments()`、`hasToolCalls()` | `toolCalls` 与 `toolCallId` 互斥（紧凑构造器强制）；`arguments` 是 JSON **字符串** |
| `ApiKeyLoader` | `loadDeepSeekKey()`、`load(Path, keyName, envLookup)`、`parse(content, keyName)` | `.env` 优先、环境变量兜底；只返回值，不打印 Key |
| `ToolRegistry` | `ToolRegistry([Path root[, int maxResultChars]])`、`getToolDefinitions()`、`execute(name, jsonArgs)`、`toolNames()`、`root()`；`record ToolEntry`、`interface ToolHandler`、`record Param` | `ToolHandler` 声明 `throws IOException`，由 `execute` 统一转成错误 Observation；默认结果上限 8000 字符 |
| `ListFilesTool` | `listFiles(Path)`、`listFiles(Path,int)`、`tree(Path)`、`IgnoreRules` 构造器、`fromConfig(Path)` | 返回相对路径、`/` 分隔、字典序 |
| `ReadFileTool` | `readFile(Path)`、`readFile(Path,long offset,long limit)` | 分页用 `BufferedReader` 流式，内存与 limit 相关 |
| `SearchCodeTool` | `record Match(String file,int line,String content)`、`searchCode(String,Path)`、`searchCodeRegex(Pattern,Path)`、`fromConfig(Path)` | 正则**故意不叫** `searchCode`（重载会与 `String` 版本产生 null 歧义） |
| `IgnoreRules` | `defaults()`、`load(Path)`、`parse(List<String>)`、`isIgnored(Path,Path)`、`patterns()` | 以 `/` 结尾 = 目录规则；其余 = glob（同时匹配文件名与相对路径） |

---

## 4. 常用命令

```powershell
# 构建与测试
mvn clean test                      # 107 个用例
mvn -B -DskipTests package          # 产出 fat jar（含 Jackson/OkHttp/slf4j，Main-Class: com.agent.Main）

# 运行 CLI（Lab01 本地模式 + Lab03 DeepSeek Agent）
java -cp target/classes com.agent.Main              # 交互式；有 Key 走 Agent，无 Key 走本地模式
"hello" | java -cp target/classes com.agent.Main    # 管道
java -jar target/agent-cli-0.1.0.jar --upper        # Lab01 挑战任务 3：输出转大写
mvn -q exec:java                                    # 走 exec-maven-plugin（不含第三方依赖，仅本地模式）

# 运行 File Tools Demo（Lab02）
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO   # 列文件 + 读文件 + 搜索
java -cp target/classes com.agent.tool.FileToolsDemo --tree           # 树形列出

# 需要其它编码时（一般不需要）
java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.tool.FileToolsDemo --tree   # 同时作用于 stdout 与 stderr
```

### 4.1 配置 DeepSeek（Lab03）

```powershell
# 写入 Key；.env 已被 .gitignore 忽略，绝不能提交
[IO.File]::WriteAllText("$PWD\.env", "DEEPSEEK_API_KEY=你的真实Key")

# 可选：换模型 / 换端点
java '-Dagent.deepseek.model=deepseek-chat' -jar target/agent-cli-0.1.0.jar

# 联网验收 AT01~AT07（脚本内会检查 .env）
powershell -ExecutionPolicy Bypass -File scripts\verify-deepseek.ps1
```

> `mvn exec:java` 走的是**运行时依赖类路径**（含 Jackson/OkHttp），Agent 模式同样可用；
> 已用假 Key 实测：能正常构造 `DeepSeekClient` 并打印 `已启用 DeepSeek Agent（model=deepseek-chat）`。
> 注意 `java -cp target/classes` 这种手拼 classpath 的方式**不含**第三方依赖，只适合本地模式或 Lab02 Demo。

> ⚠️ PowerShell 下 `-D` 参数**必须加引号**：`-Dagent.encoding=UTF-8` 不加引号会被拆成两个参数，报
> `ClassNotFoundException: /encoding=UTF-8`。

---

## 5. 测试基线（当前 107 个用例全绿）

| 测试类 | 用例数 | 覆盖重点 |
|---|---:|---|
| `AgentTest` | 9 | `respond` 的分支与边界（null / 空串 / 空白 / 大小写 / 原样回显 / 非 ASCII） |
| `ConsoleStreamsTest` | 5 | 编码选择：显式优先、非法名字回退、默认采用 JVM 探测值、兜底、系统属性生效 |
| `MainTest` | 11 | 用 GBK / UTF-8 **字节流**模拟控制台：中文回显、逐行、`quit`（含大小写）、EOF、`exit`、`clear`、`--upper` |
| `IgnoreRulesTest` | 5 | 目录规则 / glob / 注释 / 配置缺失回退 / 从文件加载 |
| `ListFilesToolTest` | 8 | 递归、排序、只列文件、忽略规则、深度限制、负参数、非法根目录、树形输出 |
| `ReadFileToolTest` | 9 | 整文件、UTF-8、不存在、目录、非法 UTF-8、分页（切片 / 越界 / 尾部 / 参数非法） |
| `SearchCodeToolTest` | 10 | 命中细节、子目录、同文件多处、二进制扩展名、忽略目录、不可解码文件、trim、正则、空关键字、非法目录 |
| `MessageTest` | 6 | 四种角色的工厂方法、`toolCalls`/`toolCallId` 互斥、防御性拷贝、null 安全访问器 |
| `LLMResponseTest` | 4 | `hasToolCalls()` 的 null / 空列表 / 非空分支、`toolCalls` 防御性拷贝 |
| `DeepSeekClientTest` | 9 | MockWebServer 离线验证：URL/Header/请求体形状、Bearer 鉴权、`content=null` 仍解析 `tool_calls`、历史序列化、401/429/5xx、非 JSON、缺 `choices`、空 Key |
| `ApiKeyLoaderTest` | 6 | BOM / 注释 / 引号解析、相似键名不误匹配、`.env` 优先于环境变量、缺失时返回 null |
| `ToolRegistryTest` | 14 | 三个工具 Schema、真实执行三个 Lab02 工具、分页、`maxDepth`、未知工具、非法 JSON、路径越界、结果截断 |
| `ReActAgentTest` | 11 | 本地模式退化、正常退出、工具调用循环、`tool_call_id` 回传、同轮多调用、未知工具、LLM 异常、`MAX_ITERATIONS`、`clearHistory`、历史只读、半配置拒绝 |

测试全部使用 `@TempDir` 或内存字节流，**不依赖真实项目结构**，可重复执行、可并行。

---

## 6. 关键设计决策与坑位（最需要交接的部分）

### 6.1 控制台编码 —— 已经踩过三次，别再改回启发式

**背景**：JDK 18 起 `file.encoding` 固定 `UTF-8`，但 JDK 19 起 `System.in/out` 使用 `stdin.encoding`/`stdout.encoding`（Windows 上 = 控制台代码页，本机 GBK）。两者不一致会产生两个方向的故障：

| 方向 | 症状 | 根因 |
|---|---|---|
| 输入侧 | 输入 `你好` → `I heard: ???` | `new InputStreamReader(System.in)` 用默认 UTF-8 解码 GBK 字节 |
| 输出侧 | 打印中文 → 乱码 | Java 按 GBK 写字节，而终端按 UTF-8 解码 |

**最终策略（当前实现，不要推翻）**：

```text
1) -Dagent.encoding=XXX        显式指定，优先级最高
2) JVM 探测值                  stdin.encoding / stdout.encoding（本机 GBK）
3) 兜底                        native.encoding，再兜底 UTF-8
```

**为什么不做「重定向/IDE 就按 UTF-8」这种猜测**：曾经实现过「用 `Console.isTerminal()` 区分真实终端与管道/IDE 捕获，管道按 UTF-8」的版本，实测**仍然乱码** —— 在中文 Windows 上由 IDE 捕获输出时对端仍按 GBK 解码，必须加 `-Dagent.encoding=GBK` 才正常。结论：**默认不做任何猜测，行为与直接用 `System.in`/`System.out` 一致，只在显式声明时覆盖**。

**验证编码问题的正确姿势**：

```powershell
Start-Process java -ArgumentList "-cp","target/classes","com.agent.Main" `
  -RedirectStandardInput in.txt -RedirectStandardOutput out.bin -NoNewWindow -Wait
[System.IO.File]::ReadAllBytes("out.bin")     # 看字节，再决定按哪种编码解码
```

> 不要用 PowerShell 的 `>` 重定向做编码验证：它会先按 `[Console]::OutputEncoding` 解码再按文件编码重写，等于人为加了一层转码。同理，**不要用肉眼判断乱码**，要比较字节。

### 6.2 忽略规则：`.agentignore` 与代码里的默认值是「两处」

`IgnoreRules.defaults()` 内嵌了一份默认规则（`target/`、`.git/`、`*.class` …），与仓库根的 `.agentignore` 内容一致。**这是已知的重复**：修改其一应同步另一处，否则「有无配置文件」两种情形行为不一致。若要重构，建议让 `defaults()` 只做兜底（最小集合），把完整规则留给配置文件。

另外注意规则语义：以 `/` 结尾 = **目录名**规则（该目录及其内容全部跳过），其余 = glob（同时匹配文件名与相对路径）。这解释了为什么 `target/classes/Generated.java` 虽然扩展名是 `.java`，仍会被正确跳过。

### 6.3 三个 Tool 的签名「不一致」是刻意的

`List<String>` / `String` / `List<Match>`，参数顺序也不同 —— 这是 Lab02 有意保留的技术债，用来在 **Lab04 引出统一 `Tool` 接口**。不要「顺手」把它们改成一致，否则会抹掉后续实验的动机（这一点在 Lab02 报告 §4.3、§9.2 有记录）。

顺带记录：`searchCodeRegex` 之所以不叫 `searchCode(Pattern,…)`，是因为与 `searchCode(String,…)` 并存时 `searchCode(null, root)` 会编译期歧义 —— 这本身就是「签名不统一」的症状。

### 6.4 异常契约：环境问题 vs 参数错误

| 情形 | 抛什么 | 理由 |
|---|---|---|
| 文件/目录不存在、不是普通文件 | `NoSuchFileException` | 运行时环境问题，属 `IOException` 子类，调用方可精确捕获或统一兜底 |
| `keyword` 为空、`limit <= 0`、`maxDepth < 0` | `IllegalArgumentException` | 调用方参数写错了，应立即暴露 |
| 单个文件读不了（二进制、无权限、非法 UTF-8） | **不抛，跳过** | 搜索是「尽力而为」，一个坏文件不应让整次搜索失败（Lab04 会包装成 `ToolResult(success=false)`） |

### 6.5 其它实现细节（改代码时容易踩）

- **`Files.walk` 先 `toList()` 再读文件**：Stream 持有目录句柄，Windows 上边遍历边读容易失败；`SearchCodeTool` / `ListFilesTool` 都遵循「先收集、后操作」。
- **路径统一 `/`**：Tool 输出边界做 `replace('\\','/')`，保证跨平台断言与 LLM 消费一致（`Path` 内部仍是平台抽象）。
- **分页输出统一 `\n`**：`readFile(path,offset,limit)` 用 `\n` 拼接，避免断言依赖 `System.lineSeparator()`；整文件读取则保留原文换行。
- **非法 UTF-8 会抛 `MalformedInputException`**（而不是静默替换）：`ReadFileTool` 明确报错，`SearchCodeTool` 则捕获后跳过该文件。区别是有意的，两条路径都有测试。
- **Demo 第一行会打印实际生效的编码**（`== stdout encoding: GBK ==`）：排查编码问题的第一手信息，保留它。

### 6.6 LLM 集成（Lab03）：厂商差异只允许出现在一个类里

- **`Agent` 只依赖 `LLMClient` 接口**，绝不 `new DeepSeekClient(...)`。这条约束的实际收益已经验证过一次：
  把指导书的 GLM 换成 DeepSeek，只改了 `DeepSeekClient`（地址、模型名、`DEEPSEEK_API_KEY` 文案、
  `agent.deepseek.*` 属性名、402 分支），协议层与循环层一行未改。
- **`hasToolCalls()` 是 Agent 唯一的分支判据**。不要引入「模型说了什么关键词」之类的启发式判断。
- **历史消息顺序不可调整**：`user → assistant(tool_calls) → tool(tool_call_id) → assistant(answer)`。
  漏掉带 `tool_calls` 的 assistant 消息，服务端会报角色/配对错误。
- **`tool_call_id` 不是工具名**，必须原样回传模型返回的 ID；一轮可有多个调用，每个都要有自己的结果消息。
- **工具失败要回传，HTTP 鉴权失败不要回传**：前者是模型可以自我纠正的信息，后者需要人去修配置。
  当前实现把 401/402/429/5xx 作为 `IOException` 抛出并终止循环，把工具异常转成 Observation。
- **结果截断上限 8000 字符**（`ToolRegistry.DEFAULT_MAX_RESULT_CHARS`）：一次 `read_file` 大文件
  足以把上下文撑爆，截断时会在结果末尾注明「结果已截断，原长度 N 字符」。
- **路径治理在代码里而不是 prompt 里**：`resolve()` 归一化后校验 `startsWith(root)`。
  不要因为「模型一般不会乱给路径」而删掉这段校验。
- **`.env` 必须在 `.gitignore` 中**；`ApiKeyLoader` 只返回值，不打印、不记录 Key。

### 6.7 本地模式是刻意保留的，不是历史包袱

`new Agent()`（无 LLMClient）时 `run()` 退化为 `respond()`。这样做的目的：

- 没有 Key 时 CLI 仍然可启动、可演示、可验收（Lab01 的管道命令依赖它）；
- 单元测试与非 LLM 场景不必配置任何凭据；
- 把「环境没配好」当作降级而不是致命错误，符合 CLI 工具的行为预期。

如果后续要抓「忘记配 Key」这类问题，应该在提示信息或退出码上做文章，而不是删掉本地模式。

### 6.8 stderr 也必须纳入编码策略（Lab03 新增）

`ConsoleStreams.configureStdout()` 只重建 `System.out`，导致
`-Dagent.encoding=UTF-8` 时 stdout 正常、stderr 仍乱码（只修一半）。
现在 `stderrCharset()` / `configureStderr()` 与 stdout 共用同一套优先级，
`Main.main` 两者都调用。新增输出流时要记得同步这套策略。

---

## 7. 已知问题 / 待办 / 不确定项

| # | 事项 | 状态与说明 |
|---|---|---|
| 1 | Lab01 挑战任务 3：`--upper` 参数把输出转大写 | **Lab03 已实现**。放在 `Main` 的输出边界（`run(..., boolean upperCaseOutput)`），`Agent` 公开 API 未变；`MainTest` 新增 2 个用例 |
| 2 | Lab03 真实模型联网验收（AT01~AT05、AT07） | **待人工执行**：本机 `.env` 为空、无任何 Key（刻意的安全状态）。填好 `DEEPSEEK_API_KEY` 后跑 `scripts\verify-deepseek.ps1`，通过标准见 `Lab03-Report.md` §4.5 |
| 3 | Lab04 统一 `Tool` 接口 | 未开始；Lab02 报告 §9.3 已给出预判（返回值如何文本化）；Lab03 的 `ToolRegistry` 是有意保留的过渡设计（`Map<String,String>` 丢类型、Schema 手工拼装） |
| 4 | `SearchCodeTool` 静默跳过不可读文件 | **有意的取舍**：调用方无从得知「结果不完整」。若要改进，建议额外返回被跳过文件的统计（Lab02 报告 §9.6 有讨论） |
| 5 | 报告的 `.md` 与 `.pdf` 可能不一致 | **已定规矩：`.md` 为唯一事实来源，PDF 是从 `.md` 派生的产物。** 提交 `a0e4149 pdf report` 曾在校验 PDF 的同时精简 `Lab01/02-Report.md`，导致两者不同步；此后修改报告一律先改 `.md`，如需 PDF 再重新生成，不在 PDF 上直接改。Lab03 目前只交付 `.md` |
| 8 | `deepseek-reasoner` 不支持 Function Calling | 默认模型固定为 `deepseek-chat`；若用 `-Dagent.deepseek.model=deepseek-reasoner` 覆盖，工具调用可能不触发。改模型前先确认该模型支持 tools |
| 9 | `TOOL` 结果长度与 token 成本 | 单次结果上限 8000 字符只是粗粒度防护；`read_file` 大文件仍会消耗较多 token。若要更精细，可按行数或 token 估算截断 |
| 6 | 无 CI、无远程仓库 | `git remote` 为空，全部为本地提交；未配置 GitHub Actions 等 |
| 7 | `target/` 不入库 | 已由 `.gitignore` 忽略；交接时不需携带构建产物 |

---

## 8. Git 历史与提交规范

```text
b3286fd 2026-09-20  docs: refresh handoff and lab03 report after review
6638a40 2026-09-20  lab03: add deepseek client, tool registry and react agent
a0e4149 2026-09-18  pdf report                      ← 生成两份 PDF，并精简了 md 报告
6d66a22 2026-09-14  lab01/lab02: default to JVM-detected console encoding
39ab7ba 2026-09-14  lab01: fix garbled Chinese input in Main
f4f6021 2026-09-14  lab02: make Demo stdout encoding adaptive
c35b50c 2026-09-14  lab02: fix garbled Chinese output in FileToolsDemo
9ef073e 2026-09-14  lab02: add experiment report (file tools)
7e7179d 2026-09-14  lab02: file tools
25ac774 2026-09-11  lab01: add experiment report (AI tasks, verification, thinking questions)
d06b5ff 2026-09-11  lab01: add boundary tests, quit command, README and exec plugin
a9464e7 2026-09-11  bootstrap project
```

约定：

- 提交信息前缀 `lab01:` / `lab02:` / 后续 `lab03:`，正文用 `-` 列点说明动机与验证方式（便于复盘）。
- 提交前必须 `mvn clean test` 全绿；不要把 `target/` 加进提交。
- **每次完成一个 Lab 都要同步更新对应报告**（课程评分中「AI 辅助开发记录 / 测试与验证 / 架构理解」占 35%），报告是交付物的一部分，不是可选项。

---

## 9. 课程要求速览（避免接手的会话偏离评分点）

每个 Lab 的产出都包含：**可运行代码 + 单元测试 + 命令行验收 + 实验报告**。统一评分维度：

| 维度 | 权重 |
|---|---:|
| 功能实现（能构建、能运行、测试通过） | 40% |
| 代码质量（结构规范、职责单一、命名合理） | 20% |
| 测试与验证（`@TempDir` 夹具、命令行验证、AI Agent 验证可重复） | 15% |
| AI 辅助开发记录（Prompt → 审查 → 验证 → 修改 的完整证据） | 10% |
| 架构理解与设计（能解释设计取舍与预判） | 10% |
| 技术表达（报告完整、代码说明清晰） | 5% |

> 每个 Lab 都要求记录 **Prompt → AI 输出 → 人工审查 → 测试验证 → 修改过程**，并且必须包含「人工发现 AI 输出问题」的真实案例。本工程已有的真实案例（可复用为写作素材）：0 字节 `Agent.java` 导致编译失败、AI 测试漏掉 `null`/大小写分支、`IGNORED_EXT` 把 `.DS_Store` 当扩展名、`Path::toString` 的平台分隔符、静默吞 `IOException`、编码三次踩坑。

---

## 10. 交接验收清单（Checklist）

- [x] `git status` 干净；Lab03 相关提交为 `6638a40`（代码）与 `b3286fd`（文档），其后仅有文档微调
- [x] `mvn clean test` → `Tests run: 107, Failures: 0, Errors: 0` + `BUILD SUCCESS`
- [x] `java -cp target/classes com.agent.Main` → 输入 `hello` 得到 `Hello, Agent!`；输入中文**无需任何参数**即正常显示
- [x] 可执行 fat jar：`"hello" | java -jar target/agent-cli-0.1.0.jar` → `Hello, Agent!`（缺 Key 时降级为本地模式并提示）
- [x] `java -cp target/classes com.agent.tool.FileToolsDemo --tree` → 首行 `== stdout encoding: GBK ==`，中文正常，列表里**没有** `target/`、`.git/`
- [x] `java -cp target/classes com.agent.tool.FileToolsDemo not-exist.txt` → 抛 `NoSuchFileException`（退出码 1），不出现其它崩溃
- [x] `java -cp target/classes com.agent.Main --upper` → `I HEARD: HI`（Lab01 挑战任务 3 已完成）
- [x] 已确认「报告以 md 还是 pdf 为准」：**md 为准，PDF 为派生产物**（§7 第 5 项）
- [x] Lab03 代码、测试（+55 用例）、`Lab03-Report.md`、README / HANDOFF 已同步更新
- [ ] **待人工执行**：在 `.env` 填入 `DEEPSEEK_API_KEY` 后运行 `scripts\verify-deepseek.ps1`，完成 AT01~AT05 / AT07 的真实模型验收
- [ ] 若继续做 Lab04：统一 `Tool` 接口 + 正式 `ToolRegistry` / `ToolExecutor`，并处理 `ToolRegistry` 的参数类型损失

---

## 11. 沙箱 / 权限注意事项（在本机自动化会话中工作必读）

- **Maven 首次构建或新增插件必须联网**：在受限沙箱里 `mvn` 会因 `Permission denied: getsockopt` 失败，需要以提权方式运行（依赖已在本地仓库缓存后，后续构建变快）。
- **`.git` 目录对沙箱只读**：`git add/commit/amend` 等写操作会报 `could not lock config file … Permission denied`，需要提权。
- **`Start-Process ... -WindowStyle Hidden`** 用于后台进程；验证编码时用 `-RedirectStandardOutput` 捕获**原始字节**。
- **不要用 `>` / `>>` 做编码实验**（见 §6.1）。
