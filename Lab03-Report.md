# Lab 03 实验报告：LLM Client + ReAct Agent + Tool Calling（V3）

| 项目 | 内容 |
|---|---|
| 实验 | Lab 03 — LLM Client + ReAct Agent + Tool Calling（在 V2 之上构建 V3） |
| 工程 | `agent-cli`（`com.agent:agent-cli:0.1.0`） |
| 对应学习目标 | LO3（大模型集成能力）、LO4（Agent 机制实现能力）、LO5（AI 辅助开发能力） |
| 新增包 | `com.agent.llm`（模型客户端与协议数据模型）；`com.agent.tool.ToolRegistry` |
| 基线版本 | V2 提交 `a0e4149`（Lab02 完成，工作区干净） |
| 模型服务 | **DeepSeek**（`deepseek-chat`，OpenAI 兼容 Chat Completions） |
| 操作系统 | Windows 11（x64），PowerShell 5.1 |
| 工具链 | JDK 22.0.2（以 `--release 17` 编译）、Maven 3.9.16、Git 2.47.1、AI Coding Agent |
| 报告日期 | 2026-09-20 |

---

## 1. 实验目的

Lab02 结束时，三个 File Tool 已经能用，但每次都要由用户自己决定「调哪个、传什么参数」。
本实验把这一步交给模型，让 Agent 从「一堆函数」变成「会自己决定下一步的系统」。

三个学习目标在本实验中的具体含义：

| 目标 | 具体含义 | 本实验的落地 |
|---|---|---|
| **LO3 大模型集成** | Java 程序能与 LLM 服务通信，并正确处理协议 | `DeepSeekClient`：OkHttp 发 POST、Bearer 鉴权、Jackson 构造/解析 JSON、401/429/5xx 诊断 |
| **LO4 Agent 机制实现** | 实现「思考—行动—观察」闭环与安全阀 | `Agent.run()`：`hasToolCalls()` 分支、Observation 回传、`MAX_ITERATIONS` |
| **LO5 AI 辅助开发** | 会审查、验证、修正 AI 生成的结果 | §5 的四个真实案例，每个都包含「AI 输出 → 人工发现 → 修改 → 测试验证」 |

一句话概括本实验的核心：

> **模型负责决策，Java 程序负责执行，执行结果必须回传给模型。**

---

## 2. 实现过程

### 2.1 阶段一：让模型说话（`com.agent.llm`）

| 类 | 作用 | 关键设计 |
|---|---|---|
| `Message` | 一条历史消息 | `record` + 静态工厂（`system` / `user` / `assistant` / `tool`）；紧凑构造器强制「`toolCalls` 与 `toolCallId` 不能同时非空」 |
| `ToolCall` | 模型发起的一次调用 | `arguments` 是**JSON 字符串**而不是对象，解析留给工具侧 |
| `LLMResponse` | 归一化后的响应 | `hasToolCalls()` 是 Agent 唯一的循环判据 |
| `LLMClient` | 模型客户端接口 | `chat(messages, tools)`；内部 `record ToolDef(name, description, JsonNode parameters)` |
| `DeepSeekClient` | DeepSeek 实现 | 请求构造、鉴权、响应解析、错误诊断、`slf4j` 记录脱敏元信息 |
| `ApiKeyLoader` | Key 加载 | `.env` 优先（去 BOM、支持引号、跳过注释），环境变量兜底 |

阶段验收（离线版，见 §4.2）：`DeepSeekClientTest` 用 MockWebServer 断言请求体形状与响应解析，
`content` 为 `null` 时仍能解析出 `tool_calls`。

### 2.2 阶段二：让模型调用工具（`ToolRegistry`）

三个 Lab02 工具的签名不一致（`List<String>` / `String` / `List<Match>`，参数顺序也不同），
`ToolRegistry` 用一个注册表把它们适配成统一协议：

```text
工具名 + JSON 参数 ──► ToolRegistry ──► Lab02 Tool ──► 字符串结果
```

| 工具名 | 参数（JSON Schema） | 复用的 Lab02 实现 |
|---|---|---|
| `list_files` | `path`(必填)、`maxDepth`(可选) | `ListFilesTool.listFiles` |
| `read_file` | `path`(必填)、`offset`/`limit`(可选) | `ReadFileTool.readFile` |
| `search_code` | `keyword`(必填)、`path`(必填) | `SearchCodeTool.searchCode` |

三条与 Lab02 一脉相承的约定：

1. **复用而不是重写**：注册表只做适配，文件操作仍然只有一份实现；
2. **失败也是 Observation**：未知工具、参数不是合法 JSON、路径越界、底层抛异常，全部转成错误字符串；
3. **路径治理**：所有路径都相对项目根目录解析，`..` 或绝对路径越界会被拒绝（挑战任务 3）。

阶段验收：`ToolRegistryTest` 14 个用例，覆盖 Schema 结构、三个工具的真实执行、越界拒绝、结果截断。

### 2.3 阶段三：实现 ReAct Agent（`Agent.run`）

```text
LLMResponse
   ├── hasToolCalls() == true
   │      └── 保存 assistant 调用 → 逐个执行工具 → 回传 tool 消息 → 下一轮
   └── hasToolCalls() == false
          └── 保存并返回最终回答 → 退出
```

`Agent` 保留两种模式，用构造器区分：

| 构造方式 | 模式 | `run(String)` 的行为 |
|---|---|---|
| `new Agent()` | 本地模式 | 退化为 Lab01 的 `respond(String)`，不联网 |
| `new Agent(llmClient, toolRegistry)` | 模型模式 | 执行上面的 ReAct 闭环 |

保留本地模式的理由不是「兼容旧代码」这么简单：没有 Key 时程序仍应可运行、可演示、可验收，
把「环境没配好」变成「换一种响应方式」比直接 `System.exit(1)` 更符合 CLI 工具的行为预期。

阶段验收：`ReActAgentTest` 11 个用例，全部用 `FakeLLMClient` 离线编排，
其中「第二轮请求里必须出现 `tool_call_id=call_1` 的 `tool` 消息」是对 Observation 回传最直接的证明。

### 2.4 阶段四：Main 接线

`Main` 只做三件事：加载 Key、创建对象、跑 I/O 循环。

```java
ConsoleStreams.configureStdout();
ConsoleStreams.configureStderr();
Agent agent = createAgent();                 // 有 Key → DeepSeek Agent；无 Key → 本地模式并提示
run(agent, ConsoleStreams.newStdinReader(), System.out, hasUpperFlag(args));
```

REPL 命令：`clear` 清空历史、`exit` / `quit` 退出、`--upper`（Lab01 挑战任务 3）把输出转大写。
`--upper` 放在输出边界实现，`Agent` 的公开 API 一行未改 —— 这正好回答了 LAB01 报告里
「参数会影响 Agent API」的顾虑：它是展示层需求，不是决策逻辑。

### 2.5 与指导书的差异：GLM → DeepSeek

指导书（`Lab 03-a/b`）以智谱 GLM-5.2 为例，本项目按要求改用 DeepSeek。差异集中在 `DeepSeekClient` 内部：

| 项目 | 指导书（GLM） | 本项目（DeepSeek） |
|---|---|---|
| 接口地址 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `https://api.deepseek.com/chat/completions` |
| 模型标识 | `glm-5.2` | `deepseek-chat`（支持 Function Calling） |
| Key 变量名 | `GLM_API_KEY` | `DEEPSEEK_API_KEY` |
| 错误提示 | 提到 `GLM_API_KEY` | 提到 `DEEPSEEK_API_KEY`，并增加 402（余额不足）分支 |

**其余代码一行未改**：`Message` / `ToolCall` / `LLMResponse` / `LLMClient` / `ToolRegistry` / `Agent` / `Main`
都只依赖接口与 OpenAI 兼容的消息协议。这次替换本身就是「依赖倒置」价值的一次实证 ——
换厂商的成本被限制在一个类里，而不是扩散到整个工程。

---

## 3. 核心代码说明

### 3.1 `hasToolCalls()` 是唯一分支判据

```java
public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
}
```

Agent 不猜测、不解析自然语言，只看这一个布尔值决定「继续循环」还是「返回」。这样做的价值是
**行为可预测**：模型输出千变万化，但控制流只有两条路，测试可以精确覆盖。

### 3.2 历史消息顺序为什么不能错

有工具调用时，历史必须按这个顺序增长：

```text
user
assistant(tool_calls: call_1)     ← 必须先保存「模型发起的调用」
tool(tool_call_id: call_1)        ← 再回传对应的结果
assistant(final answer)
```

如果漏掉带 `tool_calls` 的 `assistant` 消息，模型看不到「自己上一轮调用了什么」，
服务端会直接报消息角色或调用配对错误。`ReActAgentTest` 通过取出第二次请求的最后两条消息，
断言它们分别是 `assistant`（含 `call_1`）与 `tool`（`tool_call_id=call_1`）。

### 3.3 Observation 回传

`Agent` 把工具执行结果包成 `Message.tool(call.id(), observation)`。要点有两个：

1. **`tool_call_id` 必须是模型返回的原始 ID**，不是工具名。一轮响应可能同时包含多个调用，
   ID 是唯一能把「结果」与「请求」配对的字段（`ReActAgentTest#shouldExecuteEveryToolCallInOneRound`
   断言了 `call_1`、`call_2` 两条结果按顺序回传）。
2. **失败结果也要回传**。工具报错时 Agent 不中断，而是把错误字符串作为 Observation 送回模型，
   让模型自己决定「换个参数」还是「向用户解释失败」。

### 3.4 退出条件与 `MAX_ITERATIONS`

| 退出路径 | 条件 | 返回 |
|---|---|---|
| 正常退出 | 某一轮没有 `tool_calls` | 该轮的 `content` |
| 安全阀退出 | 轮数达到 `MAX_ITERATIONS`（10） | 提示「达到最大推理轮数限制」 |
| 异常退出 | `chat` 抛异常（网络、鉴权、超时） | `LLM 调用失败: <原因>` |

安全阀不是「怕模型太笨」，而是防止两个真实风险：模型反复用同样的错误参数调用同一个工具；
或者工具结果始终无法让模型收敛。没有上限的循环意味着不可控的 Token 消耗。

### 3.5 工具治理与结果截断

```java
private Path resolve(String rawPath) {
    Path resolved = (path.isAbsolute() ? path : root.resolve(path)).normalize();
    if (!resolved.startsWith(root)) {
        throw new IllegalArgumentException("只允许访问项目根目录内的路径: " + rawPath);
    }
    return resolved;
}
```

模型可能给出 `../../C:/Windows/win.ini` 这类路径。把「能读什么」的边界放在
`ToolRegistry` 而不是 prompt 里，是因为**约束要落在代码上，不能落在模型的自觉上**。
另外单个工具结果超过 8000 字符会截断并注明「结果已截断」，避免一次读取把上下文撑爆（选做任务 1）。

---

## 4. 测试与验收证据

### 4.1 自动化测试总览

```text
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.AgentTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.ConsoleStreamsTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.llm.ApiKeyLoaderTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.llm.DeepSeekClientTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.llm.LLMResponseTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.llm.MessageTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- com.agent.MainTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- com.agent.ReActAgentTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.tool.IgnoreRulesTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.tool.ListFilesToolTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- com.agent.tool.ReadFileToolTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- com.agent.tool.SearchCodeToolTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- com.agent.tool.ToolRegistryTest

[INFO] Tests run: 107, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

用例数变化：Lab02 基线 52 → 本实验 107（新增 55：`DeepSeekClientTest` 9、`ToolRegistryTest` 14、
`ReActAgentTest` 11、`MessageTest` 6、`LLMResponseTest` 4、`ApiKeyLoaderTest` 6、
`MainTest` +5）。

### 4.2 离线测试如何替代非确定性的外部依赖

真实模型既「联网」又「花钱」还「不保证每次回答相同」，直接拿它做单元测试会同时破坏
可重复性与可并行性。本实验用两层替身把不确定性挡在测试之外：

| 层次 | 替身 | 覆盖的问题 |
|---|---|---|
| 模型层 | `FakeLLMClient`（脚本化响应） | 循环控制、历史顺序、`tool_call_id` 回传、安全阀、异常路径 |
| HTTP 层 | `MockWebServer`（本地 HTTP 服务） | 请求 URL / Header / JSON 形状、`content=null` 解析、401/429/5xx 诊断 |

`DeepSeekClientTest` 覆盖的关键场景（都能在不联网的情况下复现）：

- 请求体包含 `model`、`messages`，未传工具时不出现 `tools` 字段；
- `Authorization: Bearer test-key` 与 `Content-Type: application/json` 正确；
- 响应 `content` 为 `null` 但带 `tool_calls` 时，仍解析出 `call_abc` / `read_file` / `{"path":"pom.xml"}`；
- 历史里的 `assistant.tool_calls` 与 `tool.tool_call_id` 被正确序列化；
- 401 保留状态码与响应体，429 / 503 给出可读提示，非 JSON 响应与缺少 `choices` 都有明确报错。

### 4.3 AT01～AT07 对照表

| 编号 | 验收项 | 离线证据（本机已执行） | 联网证据 |
|---|---|---|---|
| AT01 | 模型连通，返回非空 `content` | `DeepSeekClientTest#shouldSendBearerAuthAndOpenAiCompatibleBody` 断言解析出 `content` | 待人工执行（§4.5） |
| AT02 | 直接回答，不调用工具 | `ReActAgentTest#shouldReturnFinalAnswerOnFirstRound`（只请求 1 轮） | 待人工执行 |
| AT03 | 单工具调用 | `ReActAgentTest#shouldLoopOnToolCallAndFeedObservationBackWithToolCallId`（真实调用 `ReadFileTool`） | 待人工执行 |
| AT04 | 多步调用 | `ReActAgentTest#shouldExecuteEveryToolCallInOneRound`（同轮两个调用全部执行并回传） | 待人工执行 |
| AT05 | 工具失败不崩溃 | `shouldFeedUnknownToolBackAsObservation` + `ToolRegistryTest#shouldReportMissingFileAsObservation` | 待人工执行 |
| AT06 | 第二轮无工具调用即退出 | `#shouldReturnFinalAnswerOnFirstRound`（`fake.callCount() == 1`） | — |
| AT07 | `clear` 清空历史 | `#shouldClearHistoryKeepingOnlySystemPrompt` + `MainTest#shouldHandleClearCommandWithoutConsumingNextLine` | 待人工执行 |

### 4.4 命令行验收记录（本机实测）

```powershell
PS> mvn -B -DskipTests package
[INFO] Building jar: E:\agent-cli\target\agent-cli-0.1.0.jar
[INFO] BUILD SUCCESS
target\agent-cli-0.1.0.jar          5,536,719 字节（含 Jackson / OkHttp / slf4j 的 fat jar）
target\original-agent-cli-0.1.0.jar    45,724 字节

PS> "hello" | java -jar target/agent-cli-0.1.0.jar
提示：未找到 DEEPSEEK_API_KEY，进入本地响应模式（Lab01 行为）。
Hello, Agent!

PS> "hi" | java -cp target/classes com.agent.Main --upper
I HEARD: HI

PS> "hi" | java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.Main
提示：未找到 DEEPSEEK_API_KEY，进入本地响应模式（Lab01 行为）。
I heard: hi

PS> java -cp target/classes com.agent.tool.FileToolsDemo --tree | Select-Object -First 3
== stdout encoding: GBK ==
== ignore rules (.agentignore) ==
target/, .git/, .idea/, .vscode/, node_modules/, *.class, ...
（列表中没有 target/、.git/）

PS> java -cp target/classes com.agent.tool.FileToolsDemo not-exist.txt
Exception in thread "main" java.nio.file.NoSuchFileException: 不是文件或不存在: not-exist.txt
exit code: 1        ← Lab02 的异常契约（NoSuchFileException + 退出码 1）未被本次改动破坏
```

### 4.5 联网验收（需要真实 Key，待人工执行）

本机 `.env` 目前为空，仓库中不存在任何 API Key（这是刻意的安全状态），
因此涉及真实 DeepSeek 服务的 AT01 / AT02 / AT03 / AT04 / AT05 / AT07 需要人工执行一次。
为此提供了可复跑的脚本：

```powershell
# 1) 写入 Key（.env 已被 .gitignore 忽略，不会进入提交）
[IO.File]::WriteAllText("$PWD\.env", "DEEPSEEK_API_KEY=你的真实Key")

# 2) 一键跑完 6 项验收并打印原始输出
powershell -ExecutionPolicy Bypass -File scripts\verify-deepseek.ps1
```

脚本用 `Start-Process -RedirectStandardInput/-RedirectStandardOutput` 捕获**原始字节**并统一按
UTF-8 交互，避免 PowerShell `>` 重定向先解码再重写造成的额外转码（这是 Lab01/Lab02 编码问题的老坑）。
通过标准：AT01 有非空回答；AT03 输出出现 `执行工具: read_file` 与后续最终回答；
AT04 出现 `search_code` 与 `read_file` 两次调用；AT05 读取不存在的文件后仍给出解释且进程不崩溃；
AT07 打印「历史已清空。」。

> 诚实说明：本报告中所有「已验证」结论均来自本机可复现的离线测试与命令行实测；
> 真实模型服务的连通性结论必须由上述脚本在配置 Key 后产生，本文不预先断言其通过。

---

## 5. AI 辅助开发记录

### 案例 A：把 AI 生成的 GlmClient 迁移到 DeepSeek

| 项目 | 内容 |
|---|---|
| **任务** | 改用 DeepSeek 模型，替换指导书中的 GLM |
| **原始 Prompt** | 「按 Lab03 指导书实现 LLM Client，但不要用 GLM，改用 DeepSeek 的模型」 |
| **AI 生成结果摘要** | 直接可用的 `GlmClient`：OkHttp + Jackson、Bearer 鉴权、`tool_calls` 解析、401/429/5xx 分支、`buildRequestJson` 便于测试 |
| **人工审查发现的问题** | 代码把厂商信息写死在多个位置：接口地址、模型名、Key 变量名、错误提示文本、系统属性名前缀（`agent.glm.*`）、类名。只改一处必然漏改 |
| **人工修改** | 逐项替换为 DeepSeek：`api.deepseek.com/chat/completions`、`deepseek-chat`、`DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`、`agent.deepseek.*`，类名改为 `DeepSeekClient`；补充 402（余额不足）分支 |
| **验证命令与结果** | `mvn -B clean test` → `Tests run: 107, Failures: 0, Errors: 0`；`DeepSeekClientTest` 9 个用例全部通过（含请求体断言 `assertEquals("deepseek-chat", body.path("model").asText())`） |
| **反思** | AI 生成的代码「看起来通用」，实际耦合点分散在 6 处。审查厂商相关代码时要先列出耦合点清单，再逐个替换并写测试锁住 |

### 案例 B：`ToolHandler` 的受检异常导致编译失败

| 项目 | 内容 |
|---|---|
| **任务** | 实现 `ToolRegistry`，把三个 File Tool 适配成 `execute(name, jsonArgs) -> String` |
| **原始 Prompt** | 指导书 AI Task 02 的参考 Prompt |
| **AI 生成结果摘要** | 注册表结构、JSON Schema 生成、`Map<String,String>` 参数解析都可用 |
| **人工审查发现的问题** | 我最初的实现把执行逻辑写成 `String execute(Map<String,String> args)`，lambda 里调用 Lab02 工具（声明了 `throws IOException`）——`mvn compile` 直接报 4 处「未报告的异常 `java.io.IOException`」 |
| **人工修改** | 两个选择：在 3 个 lambda 里各写 try/catch，或把 checked exception 显式声明到接口上统一兜底。选择后者：`String execute(Map<String,String> args) throws IOException;`，并在 `ToolRegistry.execute` 用 `catch (IOException | RuntimeException)` 统一转成错误 Observation |
| **验证命令与结果** | `mvn -B clean test` → BUILD SUCCESS；`ToolRegistryTest#shouldReportMissingFileAsObservation` 断言缺文件时返回可回传的错误字符串而不是抛异常 |
| **反思** | 「编译不过」在这里是有价值的设计反馈：它逼我把「谁负责兜底」这件事想清楚。若当初用 `throws Exception` 糊过去，错误边界就模糊了 |

### 案例 C：`-Dagent.encoding=UTF-8` 只修好了 stdout

| 项目 | 内容 |
|---|---|
| **任务** | Main 在缺少 Key 时要给出中文提示 |
| **现象** | `java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.Main` 时 stdout 中文正常，stderr 的提示仍然乱码 |
| **人工审查的诊断** | `ConsoleStreams.configureStdout()` 只重建了 `System.out`；`System.err` 仍用 JVM 探测到的 GBK，显式覆盖没有作用到它 |
| **人工修改** | 新增 `stderrCharset()` 与 `configureStderr()`，与 stdout 共用同一套优先级（显式属性 → `stderr.encoding` → `native.encoding`），`Main.main` 两者都调用；并在 `ConsoleStreamsTest` 增加断言锁住「stderr 与 stdout 同优先级」 |
| **验证命令与结果** | 修改前 stderr 中文不可读；修改后 `"hi" \| java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.Main` 的 stderr 提示按 UTF-8 正常输出，`Tests run: 107` 全绿 |
| **反思** | 这是「只修一半」的典型：编码问题在 Lab01/Lab02 已经踩过三次，一旦有第二条输出流，原策略就必须同步扩展 |

### 案例 D：`git status` 暴露的安全问题

| 项目 | 内容 |
|---|---|
| **现象** | `git status` 显示 `?? .env`，而 `.gitignore` 里没有 `.env` |
| **风险** | 一旦 `git add .`，真实 API Key 会进入 Git 历史；仅删文件无法撤销泄露 |
| **人工修改** | `.gitignore` 增加 `.env`；`ApiKeyLoader` 的类注释写明「只返回值，不打印、不记录 Key」；验收脚本启动时先检查 `.env` 是否存在 Key，缺失就直接退出而不发送请求 |
| **验证命令与结果** | `git status --porcelain` 不再列出 `.env`；全量测试仍为 107 通过 |
| **反思** | 「AI 不会主动泄露 Key」不代表「工程配置安全」。安全项必须由人检查，最省事的检查点就是提交前的 `git status` |

### 5.5 协作方式小结

四个案例的共同结构是 **Prompt → AI 输出 → 人工审查 → 修改 → 测试验证**，
其中三个（A、B、C）都是「AI 的输出能跑，但存在需要人来发现的问题」。
这正是本实验希望训练的能力：AI 负责把代码写出来，人负责定义边界、验证行为并对结果负责。

---

## 6. 问题与解决方案

| # | 问题 | 原因 | 解决 |
|---|---|---|---|
| 1 | `mvn` 在受限沙箱下报 `Permission denied: getsockopt` | 首次构建需要联网拉 Jackson / OkHttp / shade 插件 | 以提权方式执行 Maven；依赖落入本地仓库后离线构建可用 |
| 2 | 4 处「未报告的异常 `java.io.IOException`」 | `ToolHandler` 未声明受检异常 | 接口显式 `throws IOException`，由 `ToolRegistry.execute` 统一兜底（案例 B） |
| 3 | stderr 中文乱码且不受 `-Dagent.encoding` 影响 | 只重建了 `System.out` | 新增 `stderrCharset()` / `configureStderr()`（案例 C） |
| 4 | `.env` 未被忽略 | `.gitignore` 缺少 `.env` | 补规则并复验 `git status`（案例 D） |
| 5 | 模型只调用工具时响应里 `content` 为 `null` | Chat Completions 的正常形态 | 解析时把 `null` 归一化为 `""`，且不因此跳过 `tool_calls` 解析，并专门写测试锁定 |
| 6 | 工具结果可能极长，撑爆上下文 | 读取大文件/大范围搜索结果 | `ToolRegistry` 单次结果 8000 字符上限，截断后注明原长度 |
| 7 | 模型可能请求根目录之外的路径 | 模型输出不可信 | `resolve()` 归一化后校验 `startsWith(root)`，越界直接拒绝（挑战任务 3） |
| 8 | 忘记回传 `tool_call_id` 会怎样（指导书 AI Task 04 要求人为制造） | 服务端无法配对调用与结果 | 通过 `ReActAgentTest` 断言第二条请求中存在 `tool_call_id=call_1` 的 `tool` 消息；删掉这一行测试立即失败 |
| 9 | 没有 Key 时 CLI 直接退出，无法演示 | 原实现把「缺配置」当成致命错误 | 改为降级到本地模式并提示，Lab01 的管道验收命令继续有效 |

---

## 7. 思考题回答

### 7.1 架构分析

**1. `Agent` 依赖 `LLMClient` 接口而不是具体客户端类，对测试与更换模型有什么价值？**

接口把「Agent 需要什么」与「谁来满足它」分开。测试时传入 `FakeLLMClient`，
可以精确编排「第一轮返回工具调用、第二轮返回最终答案」，断言请求次数与历史内容，
既不联网也不消耗 Token，还不受模型输出波动影响；更换模型时只替换实现类
（本项目就把 GLM 换成 DeepSeek，只动了 `DeepSeekClient` 一个文件），
`Agent`、`ToolRegistry`、数据模型全部不动。若 Agent 直接 `new GlmClient(...)`，
上述两点都会失效：测试无法离线，换模型要改控制逻辑。

**2. 为什么 `Main` 不应直接解析 `tool_calls` 或执行文件工具？**

`Main` 的职责是「组装 + 交互」，一旦它开始解析协议或读写文件，就会同时依赖 HTTP 细节、
消息协议与文件系统，变成三处耦合的中心：换模型要改 `Main`，改工具要改 `Main`，
而且 ReAct 循环无法被单元测试覆盖（只能启动进程测）。本项目的分法是
`DeepSeekClient`（通信）/ `ToolRegistry`（执行）/ `Agent`（决策）/ `Main`（接线），
每个类只有一个变化的理由，`Main` 的 I/O 循环也因参数化而可直接用字节流测试。

### 7.2 Agent 机制分析

**1. 把 ReAct 描述成状态机**

状态：`IDLE`（等待用户输入）→ `REASONING`（已发送请求，等待模型）→
`ACTING`（执行工具调用）→ `OBSERVING`（把结果写入历史）→ 回到 `REASONING`；
`ANSWERED`（收到无工具调用的回答）。

| 当前状态 | 事件 | 下一个状态 |
|---|---|---|
| `IDLE` | 用户输入 | `REASONING` |
| `REASONING` | 响应含 `tool_calls` | `ACTING` |
| `REASONING` | 响应无 `tool_calls` | `ANSWERED`（**正常退出**） |
| `REASONING` | 轮数达到 `MAX_ITERATIONS` | `ANSWERED`（安全阀退出） |
| `REASONING` | `chat` 抛异常 | `ANSWERED`（错误退出） |
| `ACTING` | 每个调用执行完毕 | `OBSERVING` |
| `OBSERVING` | 结果已作为 `tool` 消息入历史 | `REASONING` |

**2. 谁决定工具、谁执行工具？为什么必须分开？**

模型决定（返回结构化的 `tool_calls`），Java 程序执行（`ToolRegistry` 调用真正的
`ReadFileTool` 等）。必须分开的原因：模型不能访问本地文件系统，也没有执行权限；
如果让「决策」与「执行」合一，就等于把文件读写权限交给一个可能被提示词注入影响的组件。
分开之后，执行侧可以在自己的边界上做治理（路径限制、结果截断、错误兜底），
而这些约束不依赖模型是否「听话」。

**3. 如果下一轮只发送 Tool Result，不发送完整 Message History，会发生什么？**

模型会失去上下文：它不知道用户原始问题、不知道自己上一轮为什么调用这个工具，
也不知道之前已经获得过哪些信息。轻则答非所问、重复调用同一个工具，
重则因为消息序列缺少配对的 `assistant.tool_calls` 而被服务端直接拒绝，
表现为「消息角色或调用配对错误」。Tool Calling 协议要求 `assistant(tool_calls)` 与
`tool(tool_call_id)` 成对出现，只发结果等于只发了配对的一半。

**4. 为什么必须设置 `MAX_ITERATIONS`？除达到上限外还有哪些退出路径？**

因为循环的推进由外部模型驱动，程序无法事先证明它一定收敛：模型可能反复用同样的错误参数
调用同一个工具，或始终得不到足以作答的信息。没有上限就意味着不可控的时间与 Token 消耗。
本实现的退出路径共三条：① 某轮没有 `tool_calls`（正常退出，返回 `content`）；
② 达到 `MAX_ITERATIONS=10`（安全阀）；③ `llm.chat` 抛异常（网络/鉴权/超时，返回可读错误）。

**5. 为什么工具失败也要回传？什么错误不适合继续回传？**

工具失败对模型是有价值的信息：它可能是「路径写错了」「关键字为空」「该文件不是文本」，
模型看到错误原因后可以修正参数或换一种做法，这比让整个 Agent 崩掉更符合「尽力而为」的定位，
而且用户还能得到一个解释而不是堆栈。另外，失败结果回传也让「错误」成为可测试的行为
（`shouldFeedUnknownToolBackAsObservation`）。
**不适合继续回传的错误**是那些「继续循环没有意义、且可能造成副作用或持续消耗」的失败：
例如 401 鉴权失败（Key 无效，重试必然失败）、余额不足（402）、以及工具契约之外的
严重错误（如安全策略拒绝、进程级资源耗尽）。本实现把 401/402/429 等 HTTP 错误
直接作为 `IOException` 抛给 Agent 并终止循环，而不是伪装成 Observation 继续问模型 ——
这类问题需要用户去修配置，不是模型能自行解决的。

---

## 8. 遗留事项与 Lab04 预告

| # | 事项 | 说明 |
|---|---|---|
| 1 | 联网验收（AT01/AT03/AT04…） | 需在 `.env` 填入真实 `DEEPSEEK_API_KEY` 后执行 `scripts/verify-deepseek.ps1`（§4.5） |
| 2 | `ToolRegistry` 的参数类型损失 | 参数被压成 `Map<String,String>`，数字/布尔/数组信息会丢失；当前三个工具够用，Lab04 应由统一 `Tool` 接口自描述 Schema 与参数类型 |
| 3 | `SearchCodeTool` 静默跳过不可读文件 | Lab02 遗留：调用方无从得知「结果不完整」，可考虑返回被跳过文件的统计 |
| 4 | `.agentignore` 与 `IgnoreRules.defaults()` 的重复 | Lab02 遗留，修改其一需同步另一处 |
| 5 | 报告以 `.md` 为准 | PDF 为派生产物，后续修改先改 `.md` 再重新生成 PDF，避免三处不一致 |
