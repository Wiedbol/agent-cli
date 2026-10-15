# 运行截图目录

把 `V4-Coding-Agent-Report.md` 第 6 节「效果展示」需要的截图放在这个目录里，文件名与文档中的占位一致即可自动生效：

| 文件名 | 对应小节 | 建议内容 |
|---|---|---|
| `01-startup.png` | 6.1 | 启动首屏 + 输入一句自然语言任务 |
| `02-read-only.png` | 6.2 | `[守卫] ALLOW：只读操作` 与 `[执行员] 执行成功` |
| `03-write-file.png` | 6.3 | `write_file` 的确认提示 + 生成的文件内容 |
| `04-run-command.png` | 6.4 | `run_command` 的输出与「退出码: 0」 |
| `05-final-answer.png` | 6.5 | Agent 的最终总结回答 |
| `06-guard-path.png` | 6.6 | 读取 `../outside.txt` 被 `[守卫] DENY` |
| `07-guard-command.png` | 6.7 | `rm -rf /tmp/agent-test` 被 `[守卫] DENY` |
| `08-tests.png` | 6.8 | `mvn clean test` → `Tests run: 175, Failures: 0, Errors: 0` |

如果用了别的文件名，直接改 `V4-Coding-Agent-Report.md` 里对应的 `![...](docs/images/xxx.png)` 即可。

> 提示：截图里不要出现 `.env` 的真实 Key。终端窗口建议先把窗口调宽，避免日志换行影响阅读。
