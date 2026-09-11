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
├── .gitignore
├── pom.xml
├── README.md
└── src/
    ├── main/java/com/agent/
    │   ├── Agent.java
    │   └── Main.java
    └── test/java/com/agent/
        └── AgentTest.java
```

| 类 | 职责 |
|---|---|
| `Agent` | 纯逻辑：`respond(String)` 决定「输入 → 响应」，不接触键盘与屏幕，可直接单元测试 |
| `Main` | I/O 接线：从 stdin 逐行读取，交给 `Agent`，再打印到 stdout |
| `AgentTest` | 用 JUnit 5 验证 `Agent.respond` 的各分支与边界 |

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
