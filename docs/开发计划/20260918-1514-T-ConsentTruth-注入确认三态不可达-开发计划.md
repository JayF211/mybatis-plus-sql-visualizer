# T-ConsentTruth 注入确认三态不可达 — 开发计划

- 日期：2026-09-18 15:14
- 任务编号：T-ConsentTruth（P1；T-SettingsTruth / T-CaptureTruth 同族续作——前两次收敛「设置项是否接线」「捕获链路是否在工作」，这次是「确认流的结果有没有落到状态上」）
- 来源：用户报障「在设置中手动开启增强捕获，出现弹窗点击确认，但是增强捕获的开启标识没变，并且实际也没有生效」
- 性质：**功能不可达**——增强捕获的授权入口全链失效，点「同意并启用」会被记成「拒绝」。不是体验问题，是缺陷

---

## 一、现象

用户在设置页勾选「增强捕获」→ 弹出注入确认框 → 点「同意并启用」→ 两个症状：

1. **开启标识没变**：复选框自己弹回未勾选状态；
2. **实际没有生效**：`enhancedCaptureConfirmed` 没被写入，设置也没保存，agent 始终不挂载。

## 二、根因（实测）

`EnhancedCaptureConsent.ACCEPTED` 这个状态**在全仓 src/main 中从未被赋值过**：

```
$ grep -rn "EnhancedCaptureConsent.ACCEPTED" src/main/kotlin/
EnhancedCaptureConfirmDialog.kt:47:  confirmOutcome(parent) == EnhancedCaptureConsent.ACCEPTED   ← 只有比较，没有赋值
```

对话框只有一个写入点，且只服务第三动作：

```kotlin
var outcome: EnhancedCaptureConsent = EnhancedCaptureConsent.REJECTED   // 初值 = 拒绝
private val disableCaptureAction = object : DialogWrapperExitAction(…, DISABLE_EXIT_CODE) {
    override fun doAction(e) { outcome = EnhancedCaptureConsent.DISABLE_CAPTURE; super.doAction(e) }  // 唯一写入点
}
```

对话框**没有 `doOKAction()` 覆写**（同项目 `ShareDialog` / `ErrorReportDialog` / `EntitySelectionDialog` 都覆写了它，是标准做法）。于是点「同意并启用」走平台 OkAction → 直接关窗，`outcome` 停在初值 `REJECTED`：

- `confirm(parent)` = `confirmOutcome(parent) == ACCEPTED` → **恒为 false**
- `confirmOutcome(parent)` → 恒为 `REJECTED`（点第三按钮才得 `DISABLE_CAPTURE`）

两个消费点因此全废：

| 路径 | 点「同意并启用」后实际发生 |
|---|---|
| 设置页勾选（`PluginSettingsPanel.kt:811-818`） | `confirm()` 返回 false → 走 else 分支 `isSelected = false` → **复选框自己弹回**（症状 1）；`enhancedCaptureConfirmed` 未写、Apply 无内容可存（症状 2） |
| 应用启动弹窗（`AgentManager.kt:284` → `requestConsentThenAttach`） | `confirmOutcome()` 返回 `REJECTED` → 写 `enhancedCaptureRejectedAtMs` → **进 24h 冷却**，日志打「用户拒绝注入」 |

**回归来源（git 实证）**：T-PS4-01（`0fbd147`，2026-09-18 08:33）之前是

```kotlin
fun confirm(parent: Project?): Boolean = EnhancedCaptureConfirmDialog(parent).showAndGet()
```

`showAndGet()` 的布尔值本身就是「是否按了 OK」，所以同意路径一直正常。三态化改造把 `showAndGet()` 换成 `show()` + 读 `outcome`，**只接上了 `DISABLE_CAPTURE`，OK 那条线断了**。09-17 真机设置文件里的 `enhancedCaptureConfirmed=true` 正是旧实现写下的——这也是「真机昨天正常、今天不行」的真正分界。

**对上一轮结论的更正**：T-CaptureTruth 立项时我从日志读到 13:42:35 的「用户拒绝注入——进入 86400000ms 冷却」，据此判定「授权框被拒」。按本缺陷，点「同意并启用」打出的就是这行日志——用户当时点的多半就是同意（弹窗 13:42:24 出现、13:42:35 关闭，11 秒正好是读一遍话术点确认）。即上一轮根因①实为本缺陷，而非用户操作。教训：确认流的关键结论不能只靠一行日志定性，须回到对话框实现核对结果写入点。

## 三、影响面

- **增强捕获经 UI 完全不可达**：两个 `enhancedCaptureConfirmed = true` 的写入点（`PluginSettingsPanel.kt:814`、`AgentManager.kt:246`）都在本对话框之后，`ACCEPTED` 不可达 ⇒ 两个点都不可达 ⇒ agent 永不挂载。
- T-CaptureTruth 新增的「重新授权」按钮走同一条确认流，**在修好之前同样按了没用**。
- T-CaptureTruth T-CT-07 让第三按钮可见后，出现「关闭增强」能用、「同意」不能用 的怪状态。
- 唯一幸免的是 `DISABLE_CAPTURE`（第三动作）与 X 关闭（按拒绝）——两条都是"不启用"方向。

## 四、方案

### T-CN-01（P1）把 OK 路径接回三态

```kotlin
// EnhancedCaptureConfirmDialog：对齐同项目 ShareDialog / ErrorReportDialog / EntitySelectionDialog 惯例。
// public（非默认 protected）为运行期测试缝——EntitySelectionDialog 同款口径，headless 可直调。
public override fun doOKAction() {
    outcome = EnhancedCaptureConsent.ACCEPTED
    super.doOKAction()
}
```

- `outcome` 初值保持 `REJECTED`（X 关闭/取消按拒绝的既有语义，KDoc 已述，不动）。
- 不改 `confirm()` / `confirmOutcome()` 的签名与语义，两个消费点零改动。
- 不改设置页 `isSelected = false` 分支：修好后它只在真拒绝/真关闭时触发，语义正确。

### T-CN-02（P1）补测试——本次是测试盲区

现有测试**没有一条覆盖真实对话框的 OK 路径**：`AgentConsentFlowTest` 注入的是假 `consentProvider`（绕开真对话框）；T-CaptureTruth 新加的 `DialogWrapperInitOrderGuardTest` 只查声明顺序。所以这个"状态不可达"缺陷能一路走到用户手上。

- **运行期测试**（新增 `EnhancedCaptureConsentDialogTest`，`BasePlatformTestCase` 平台桶）：构造对话框 → `doOKAction()` → 断言 `outcome == ACCEPTED`。**这条用例在修复前必红**（先红后绿验证）。参考既有惯例：`EntitySelectionDialogTest:53` / `ParamImportDialogTest:26` / `SqlPreviewDialogTest:61` 都是 headless 直调 `doOKAction()`。
- **状态可达性源码守卫**（并入 `DialogWrapperInitOrderGuardTest` 或同文件新用例）：遍历 `EnhancedCaptureConsent.values()`，每个常量必须在对话框源码中存在写入点（`outcome = EnhancedCaptureConsent.X`），唯一豁免是字段初值本身。防的正是本次这种「枚举加了状态、忘了接线」——比逐个状态写断言更耐改。

### T-CN-03（P2）确认结果落地后刷新状态行

设置页确认路径直接写 `PluginSettings.getInstance().state.enhancedCaptureConfirmed`，**不经过 AgentManager 的状态广播**，因此 T-CaptureTruth T-CT-01 的五态状态行会停在旧文案（例如仍显示「未授权注入——下次启动主配置时会询问」），直到下一次 attach/掉线才刷新。修法：设置页在对话框结果处理完之后调 `refreshEnhancedStatusLabel()`（面板内既有方法，零新增机制）。

### T-CN-04（P3）文档同步

- CHANGELOG `[Unreleased]` Fixed：确认框「同意并启用」被记为拒绝（增强捕获经 UI 不可达）。
- 变更记录总册交付行（倒序插顶）。
- 本计划 §六 实施记录回填。

## 五、验证与验收

**自动化**

- 定向：新增 `EnhancedCaptureConsentDialogTest`（先红后绿）、`DialogWrapperInitOrderGuardTest`（含新守卫）、`AgentConsentFlowTest`、`AgentManagerFallbackTest`、`SettingsTruthTest`。
- 门禁：testShards 全量（口径按项目测试约定：test 前缀计数 / 0 失败）。

**真机/沙盒人工走查（不可省）**

1. 设置页勾选「增强捕获」→ 弹框 → 点「同意并启用」→ **复选框保持勾选**、状态行转「已授权，等待应用进程启动后挂载」→ Apply 保存。
2. 启动应用 → 设置页状态行转「已挂载 pid=…」，日志出现「用户同意注入」而非「用户拒绝注入」。
3. 点第三动作「关闭增强捕获并不再询问」→ 复选框回弹、总开关持久化关闭（既有语义不回归）。
4. X 关闭/取消 → 复选框回弹、写拒绝冷却（既有语义不回归）。
5. 「重新授权」按钮（T-CaptureTruth T-CT-02）此时应真正生效。

**立即可用的绕过路径（修复前）**：手工改设置文件，在 `MpSqlVisualizerSettings` 组件下加 `<option name="enhancedCaptureConfirmed" value="true" />` 并删掉 `enhancedCaptureRejectedAtMs`，重启 IDE 后勾选即可保存生效（真机路径 `%APPDATA%\JetBrains\IntelliJIdea2024.3\options\mp-sql-visualizer.xml`；沙盒为 `build/<沙盒>/…/config/options/mp-sql-visualizer.xml`）。

## 六、实施记录

实施方式：主工作树直接实施（用户指定不新建工作树），基线 master 8e04f60。

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-CN-01 | 本批 | `public override fun doOKAction()` 写 `outcome = ACCEPTED` 后调 `super.doOKAction()`；`outcome` 初值保持 REJECTED；`confirm()`/`confirmOutcome()` 签名与两个消费点零改动。**偏离**：方法声明为 `public`（平台原 protected）——为 headless 运行期测试缝，惯例同 `EntitySelectionDialog.doOKAction()`（测试侧直调 `doOKAction()` 已是项目既有形态：EntitySelectionDialogTest / ParamImportDialogTest / SqlPreviewDialogTest）。 |
| T-CN-02 | 本批 | 新增 `EnhancedCaptureConsentDialogTest`（平台桶）：①`doOKAction()` → 断言 ACCEPTED（本缺陷正面回归守卫）②初值 REJECTED（X 关闭语义不回归）。新增状态可达性守卫 `DialogWrapperInitOrderGuardTest#test_确认框每个结果态都必须存在写入点`：数据驱动遍历 `EnhancedCaptureConsent.values()`，除字段初值外每态必须存在 `outcome = EnhancedCaptureConsent.X`。**偏离**：原计划写「OK 按钮文案即同意入口」第三条运行期断言，实施时因 `getOKAction()` 为 protected（平台侧即 protected，覆写未放宽）无法从测试读取，删去该条——文案与产出态的绑定由可达性守卫 + 既有 T-CT-07 守卫覆盖，不再单独放宽平台方法可见性。 |
| T-CN-03 | 本批 | 设置页勾选处理在对话框结果落地后补 `refreshEnhancedStatusLabel()`（该路径直写全局 state、不经 AgentManager 状态广播，不补则五态状态行停在旧文案直到下次 attach/掉线）。 |
| T-CN-04 | 本批 | CHANGELOG `[Unreleased]` Fixed 一条；变更记录总册倒序插顶；本表回填。 |

**验证结果**

- **先红后绿（核心）**：临时摘除 `doOKAction` 接线 → `EnhancedCaptureConsentDialogTest.testOK动作产出ACCEPTED` 与可达性守卫两条**均红**（5 例中 2 失败）→ 还原修复版后全绿。即本缺陷若再次出现，两道守卫都会拦下。
- 定向：`EnhancedCaptureConsentDialogTest` / `DialogWrapperInitOrderGuardTest` / `AgentConsentFlowTest` / `AgentManagerFallbackTest` / `SettingsTruthTest` 全绿。
- 全量门禁 `testShards`：**4434 / 0 失败 / 0 错误 / 6 跳过**（368 套件；对比上一轮 4431，+3 例 = 本次两个运行期用例 + 一个可达性守卫用例）。
- **过程中一次假失败（已证伪，非本改动引入）**：首跑 testShards 报 8 例失败，全部集中在 testShard2 的 5 个类，形态为 `AlreadyDisposedException: Container is already disposed`（5 例）与一处 EP 注册表断言转储（2 例）。两步证伪：①5 个类定向隔离复跑全绿；②单独复跑 `testShard2` 全绿（同一份生产代码）→ 与本次生产改动无关。成因符合项目已登记的「分片重平衡顺序依赖」形态：本次新增 3 个用例改变了时长权重与分片归属（新增守卫类落在 testShard2），换了同 JVM 邻居即暴露潜在顺序依赖；叠加当时 7 个并行会话在跑构建的资源竞争。全量重跑即全绿（权重表随新 XML 更新）。
- **真机/沙盒走查留人工**：勾选→同意后复选框保持勾选、状态行转「已授权，等待应用进程启动后挂载」、启动应用后日志出现「用户同意注入」且状态行转「已挂载 pid=…」；第三动作与 X 关闭两条既有语义不回归；T-CaptureTruth 的「重新授权」按钮此时才真正可用。


## 七、非目标

- 不改确认框话术与三动作布局（T-CaptureTruth T-CT-07 刚定稿）。
- 不改 `outcome` 初值语义（X 关闭按拒绝是 2026-09-16 起的既有约定）。
- 不重构「设置页直写全局 state + AgentManager 也写」这两个写入点的双轨形态（本次只修可达性；如需收敛另立任务）。
- 不动 24h 拒绝冷却与触发面收窄（T-PS4-01 既有决策）。
- 本次在主工作树直接实施（用户指定不新建工作树）。
