# Repair Lab

独立的 Java 17 / Maven 商店规则项目，用真实测试失败演示任务修复、恢复与失败经验。模板与控制程序位于本目录；**模型只操作由控制程序生成的外部工作区**。

## 场景与冻结验收

| 标识 | 用途 | 目标 | 初始唯一失败 |
|---|---|---|---|
| `source-shipping` | 经验来源任务 | 满10000分免运费 | `shouldShipFreeAtThreshold` |
| `eval-coupon` | 相似但不同 | 满20000分允许优惠券 | `shouldAllowCouponAtThreshold` |
| `eval-stock` | 相似但方向不同 | 库存不超过5件预警 | `shouldWarnAtStockThreshold` |
| `eval-quantity` | 不相关 | 数量必须为正整数 | `shouldRejectNegativeQuantity` |

四个场景共用16项测试：临界点、两侧值、负数/非法输入的正反例。每次只在一个方法引入缺陷；其余方法保持正确，避免无关失败混淆结果。固定验收为项目根运行 `mvn test`，报告位于默认Surefire目录。

`control/ShopRules.java` 为受控参考修复，`control/lab.py` 包含缺陷构造规则。两者仅供可信操作者访问，不能复制到模型工作区、提示词或交给模型作为工具参数。生成工作区只含初始源码、测试、pom、目标与摘要元数据；无参考修复或控制脚本。

## 环境

JDK17+、Maven、Python3.10+（仅控制程序，标准库即可）。首次使用需要正常下载示例pom依赖和插件；缓存完整后可用 `mvn -o test`。依赖缺失或编译错误不能被当作期望的测试失败。

## 初始化并运行

在agent-cli仓库目录打开PowerShell：

```powershell
$lab = (Resolve-Path './examples/repair-lab/control/lab.py').Path
$agentJar = (Resolve-Path './target/agent-cli-0.1.0.jar').Path
$workspace = 'E:/agent-demo-shop'
python $lab init --workspace $workspace --scenario source-shipping
Set-Location $workspace
mvn test
```

最后一步应失败，且只有表中声明的测试失败。`init`要求工作区尚不存在，父目录已存在，路径为规范绝对路径且位于开发者仓库之外。不会接管已有目录。

然后使用已配置API Key的Agent：

```powershell
java '-Dagent.encoding=UTF-8' -jar $agentJar --root $workspace --task '修复满10000分免运费规则，保持既有测试不变'
```

Agent可能修改文件并调用模型服务；只在你明确运行这条命令时发生。离线控制验证不调用模型。

## 重置和参考修复

```powershell
python $lab reset --workspace $workspace --scenario eval-coupon
python $lab describe
```

重置在同一绝对根目录保留项目身份，只替换声明的pom、源码、测试和场景清单。**这些文件的人工修改会被覆盖**。只允许带有匹配根目录/版本标记的示例工作区，拒绝开发者仓库、符号链接路径、错误标记；不递归删除目录、不清理`.agent`任务/经验/来源证据，也不触碰未声明的用户文件。对已存在硬链接的文件使用替换目录项方式写入，不沿硬链接截断外部文件。

工作区标记为`.repair-lab-workspace.json`，场景与初始文件/验收SHA-256为`.repair-lab-manifest.json`。模板版本为`repair-lab-1`，验收版本为`shop-rules-tests-1`，冻结pom/测试的摘要可用`describe`查看。不要手工复制标记来接管其他目录。

可信操作者可应用参考修复以单独检验验收：

```powershell
python $lab reference --workspace $workspace --scenario eval-coupon
mvn test
```

这不是Agent修复结果，也不能当成模型成功证据。参考操作要求场景清单、pom和测试摘要仍匹配，只替换业务源码。

## 独立离线验证

在开发者仓库运行：

```powershell
python -m unittest discover -s examples/repair-lab/control -p test_lab.py -v
python examples/repair-lab/control/lab.py verify
```

第一条只测试安全控制与重置，不启动Maven或模型。第二条是**显式本地集成验证**：在独立临时工作区对四个场景分别运行离线Maven，验证初始恰好一项指定断言失败、参考修复后16项全过，核对验收摘要与证据保留。共8次Maven运行；依赖缓存不完整时会失败，不会退回联网下载。正常agent-cli回归不会递归调用此验证。

请在正式对照前准备好依赖、冻结模板与经验，再由受控评测流程隔离来源答案和执行记录。本项目提供可验证缺陷，不把参考修复、离线检查或演示当作真实模型评测结果。
