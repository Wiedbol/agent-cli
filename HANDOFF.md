# agent-cli 任务交接文档

> 用途：把当前工程状态、设计决策、已知坑位与后续步骤完整交给另一个会话 / 另一位协作者。
> 生成时间：2026-09-18 ｜ 仓库：`E:\agent-cli` ｜ 分支：`master` ｜ HEAD：`a0e4149`

---

## 0. 接手后的前 15 分钟（建议顺序）

1. 读 [`README.md`](README.md)（工程说明与命令）、[`Lab01-Report.md`](Lab01-Report.md)、[`Lab02-Report.md`](Lab02-Report.md)（课程要求与已交付内容）。
2. 跑一次基线：`mvn clean test` —— 应为 **52 个用例通过、BUILD SUCCESS**（首次构建需联网拉依赖）。
3. 跑一次人工验收：

   ```powershell
   "hello" | java -cp target/classes com.agent.Main
   java -cp target/classes com.agent.tool.FileToolsDemo --tree
   ```

   第一次输出 `Hello, Agent!`；第二条首行应为 `== stdout encoding: GBK ==` 且中文正常。
4. **动代码前先读 §6「关键设计决策与坑位」** —— 其中编码问题已经反复踩过三次，不要再改回启发式猜测。
5. 需要 `git add/commit` 时注意：本工作区的 `.git` 对沙箱是只读的，**任何写 Git 的命令都要申请提权**（Maven 首次联网同理）。

---

## 1. 项目是什么

课程项目 **AI Coding Agent CLI** 的渐进式实现，每个 Lab 在上一版基础上增量演进：

```text
V1（Lab01）        Maven + Java 17 + CLI + JUnit + Git          ✅ 已完成
V2（Lab02）        + ListFilesTool / ReadFileTool / SearchCodeTool  ✅ 已完成
V3（Lab03，未开始） + Command Tool …                             ⏳ 待做
Lab04             把三个 Tool 抽象成统一 Tool 接口               ⏳ 待做
```

当前处于 **V2 完成、等待 Lab03 材料** 的状态。实验指导书只在本地存在（`d:\EdgeDownload\labs-lab01 1.md`、`labs-lab02 1.md`，Lab03 尚未下载）。

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
├── .gitignore                # 忽略 target/ 等
├── pom.xml                   # GAV: com.agent:agent-cli:0.1.0
├── README.md                 # 工程说明（环境/构建/运行/编码规则/Tool 签名表）
├── HANDOFF.md                # 本文档
├── Lab01-Report.md / .pdf    # 实验一报告（md 在 a0e4149 被精简过，见 §7）
├── Lab02-Report.md / .pdf    # 实验二报告（同上）
└── src/
    ├── main/java/com/agent/
    │   ├── Agent.java            # 纯逻辑：respond(String) → String
    │   ├── ConsoleStreams.java   # 控制台编码策略（stdin/stdout）
    │   ├── Main.java             # CLI：I/O 接线 + quit
    │   └── tool/
    │       ├── IgnoreRules.java    # .agentignore 解析与判定
    │       ├── ListFilesTool.java  # 递归列出 / 深度限制 / 树形输出
    │       ├── ReadFileTool.java   # 读取 UTF-8 / 分页读取
    │       ├── SearchCodeTool.java # 关键字 / 正则搜索，返回 Match 列表
    │       └── FileToolsDemo.java  # 命令行演示入口
    └── test/java/com/agent/
        ├── AgentTest.java           # 9
        ├── ConsoleStreamsTest.java  # 5
        ├── MainTest.java            # 6
        └── tool/
            ├── IgnoreRulesTest.java     # 5
            ├── ListFilesToolTest.java   # 8
            ├── ReadFileToolTest.java    # 9
            └── SearchCodeToolTest.java  # 10
```

### 关键公开 API（改签名前先看这里）

| 类 | 方法 / 成员 | 说明 |
|---|---|---|
| `Agent` | `String respond(String)` | null / 空白 / `hello`（忽略大小写与首尾空白）→ `Hello, Agent!`，其余 → `I heard: ` + **原始输入** |
| `Main` | `main(String[])`、`run(Agent, BufferedReader, PrintStream)` | `run` 是包级可见的 I/O 循环，便于单元测试 |
| `ConsoleStreams` | `charset(forced, detected, fallback)`、`stdinCharset()`、`stdoutCharset()`、`newStdinReader()`、`configureStdout()`、`ENCODING_PROPERTY="agent.encoding"` | 编码策略单一入口，见 §6.1 |
| `ListFilesTool` | `listFiles(Path)`、`listFiles(Path,int)`、`tree(Path)`、`IgnoreRules` 构造器、`fromConfig(Path)` | 返回相对路径、`/` 分隔、字典序 |
| `ReadFileTool` | `readFile(Path)`、`readFile(Path,long offset,long limit)` | 分页用 `BufferedReader` 流式，内存与 limit 相关 |
| `SearchCodeTool` | `record Match(String file,int line,String content)`、`searchCode(String,Path)`、`searchCodeRegex(Pattern,Path)`、`fromConfig(Path)` | 正则**故意不叫** `searchCode`（重载会与 `String` 版本产生 null 歧义） |
| `IgnoreRules` | `defaults()`、`load(Path)`、`parse(List<String>)`、`isIgnored(Path,Path)`、`patterns()` | 以 `/` 结尾 = 目录规则；其余 = glob（同时匹配文件名与相对路径） |

---

## 4. 常用命令

```powershell
# 构建与测试
mvn clean test                      # 52 个用例
mvn -B -DskipTests package          # 产出 target/agent-cli-0.1.0.jar（Main-Class: com.agent.Main）

# 运行 CLI（Lab01）
java -cp target/classes com.agent.Main              # 交互式，quit 退出
"hello" | java -cp target/classes com.agent.Main    # 管道
mvn -q exec:java                                    # 走 exec-maven-plugin

# 运行 File Tools Demo（Lab02）
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO   # 列文件 + 读文件 + 搜索
java -cp target/classes com.agent.tool.FileToolsDemo --tree           # 树形列出

# 需要其它编码时（一般不需要）
java '-Dagent.encoding=UTF-8' -cp target/classes com.agent.tool.FileToolsDemo --tree
```

> ⚠️ PowerShell 下 `-D` 参数**必须加引号**：`-Dagent.encoding=UTF-8` 不加引号会被拆成两个参数，报
> `ClassNotFoundException: /encoding=UTF-8`。

---

## 5. 测试基线（当前 52 个用例全绿）

| 测试类 | 用例数 | 覆盖重点 |
|---|---:|---|
| `AgentTest` | 9 | `respond` 的分支与边界（null / 空串 / 空白 / 大小写 / 原样回显 / 非 ASCII） |
| `ConsoleStreamsTest` | 5 | 编码选择：显式优先、非法名字回退、默认采用 JVM 探测值、兜底、系统属性生效 |
| `MainTest` | 6 | 用 GBK / UTF-8 **字节流**模拟控制台：中文回显、逐行、`quit`（含大小写）、EOF |
| `IgnoreRulesTest` | 5 | 目录规则 / glob / 注释 / 配置缺失回退 / 从文件加载 |
| `ListFilesToolTest` | 8 | 递归、排序、只列文件、忽略规则、深度限制、负参数、非法根目录、树形输出 |
| `ReadFileToolTest` | 9 | 整文件、UTF-8、不存在、目录、非法 UTF-8、分页（切片 / 越界 / 尾部 / 参数非法） |
| `SearchCodeToolTest` | 10 | 命中细节、子目录、同文件多处、二进制扩展名、忽略目录、不可解码文件、trim、正则、空关键字、非法目录 |

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

---

## 7. 已知问题 / 待办 / 不确定项

| # | 事项 | 状态与说明 |
|---|---|---|
| 1 | Lab01 挑战任务 3：`--upper` 参数把输出转大写 | **未实现**。会让 `Agent` 公开 API 变化，需与后续参数解析一并设计（Lab01 报告已标注） |
| 2 | Lab03（Command Tool） | 未开始，**实验材料尚未提供**（`d:\EdgeDownload` 里只有 lab01/lab02） |
| 3 | Lab04 统一 `Tool` 接口 | 未开始；Lab02 报告 §9.3 已给出预判（返回值如何文本化） |
| 4 | `SearchCodeTool` 静默跳过不可读文件 | **有意的取舍**：调用方无从得知「结果不完整」。若要改进，建议额外返回被跳过文件的统计（Lab02 报告 §9.6 有讨论） |
| 5 | 报告的 `.md` 与 `.pdf` 可能不一致 | 提交 `a0e4149 pdf report` 在生成 PDF 的同时**精简了 `Lab01-Report.md`（−68 行）/ `Lab02-Report.md`（−93 行）**。若继续用报告作为交付物，**先确认 PDF 生成方式与「以哪一版为准」**，否则后续修改会出现三处不一致 |
| 6 | 无 CI、无远程仓库 | `git remote` 为空，全部为本地提交；未配置 GitHub Actions 等 |
| 7 | `target/` 不入库 | 已由 `.gitignore` 忽略；交接时不需携带构建产物 |

---

## 8. Git 历史与提交规范

```text
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

- [ ] `git status` 干净、`git log --oneline -1` 为 `a0e4149`（或后续提交）
- [ ] `mvn clean test` → `Tests run: 52, Failures: 0, Errors: 0` + `BUILD SUCCESS`
- [ ] `java -cp target/classes com.agent.Main` → 输入 `hello` 得到 `Hello, Agent!`；输入中文**无需任何参数**即正常显示
- [ ] `java -cp target/classes com.agent.tool.FileToolsDemo --tree` → 首行 `== stdout encoding: GBK ==`，中文正常，列表里**没有** `target/`、`.git/`
- [ ] `java -cp target/classes com.agent.tool.FileToolsDemo not-exist.txt` → 抛 `NoSuchFileException`（退出码 1），不出现其它崩溃
- [ ] 已确认「报告以 md 还是 pdf 为准」（对应 §7 第 5 项）
- [ ] 若继续做 Lab03：先取到 `labs-lab03` 指导书，再按 §6 的约束增量开发，并在完成后同步更新报告

---

## 11. 沙箱 / 权限注意事项（在本机自动化会话中工作必读）

- **Maven 首次构建或新增插件必须联网**：在受限沙箱里 `mvn` 会因 `Permission denied: getsockopt` 失败，需要以提权方式运行（依赖已在本地仓库缓存后，后续构建变快）。
- **`.git` 目录对沙箱只读**：`git add/commit/amend` 等写操作会报 `could not lock config file … Permission denied`，需要提权。
- **`Start-Process ... -WindowStyle Hidden`** 用于后台进程；验证编码时用 `-RedirectStandardOutput` 捕获**原始字节**。
- **不要用 `>` / `>>` 做编码实验**（见 §6.1）。
