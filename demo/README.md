# 示例与离线验证

- 中文输入输出的字节级复核。 [编码示例](console-encoding/README.md)
- 修复技能的候选生成、试用、审核与模拟对照。 [修复技能操作指引](repair-skills/README.md)
- 管理修复和经验复用的历史结果。 [课程报告](COURSE_REPORT.md) · [十八次真实对照](../examples/repair-lab/results/2026-10-23/README.md)

## 离线验收

在仓库根目录执行以下命令。若本机 `java` 是启动器别名，可将 `$java` 设为 JDK 的 `java.exe` 路径。

```powershell
mvn -B '-DskipTests' package
$java = 'java'
python demo/verify_packaged.py $java (Resolve-Path target/agent-cli-0.1.0.jar).Path
python -m unittest discover -s examples/repair-lab/control -p 'test_*.py'
```

打包检查在临时目录使用本地 HTTP 替身和虚拟 Key，覆盖 UTF-8/GBK、本地模式、续聊、导出、守卫拦截及超时进程清理。
