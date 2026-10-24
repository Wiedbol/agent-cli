# Repair Lab v2：修复技能的来源、开发与正式样例

独立版本 `repair-lab-2`。本目录不修改旧 `repair-lab-1` 模板、十八次协议或历史结果。控制程序只依赖 Python 3.10+ 标准库；业务工作根是 Java 17 / Maven 项目，固定验收命令为 `mvn test`。

## 可审阅清单

[MANIFEST.json](MANIFEST.json) 冻结各场景的业务目标、初始文件 SHA-256、pom/测试摘要、全部测试身份和预期失败。生成输入统一为 UTF-8/LF，Windows 检出换行不改变摘要。控制器运行前核对清单；更新样例需明确建立新版本，不能偷偷改写已冻结结果。

| 阶段 | 场景 | 缺陷与初始失败 |
|---|---|---|
| 来源 | `source-delivery` | 运费金额门槛把 `>=` 写成 `>`；`shouldShipAtThreshold` |
| 初始开发正例 | `dev-points` | 不同方法的积分资格门槛；`shouldQualifyAtThreshold` |
| 初始开发反例 | `dev-whitespace` | 数量解析遗漏去除两侧空白；`shouldParseWhitespace` |
| 后续反馈开发 | `feedback-product` | 乘法完成后才转 long，已发生溢出；`shouldQualifyLargeProduct`、`shouldQualifyLargerProduct`、`shouldQualifyFewerHighPriceUnits` |
| 必要开发回归 | `dev-points`、`dev-whitespace` | 保留初始开发正反例，后续修订须重新检查；不能用正式样例代替 |
| 正式相关缺陷一 | `formal-bulk` | 错用件数代替件数乘单价；`shouldDiscountAtTotalThreshold`、`shouldDiscountAboveTotalThreshold`、`shouldDiscountFewerHighValueItems` |
| 正式相关缺陷二 | `formal-remaining` | 应比较剩余额度，却比较已用额度；`shouldWarnAtRemainingThreshold`、`shouldWarnBelowRemainingThreshold` |
| 正式不适用反例 | `formal-grammar` | 数字语法错误允许前导加号；`shouldRejectPlusPrefix` |

正式样例恰好两类相关新缺陷和一类不适用反例。它们与来源的方法、表达式和失败表现不同；仅来源通过不能证明迁移有效。

乘法样例包含变化单价与零单价，剩余额度样例包含变化总额度，防止仅靠固定件数或固定已用额度阈值通过。控制器另提供三项错误捷径：`units >= 44000`、`items >= 100`、`used >= 13`，离线验证它们均被冻结断言拒绝。

两个反例均预先规定错误复用步骤：把 `value <= 0` 放宽为 `value < 0`。这样既不修复解析/语法错误，还会额外使 `shouldRejectZero` 失败。未来技能试用的使用声明须拒绝该步骤并解释解析或语法原因，实际修改保持正数量约束，再通过全部冻结测试。控制器的 `wrong-reuse` 只证明该错误步骤可被验收观察，不能代替模型的拒绝声明或证明模型成功识别限制。

## 初始化和可信控制层

模型仅可访问当轮工作根；操作者运行本目录控制器，不能把它、参考方法、完整清单或归档交给模型。来源阶段的候选生成只接收已确认经验；正式答案、场景标签和其他阶段输入不进入生成或修订材料。不要把本仓库本身用作模型实验工作根。

```powershell
$lab = (Resolve-Path './examples/repair-lab-v2/control/lab_v2.py').Path
python $lab describe
python $lab init --workspace E:/repair-v2-work --archive E:/repair-v2-control --scenario source-delivery
python $lab inspect --workspace E:/repair-v2-work --archive E:/repair-v2-control
```

两个根必须是规范绝对路径、父目录已存在、根本身不存在，位于开发仓库之外，互不包含且在同一文件系统。不会接管任何已有目录或 Git 检出。`init/reset` 必须显式指定场景；`reference/inspect` 使用归档中记录的当前场景。

当轮工作根只有 `pom.xml`、当前场景的业务源码、测试、`GOAL.md` 和稳定项目标记。标记仅含版本、项目 ID、工作根路径，不含阶段/场景标签。每个场景只有自己的方法与测试，不将其余阶段业务代码混入当前输入。完整清单和当前场景记录保存在可信控制端。

```powershell
Push-Location E:/repair-v2-work
try { mvn -o clean test } finally { Pop-Location }
```

这一步应恰好出现清单声明的测试失败；缺少依赖、编译错误、报告缺失/过期或跳过测试均不算预期的业务失败。`-o` 要求 JUnit 5.10.2、compiler 3.13.0、Surefire 3.2.5 等依赖已缓存，不自动联网下载。

## 重置、证据保留与参考修复

```powershell
python $lab reset --workspace E:/repair-v2-work --archive E:/repair-v2-control --scenario dev-points
python $lab reference --workspace E:/repair-v2-work --archive E:/repair-v2-control
```

`reset` 会覆盖已声明的业务输入。控制器先验证根、身份、冻结清单及整棵路径，再将整个旧受控根原子移动到 `archive/history/<ID>/workspace`，保留 `.agent`、构建产物、旧输入及对应清单；然后以相同项目 ID 和根路径创建当轮输入。新根不会继承历史聊天、来源证据、答案或旧构建产物。不递归删除用户目录；出现未声明用户文件、符号链接/路径逃逸、复制/迁移的身份、Git 检出或未知目录即拒绝，保留现场。

运行重置前先停止使用该根的任务/构建，并从模型工作根之外运行控制命令；在工作根内运行会在归档之前明确拒绝，避免 Windows 当前目录占用留下不必要的待处理状态。

重置中断会保留 `pending.json` 与旧归档，并阻止后续自动重放。操作者需查看待处理记录及两个根，保留证据后人工处理；首版不承诺任意中断位置的自动恢复。

`reference` 要求冻结 pom/测试未变，只替换业务源码，不沿现有硬链接截断外部文件。`wrong-reuse` 对两个反例和三个派生数量场景提供预定义错误应用/捷径控制。它们都仅供可信操作者验证，**不是 Agent 修复或技能收益证据**。

## 离线核验

```powershell
python -m unittest discover -s examples/repair-lab-v2/control -p test_lab_v2.py -v
python -m unittest discover -s examples/repair-lab/control -p 'test_*.py' -v
python $lab verify --output E:/repair-v2-proof
```

第一条只运行控制程序测试，不启动 Maven 或模型。第二条复核旧控制程序兼容。第三条要求新的规范绝对输出目录，在独立临时模型根执行 19 次离线 Maven：七场景各初始/参考两次，另加两个反例错误应用和三个错误单阈值捷径。严格检查新报告、完整测试身份、精确失败集合、无 errors/skips、退出码及冻结验收摘要；原始日志、测试 XML、输入快照与中断时部分结果保存在输出目录。所有参考修复仅为操作者控制，未调用模型。

后续试用与正式六次任务仍须独立实现受控知识提供、使用审计和版本冻结；真实模型运行需要另行批准具体请求额度。
