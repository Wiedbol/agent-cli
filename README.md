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
└── src/
    ├── main/java/com/agent/
    │   ├── Agent.java
    │   ├── Main.java
    │   └── tool/
    │       ├── FileToolsDemo.java
    │       ├── IgnoreRules.java
    │       ├── ListFilesTool.java
    │       ├── ReadFileTool.java
    │       └── SearchCodeTool.java
    └── test/java/com/agent/
        ├── AgentTest.java
        └── tool/
            ├── IgnoreRulesTest.java
            ├── ListFilesToolTest.java
            ├── ReadFileToolTest.java
            └── SearchCodeToolTest.java
```

| 类 | 职责 |
|---|---|
| `Agent` | 纯逻辑：`respond(String)` 决定「输入 → 响应」，不接触键盘与屏幕，可直接单元测试 |
| `Main` | I/O 接线：从 stdin 逐行读取，交给 `Agent`，再打印到 stdout |
| `AgentTest` | 用 JUnit 5 验证 `Agent.respond` 的各分支与边界 |
| `ListFilesTool` | 递归列出目录文件；支持最大深度与树形输出；返回 `List<String>` |
| `ReadFileTool` | 读取 UTF-8 文本文件；支持 `offset`/`limit` 分页；返回 `String` |
| `SearchCodeTool` | 搜索包含关键字/正则的行；返回 `List<Match>`（文件 + 行号 + 内容） |
| `IgnoreRules` | 解析 `.agentignore`（gitignore 风格）规则，供上述 Tool 跳过无关路径 |
| `FileToolsDemo` | 命令行演示三个 Tool 的入口 |

## 3. 构建与测试

```bash
mvn test          # 编译 + 运行单元测试，必须 BUILD SUCCESS 且 Failures: 0
mvn clean test    # 清理 target/ 后重新构建
mvn package       # 生成 target/agent-cli-0.1.0.jar（清单已配置 Main-Class）
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

结束交互式输入：macOS / Linux 按 `Ctrl + D`，Windows PowerShell 按 `Ctrl + Z` 后回车。

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

Demo 入口会把标准输出固定为 UTF-8（见 `FileToolsDemo.useUtf8Stdout()`），因为 Windows 上
JDK 19+ 的 `stdout.encoding` 默认跟随控制台代码页（简体中文环境通常是 GBK），会导致「Java 按
GBK 写、终端按 UTF-8 读」的中文乱码。固定为 UTF-8 后，Windows Terminal / PowerShell 7 /
VS Code / IDEA / macOS / Linux 都能正常显示。

若你的终端仍在使用旧代码页（未执行 `chcp 65001` 的 cmd.exe），先切换代码页再运行：

```powershell
chcp 65001
java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
```

### 6.2 忽略规则 `.agentignore`

Tool 遍历目录时按项目根目录的 `.agentignore` 跳过文件，语法与 `.gitignore` 一致：
`#` 开头是注释，`target/` 这类以 `/` 结尾的规则表示目录名（连同其内容一起跳过），
其余按 glob 同时匹配文件名与相对路径（如 `*.class`、`build/**`）。配置文件不存在时使用
`IgnoreRules.defaults()` 兜底。

## 7. 实验报告

- [Lab01-Report.md](Lab01-Report.md)：Java Project Bootstrap（V1）
- [Lab02-Report.md](Lab02-Report.md)：File Tools（V2）
