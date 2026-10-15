# AI Coding Agent CLI：六角色架构说明与效果展示

| 项目 | 内容 |
|---|---|
| 工程 | `agent-cli`（`com.agent:agent-cli:0.1.0`） |
| 日期 | 2026-10-15 |
| 模型 | DeepSeek `deepseek-chat`（OpenAI 兼容 Chat Completions） |
| 代码位置 | `src/main/java/com/agent/`（角色在 `roles/`，工具在 `tool/`） |
| 测试 | `mvn clean test` → 175 个用例全绿 |

---

## 1. 这份文档讲什么

这份文档只回答三个问题：

1. 六个角色各自是哪段代码、负责什么、不负责什么；
2. 它们在一次任务里是怎么串起来的；
3. 运行起来长什么样（最后一节留出截图位，方便补充运行截图）。

程序的目标是一条完整链路：**用户说一句自然语言任务 → Agent 自己读文件、写文件、执行命令 → 给出可验证的结果**。

```text
用户 ──自然语言任务──► 控制员 ──请求──► LLM ──tool_calls──► 控制员
                        │                                     │
                        ├─► 守卫 GuardRole       放行 / 需确认 / 拒绝
                        ├─► 注册员 RegistrarRole  按名字找到工具
                        └─► 执行员 ExecutorRole   真正执行并统一兜底
                                     │
                                     └── Observation 回传 ──► LLM（下一轮）
```

---

## 2. 角色总览

| # | 角色 | 文件 | 一句话职责 | 关键方法 |
|---:|---|---|---|---|
| 1 | 用户 | `roles/UserRole.java` | 提供任务，并对危险操作行使最终决定权 | `nextTask()`、`confirm(String)` |
| 2 | LLM | `roles/LlmRole.java` | 维护系统提示词与消息历史，向模型发起请求 | `beginTask()`、`ask()`、`recordToolResult()` |
| 3 | 注册员 | `roles/RegistrarRole.java` | 登记工具、导出 Schema、按名字查找 | `standard(root)`、`find(name)`、`toolDefinitions()` |
| 4 | 守卫 | `roles/GuardRole.java` | 裁决「能不能做」：放行 / 需确认 / 拒绝 | `review(tool, args)` |
| 5 | 执行员 | `roles/ExecutorRole.java` | 真正执行工具，兜底异常、裁剪结果、记录耗时 | `execute(tool, args)` |
| 6 | 控制员 | `roles/ControllerRole.java` | 驱动整个 ReAct 循环并判定终止 | `runTask(task)`、`dispatch(call)` |

装配点只有一个：`com/agent/CodingAgentCli.java`（谁依赖谁），入口 `Main` 只负责参数解析与启动。

---

## 3. 逐个角色：代码与职责

### 3.1 用户（UserRole）

**职责**：两件事，仅此而已 —— 输入任务、批准或拒绝守卫提出的确认请求。

```java
/** 读取下一条任务；输入结束（EOF）时返回 Optional.empty()。 */
public Optional<String> nextTask() throws IOException {
    String line = in.readLine();
    return line == null ? Optional.empty() : Optional.of(line.trim());
}

/** 回应守卫的确认请求：拿不到输入时按「拒绝」处理 —— 安全的默认值必须是不放行。 */
public boolean confirm(String question) {
    if (autoApprove) { /* --yes 时自动同意 */ return true; }
    ...
    return normalized.equalsIgnoreCase("y")
            || normalized.equalsIgnoreCase("yes")
            || normalized.equals("是");
}
```

**不负责**：解析任务、挑选工具、读写文件、执行命令。

**设计要点**：用户不是流程的旁观者，而是安全链条上的一环 —— 写文件与执行命令必须经他点头（`--yes` 才跳过）。
EOF 或无输入一律按拒绝处理，避免管道异常被误当成同意。

### 3.2 LLM（LlmRole）

**职责**：代表「模型这一侧的会话状态」—— 系统提示词 + 完整消息历史 + 发起 `chat` 请求。

```java
public static final String SYSTEM_PROMPT = """
        你是一个可以操作本地代码库的编程助手 Agent。
        你可以使用这些工具：
        - list_files：查看目录结构；
        - read_file：读取文件内容；
        ...
        1. 需要了解现状时先调用只读工具，不要凭记忆猜测文件内容；
        2. 修改文件用 write_file，并保证内容是完整可用的；
        3. 验证改动时用 run_command 执行构建或测试命令；
        """;

public LLMResponse ask(List<LLMClient.ToolDef> tools) throws IOException {
    return client.chat(List.copyOf(history), tools);
}

/** 顺序不能颠倒：先记录带 tool_calls 的 assistant，再记录 tool 结果。 */
public void recordToolCalls(LLMResponse response) {
    history.add(Message.assistant(response.content(), response.toolCalls()));
}

public void recordToolResult(ToolCall call, String observation) {
    history.add(Message.tool(call.id(), observation));
}
```

**不负责**：决定执行什么动作（由模型通过 `tool_calls` 决定）、执行工具。

**设计要点**：消息历史放在这里而不是控制员手里，是因为「哪种角色带哪些字段、谁与谁成对」属于**模型协议知识**
（`assistant(tool_calls)` 必须与 `tool(tool_call_id)` 成对出现），而不是流程知识。控制员只该关心「下一步做什么」。

### 3.3 注册员（RegistrarRole）

**职责**：维护「这个 Agent 会哪些工具」的花名册，对外提供两种查询。

```java
/** 标准工具集：读、写、列目录、搜代码、执行命令。 */
public static RegistrarRole standard(Path root) {
    return new RegistrarRole()
            .register(new ReadFileTool())
            .register(new WriteFileTool())
            .register(listFilesTool(root))     // 读取项目 .agentignore 规则
            .register(searchCodeTool(root))
            .register(new RunCommandTool());
}

/** 给 LLM 看的工具清单：名字 + 描述 + JSON Schema。 */
public List<LLMClient.ToolDef> toolDefinitions() {
    return tools.values().stream()
            .map(t -> new LLMClient.ToolDef(t.name(), t.description(), t.parameters()))
            .toList();
}
```

**不负责**：执行工具（执行员）、安全判断（守卫）。

**设计要点**：工具名重复会直接抛异常，避免「一个名字指向两个工具」这类极难排查的问题；
`toolDefinitions()` 是模型能正确调用的前提 —— 描述写不清，模型就不知道什么时候该用哪个工具。

### 3.4 守卫（GuardRole）

**职责**：唯一的安全裁决者，对每个工具调用给出三种结论之一。

```java
public enum Outcome { ALLOW, CONFIRM, DENY }

public Decision review(Tool tool, JsonNode arguments) {
    Decision pathDecision = reviewPaths(tool, arguments);   // ① 路径沙箱 + 受保护路径
    if (pathDecision != null) {
        return pathDecision;
    }
    return switch (tool.risk()) {                            // ② 按风险等级分流
        case READ -> Decision.allow("只读操作: " + tool.name());
        case WRITE -> Decision.confirm("写入操作: " + tool.name() + " 将修改项目文件");
        case EXECUTE -> reviewCommand(arguments);            // ③ 黑名单 / 越界路径 / 需确认
    };
}
```

裁决矩阵：

| 操作 | 结论 | 依据 |
|---|---|---|
| 读文件 / 列目录 / 搜代码 | `ALLOW` | `Tool.risk() == READ` |
| 写文件（项目内） | `CONFIRM` | `risk() == WRITE` |
| 写 `.env`、`.git/**`、`*.key` / `*.pem` | `DENY` | `PROTECTED_PREFIXES` / `PROTECTED_SUFFIXES` |
| 路径含 `..` 或绝对路径越出根目录 | `DENY` | `reviewPaths` 归一化后 `startsWith(root)` |
| 普通命令（`mvn test`、`git log` 等） | `CONFIRM` | 起进程属于高风险，交用户确认 |
| 高危命令（`rm -rf`、`git reset --hard`、`format`、`del /f`…） | `DENY` | `FORBIDDEN_FRAGMENTS` 黑名单 |
| 命令引用项目外绝对路径或上级目录 | `DENY` | `findOutsideAbsolutePath`、`..` 检查 |

**不负责**：执行任何工具。

**设计要点**：把安全判断从提示词里搬进代码，并且**只认工具的声明**（`risk()` 与 `pathArguments()`）——
新增工具时不必改守卫，只要老实声明自己的风险等级与路径参数。

**诚实的边界**：这是「黑名单 + 人工确认」的最小防线，能挡住常见误操作与明显破坏性指令，
但挡不住脚本包装、编码变形之类的绕过手法；真正的隔离要靠容器、受限用户或虚拟机。

### 3.5 执行员（ExecutorRole）

**职责**：唯一真正「动手」的角色 —— 把工具与参数变成结果。

```java
/** 执行一次工具调用，永不抛异常。 */
public ToolResult execute(Tool tool, JsonNode arguments) {
    long start = System.nanoTime();
    try {
        return truncate(tool.execute(new ToolRequest(root, arguments)));
    } catch (Exception failed) {
        return ToolResult.fail(tool.name(), describe(failed), elapsedMillis(start));
    }
}

private static String describe(Exception failed) {
    if (failed instanceof SecurityException) {
        return "安全校验失败: " + failed.getMessage();
    }
    if (failed instanceof IllegalArgumentException) {
        return "参数错误: " + failed.getMessage();
    }
    return failed.getClass().getSimpleName() + ": " + failed.getMessage();
}
```

**不负责**：安全判断（它只会在守卫放行之后被调用）。

**设计要点**：三件事在这里统一处理 —— 异常兜底（一次工具失败不该终止整个任务）、
结果裁剪（超长输出会撑爆上下文）、耗时记账（便于观测哪一步慢）。

### 3.6 控制员（ControllerRole）

**职责**：流程的驱动者，把五个角色串成一个循环，并负责终止判定。

```java
public String runTask(String task) {
    llm.beginTask(task);
    for (int step = 1; step <= maxSteps; step++) {
        LLMResponse response = llm.ask(registrar.toolDefinitions());

        if (!response.hasToolCalls()) {            // ← 唯一的分支判据
            llm.recordFinalAnswer(response.content());
            return response.content();             // 正常退出
        }
        llm.recordToolCalls(response);
        for (ToolCall call : response.toolCalls()) {
            llm.recordToolResult(call, dispatch(call));
        }
    }
    return "达到最大步数限制（" + maxSteps + "），任务未完成。";
}

/** 查表 → 守卫 → 用户确认 → 执行 → 返回 Observation 文本。 */
private String dispatch(ToolCall call) {
    Optional<Tool> found = registrar.find(call.name());           // 注册员
    if (found.isEmpty()) {
        return "未知工具: " + call.name() + "（可用工具: ...）";
    }
    GuardRole.Decision decision = guard.review(tool, args);       // 守卫
    if (!decision.allowed()) {
        return "守卫拒绝了这次工具调用 —— " + decision.reason();
    }
    if (decision.needsConfirmation() && !user.confirm(...)) {
        return "用户拒绝了这次工具调用 —— " + decision.reason();
    }
    return executor.execute(tool, args).toObservation();          // 执行员
}
```

**不负责**：读文件、发 HTTP、做安全判断、理解任务内容 —— 它只管「顺序与边界」。

---

## 4. 它们如何协作

### 4.1 一次任务的完整时序（真实运行记录）

任务：*「读取 pom.xml 取出 artifactId、version、依赖数量；写入 demo/agent-summary.md；运行 git log --oneline -1 并追加到文件末尾」*

| 步 | 角色动作 | 实际输出（节选） |
|---:|---|---|
| 1 | 控制员 → LLM：请求决策 | `[控制员] 第 1/14 步：让 LLM 决定下一步` |
| 2 | LLM 返回两个只读调用 | `read_file {"path":"pom.xml"}`、`list_files {"path":".","maxDepth":1}` |
| 3 | 守卫裁决 | `[守卫] ALLOW：只读操作: read_file` |
| 4 | 执行员执行 | `[执行员] 执行成功（1 ms）` / `（20 ms）` |
| 5 | 控制员 → LLM：回传两个 Observation | 进入第 2 步请求 |
| 6 | LLM 要求执行命令 | `run_command {"command":"git log --oneline -1"}` |
| 7 | 守卫要求确认 → 用户同意 | `[守卫] CONFIRM：将在项目根目录执行命令` → `已按 --yes 自动同意` |
| 8 | 执行员执行 | `[执行员] 执行成功（123 ms）` |
| 9 | LLM 要求写文件 | `write_file {"path":"demo/agent-summary.md", ...}` |
| 10 | 守卫确认 → 执行员执行 | `[守卫] CONFIRM：写入操作` → `[执行员] 执行成功（18 ms）` |
| 11 | LLM 自己要求读回文件核对 | `read_file {"path":"demo/agent-summary.md"}` |
| 12 | 无工具调用 | `[控制员] 模型未请求工具，任务结束（共 5 步）` |
| 13 | 输出最终回答 | 「做了什么 / 改动的文件 / 文件内容 / 验证结果」 |

两个细节值得注意：**第 11 步是模型自发复查**（核对写入内容与 `pom.xml`、`git log` 的真实值是否一致），
**落盘内容里的 artifactId、version、依赖数、提交号全部是真实值**，没有编造。

### 4.2 消息历史如何增长

```text
system(系统提示词)
user(任务)
assistant(tool_calls: call_1, call_2)      ← 控制员在派发之前先记录
tool(tool_call_id: call_1, read_file 结果)
tool(tool_call_id: call_2, list_files 结果)
assistant(tool_calls: call_3)              ← 下一轮
tool(tool_call_id: call_3, run_command 结果)
...
assistant(最终回答)                         ← 循环在此退出
```

顺序不能错：漏掉带 `tool_calls` 的 `assistant` 消息，模型就看不到自己上一轮调用了什么，服务端会直接报调用配对错误。

### 4.3 守卫的三条出口

```text
                     ┌── ALLOW   → 执行员执行
模型请求工具调用 ──► 守卫裁决 ├── CONFIRM → 用户确认 → 同意才执行
                     └── DENY    → 不执行，理由作为 Observation 回传模型
```

三条出口都只是「一条消息」，模型拿到之后仍可继续决策 —— 守卫拒绝不会让任务崩溃，
Agent 会向用户解释原因（实测：读 `../outside.txt`、执行 `rm -rf /tmp/agent-test` 都被如实拒绝并说明）。

### 4.4 谁依赖谁

```text
CodingAgentCli（装配）
   ├── UserRole        ← 输入流 + 输出流 + autoApprove
   ├── LlmRole         ← LLMClient（DeepSeekClient）
   ├── RegistrarRole   ← 5 个 Tool 实例
   ├── GuardRole       ← 项目根目录
   ├── ExecutorRole    ← 项目根目录 + 结果上限 + trace
   └── ControllerRole  ← 上面五个角色
```

依赖是单向的：控制员依赖另外五个；守卫、执行员、注册员互不依赖；用户与 LLM 谁都不依赖。
唯一的装配地点是 `CodingAgentCli`，新增角色时只需改这一处。

---

## 5. 工具清单（角色之间的能力边界）

统一接口 `Tool`：`name()` / `description()` / `parameters()`（JSON Schema）/ `risk()` / `pathArguments()` / `execute(ToolRequest)`。

| 工具 | 风险 | 参数 | 说明 |
|---|---|---|---|
| `list_files` | READ | `path`，可选 `maxDepth` | 递归列目录，遵守 `.agentignore` |
| `read_file` | READ | `path` | 读取 UTF-8 文本 |
| `search_code` | READ | `keyword`、`path` | 返回 `文件:行号: 内容` |
| `write_file` | WRITE | `path`、`content`，可选 `append` | 自动建父目录，默认覆盖 |
| `run_command` | EXECUTE | `command`，可选 `timeoutSeconds` | 在项目根目录执行；超时杀整棵进程树；输出按控制台编码解码 |

---

## 6. 效果展示（待补充运行截图）

> **使用说明**：把截图放到仓库的 `docs/images/` 目录（需要新建），
> 再把下面每条里的 `![](docs/images/xxx.png)` 换成实际文件名即可。
> 每条都已写好「建议截图内容」与「说明要点」，照着填就行。

### 6.1 启动与交互式提问

> 建议截图：`java -jar target/agent-cli-0.1.0.jar` 启动后的首屏，以及输入一句自然语言任务之后的界面。

![启动界面](docs/images/01-startup.png)

说明要点：启动信息会打印沙箱根目录与注册的工具清单；输入任务后先看到控制员的步进日志。

### 6.2 只读工具调用（读文件 / 列目录）

> 建议截图：`[守卫] ALLOW：只读操作: read_file` 与 `[执行员] 执行成功` 的日志片段。

![只读工具](docs/images/02-read-only.png)

说明要点：只读操作自动放行，不需要用户确认；每步的执行耗时也在日志里。

### 6.3 写入文件（守卫确认 + 落盘结果）

> 建议截图：`write_file` 触发 `[守卫] CONFIRM` 的确认提示，以及随后打开生成文件的画面。

![写入文件](docs/images/03-write-file.png)

说明要点：写文件前必须确认；落盘内容里的数值来自 `pom.xml` 与 `git log` 的真实输出。

### 6.4 执行命令（退出码与输出）

> 建议截图：`run_command` 执行 `git log --oneline -1`（或 `mvn -q test`）后的输出，包含「退出码: 0」。

![执行命令](docs/images/04-run-command.png)

说明要点：命令在项目根目录执行、有超时保护、超时会连子进程一起结束。

### 6.5 最终回答（多步任务的收尾）

> 建议截图：Agent 输出的「做了什么 / 改动的文件 / 验证结果」总结。

![最终回答](docs/images/05-final-answer.png)

说明要点：模型的最终回答建立在工具真实返回之上，并且会自己读回文件核对。

### 6.6 守卫拦截之一：越界路径

> 建议截图：请求读取 `../outside.txt` 时 `[守卫] DENY：路径越出项目根目录` 以及 Agent 的解释。

![守卫拦截越界路径](docs/images/06-guard-path.png)

说明要点：即使写成相对路径，只要归一化后越出项目根目录就拒绝。

### 6.7 守卫拦截之二：高危命令

> 建议截图：请求执行 `rm -rf /tmp/agent-test` 时 `[守卫] DENY：命令包含高危片段` 以及 Agent 的解释。

![守卫拦截高危命令](docs/images/07-guard-command.png)

说明要点：高危片段与项目外绝对路径双重命中；Agent 没有执行，并给出更安全的替代建议。

### 6.8 单元测试全绿

> 建议截图：`mvn clean test` 的结果行 `Tests run: 175, Failures: 0, Errors: 0` 与 `BUILD SUCCESS`。

![测试结果](docs/images/08-tests.png)

说明要点：守卫策略、执行员兜底、控制员循环都是离线用例，改动后可以立即回归。

---

## 7. 附：如何复现

```bash
# 1) 配置 Key（.env 已在 .gitignore 中，不会提交）
#    DEEPSEEK_API_KEY=你的Key

# 2) 构建
mvn clean test
mvn -DskipTests package

# 3) 交互式运行
java -jar target/agent-cli-0.1.0.jar

# 4) 单任务运行（脚本化）
java -jar target/agent-cli-0.1.0.jar --yes --task "读取 pom.xml 的 artifactId，写入 demo/id.txt，然后运行 git log --oneline -1"

# 5) 本地回退模式（不需要 Key，Lab01 行为）
java -jar target/agent-cli-0.1.0.jar --local
```

| 参数 | 作用 |
|---|---|
| `--task "..."` / `-t` | 跑完一个任务即退出 |
| `--yes` / `-y` | 自动同意守卫的确认请求（脚本化场景） |
| `--root <dir>` | 项目根目录，也是沙箱边界（默认当前目录） |
| `--max-steps N` | 单个任务的最大步数（默认 12） |
| `--local` | 强制本地回显模式 |
| `--upper` | 输出转大写（Lab01 挑战任务 3） |
