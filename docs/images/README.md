# 运行截图目录

`V4-Coding-Agent-Report.md` 第 6 节用到的截图都在这里，文件名与报告中 `![](docs/images/xxx.png)` 一一对应。

| 文件名 | 对应小节 | 实际内容 |
|---|---|---|
| `01-startup.png` | 6.1 | `mvn -q exec:java` 启动，打印 root 与五个工具，等待输入 |
| `02-read-only.png` | 6.2 | 「读取 test.txt」→ `read_file` 被 `ALLOW`（0 ms）→ 直接给出回答 `Hello agent!` |
| `03-write-file.png` | 6.3 | 「将内容改为 Hello world!」→ `write_file` 经 `CONFIRM` + `y` 放行 → 写后自查 |
| `04-run-command.png` | 6.4 | 「提交到 git」5 步命令：`git status` → `git diff` → `git add` → `git commit` → `git log`，逐次确认 |
| `05-guard-path.png` | 6.5 | 读取 `..\doc.txt` 被 `DENY`（路径越出项目根目录） |
| `06-guard-command.png` | 6.6 | `rm -rf /tmp/agent-test` 被 `DENY`（高危片段） |
| `07-tests.png` | 6.7 | `mvn clean test` → `Tests run: 175, Failures: 0, Errors: 0` + `BUILD SUCCESS` |

如需再补截图（例如单任务模式、`--local` 回退模式），按 `08-xxx.png` 命名，
并在报告 §6.8 之后照格式新增一条即可。

> 提示：截图里不要出现 `.env` 的真实 Key。终端窗口尽量调宽，避免日志折行影响阅读。
