# 演示指南：Windows 中文输出乱码

用约 90 秒展示同一份输出字节如何因为解码方式不同而变成乱码。脚本运行真实 JAR 的 `--local` 回显模式，以 Python 模拟接收端。整个演示离线完成，无需模型、API Key 或更改系统代码页。

## 课前准备

需要 JDK 17 或更高版本、Python 3.10 或更高版本，以及项目的 JAR。先在仓库根目录打开 PowerShell。如果已有 JAR，直接运行演示。缺少 JAR 时先打包一次。

```powershell
if (-not (Test-Path './target/agent-cli-0.1.0.jar')) {
    mvn -B '-DskipTests' package
}
python -B ./demo/console-encoding/replay.py
```

如果 `java` 命令指向的启动器不可用，用 `--java` 指定本机 JDK，例如 `--java 'E:/java/bin/java.exe'`。使用别处的 JAR 时可传入 `--jar 'JAR 的实际路径'`。

## 台上怎么讲

| 时间 | 操作 | 讲解 |
|---|---|---|
| 0–25 秒 | 运行脚本，指向第 1 行结果 | 「输入是‘你好，Agent’。JAR 用 GBK 正常写出字节，但接收端按 UTF-8 读取，所以中文部分乱码。ASCII 的 `I heard:` 仍可辨认。」 |
| 25–50 秒 | 对照第 1、2 行和十六进制字节 | 「第 2 行读取的是同一份字节。把解码方式改成 GBK，原中文就回来了。这把变量锁定在接收端解码。」 |
| 50–90 秒 | 指向第 3 行和 `PASS` | 「最后用 `-Dagent.encoding=UTF-8` 让 JAR 的输入输出统一使用 UTF-8，接收端也用 UTF-8。脚本校验两种编码对齐时都完整回显了原文。」 |

预期第 1 行在中文位置出现替换字符，第 2、3 行都显示 `I heard: 你好，Agent`，最后出现 `PASS`。脚本会在结果与预期不符时以非零状态退出。

## 对应到项目中的修复

早期版本曾根据「输出被 IDE 捕获」推断接收端使用 UTF-8，但实测有 IDE 仍按 GBK 读取。后续提交 `6d66a22` 改为优先使用 JVM 检测到的编码，并保留显式覆盖。当前实现把标准输入、输出和错误流的编码决策集中在 `ConsoleStreams`。本演示用当前 JAR 构造同类编码错配，范围是控制台输入输出。 [编码实现](../../src/main/java/com/agent/ConsoleStreams.java) · [编码测试](../../src/test/java/com/agent/ConsoleStreamsTest.java)
