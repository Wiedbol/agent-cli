# 隔离评测

已完成的真实样本及限制见 [2026-10-23 对照报告](results/2026-10-23/README.md)。

评测控制器复用管理任务、独立验收和经验匹配规则，固定运行三个评测场景，每场景开启/关闭经验各三次，共十八次。每个重复中两组相邻运行，奇偶重复交换先后，来源和试跑不计入十八次。

## 离线模拟

在仓库根执行：

```powershell
mvn -B '-Dtest=EvaluationRunnerTest,EvaluationMatrixTest' test
```

这是 **SIMULATED 离线模拟**：使用 `FakeLLMClient` 和命令替身，不请求模型、不递归启动 Maven。测试在临时目录建立真实管理任务来源，明确确认经验，再执行十八次矩阵，验证输入摘要、重置、冻结、工具越界拒绝、未知 token、异常保留和脱敏。测试通过仅证明调度与记录机制，不表示模型能修复示例，也不能作为经验收益结论。

## 真实运行准备

需要 Java 17、Maven、Python 3.9+、已缓存或可下载的示例 Maven 依赖，以及模型配置。工作根必须是通过 [Repair Lab 初始化](README.md) 创建的独立规范绝对路径，并已在该根完成来源任务、明确审核保存启用经验。试跑也在此根进行；控制器冻结前验证经验及完整来源链。不要移动来源工作根，不要复制参考答案到其中。

以下示例假设来源工作根为 `E:\agent-evaluation\workspace`，其父目录已存在。归档目录必须不存在，与工作根位于同一文件系统，且双方互不包含。初始模板集合必须放在工作根之外。控制器会将根中除身份标记之外的**全部旧内容移动到外部 source 归档**，仅用于专门创建的评测工作区。

```powershell
# 仓库根执行；导出三份初始输入，不导出参考修复。
python examples/repair-lab/control/lab.py export --output E:\agent-evaluation\initials.json
mvn -B '-DskipTests' package
$agentJar = (Resolve-Path target/agent-cli-0.1.0.jar).Path

# 显式付费入口：会调用模型，执行十八次正式任务。
java '-Dagent.encoding=UTF-8' -cp $agentJar com.agent.evaluation.EvaluationMain --real E:\agent-evaluation\workspace E:\agent-evaluation\batch-01 E:\agent-evaluation\initials.json
```

该入口固定验收命令 `mvn test`、每次十二次模型请求预算、每条命令六十秒超时；HTTP 连接/读取超时采用客户端默认的六十/一百二十秒。模型由现有 `agent.deepseek.model` / `DEEPSEEK_MODEL` 设置选择，端点沿用 `agent.deepseek.endpoint`；采样参数使用服务默认值。两组使用同一配置。密钥沿用现有加载方式，不写入评测配置。

## 隔离与记录

每次只恢复冻结的 pom、源码、测试和身份标记，新建聊天与任务状态；完成后把本轮代码、`.agent`、构建产物等移动到外部 `run-N`，下一轮不继承。旧会话、来源证据、完整经验及已有答案均在外部 `source`。关闭组不读经验文件、不收经验摘要；开启组只在模型请求中收到按现有规则选出的摘要。正式运行不提炼或新增经验。

仅允许运行冻结的验收命令。文件工具不能越出工作根；这不是操作系统沙箱，项目代码和 Maven 插件仍须可信，不能用于执行恶意样本。

| 文件 | 内容 |
|---|---|
| `config.json` | 模型、命令、预算、超时、端点和模拟标记 |
| `frozen.json` | 开始前通过来源核验的完整经验 |
| `preparation.json` | 来源/试跑任务状态与用量，独立列出 |
| `source/` | 冻结前完整现场和独立来源证据 |
| `results.json` | 按交错顺序增量原子写入的十八次结果 |
| `summary.json` | 按场景和经验开关统计状态次数 |
| `run-N.log`、`run-N/` | 脱敏后的控制台日志与各轮完整现场 |

每条结果保留状态、退出码、原因、初始文件 SHA-256、请求数、工具数、输入/输出 token、耗时和人为介入次数。任何未知请求用量使该轮 token 总数记为 `null`，不伪装成零。全程非交互，人为介入计零；需要人工操作时会暂停，不在评测中批准恢复。模型/任务异常作为 ERROR 保留并继续，暂停与未完成也保留。存储、冻结校验或归档失败会终止批次，已写入结果与现场保留供检查；当前入口不承诺中断后自动续跑。

工作区根身份不变，内容会移入外部归档；结束时只留身份标记。不要把旧证据复制回正式运行工作根。服务模型版本和默认采样可能变化，比较结果需要结合运行时间、配置与原始记录解释。
