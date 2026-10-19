# SQL 预览「生成测试资产」popup onChosen 同步弹窗焦点告警修复 开发计划

> 现象：runIde 真机点「生成测试资产」→ 选「JUnit 用例骨架」后，idea.log 报 `java.lang.Throwable: Showing dialogs in PopupStep.onChosen can result in focus issues. Please put the handler into BaseStep.doFinalStep or PopupStep.getFinalRunnable.`，栈顶 `PopupImplUtil.prohibitFocusEventsInHandleSelect` 拦到 `WINDOW_ACTIVATED`（新弹 dialog7 激活、opposite=dialog4 即 SQL 预览对话框）。
> 用户要求：先出开发计划文件，再实施解决。
> 工作目录：dev worktree（`mybatis-plus-sql-visualizer-dev`）。

## 一、根因定位

`SqlPreviewPanel.kt` `generateTestAsset()`（L515~560）：`BaseListPopupStep.onChosen`（L536）在返回 `FINAL_CHOICE` **之前**同步调 `JavaCodePreviewDialog(...).show()`（L547）。

平台机制：`ListPopupImpl.handleSelect` → `PopupImplUtil.prohibitFocusEventsInHandleSelect` 用 `ProhibitAWTEvents` 在选项处理期间挂起窗口激活/焦点事件；onChosen 内同步 show 的 `DialogWrapper` 立刻产生 `WINDOW_ACTIVATED`，被检测器捕获并抛提示性 Throwable 记日志。后果：popup 关闭与 dialog 激活的焦点交接不受控（典型：关 JUnit 弹窗后编辑器焦点回不来）+ 红色异常噪音。

旁证：同 popup 的 SQL 脚本分支（`saveSqlScriptAsset`）走 `executeOnPooledThread` + `invokeLater`，弹提示时 popup 处理已结束，不触发告警——问题只出现在同步弹窗的 JUnit 分支。

## 二、方案决策

**方案 A（选定）：onChosen 改返回 `doFinalStep(Runnable)`** —— 平台异常信息指定的标准出口。`doFinalStep` 是 `BaseStep` 现成方法（本地 jar 反编译核实：`public PopupStep<?> doFinalStep(Runnable)`，`BaseListPopupStep extends BaseStep` 直接继承），语义是「返回 FINAL_CHOICE + 把动作挂到 popup 完全关闭后的 final runnable」，弹窗时机移出 `prohibitFocusEventsInHandleSelect` 窗口。改动集中在 onChosen 体内，两个分支统一包装（SQL 脚本分支动作本已后台化，包 doFinalStep 只是语义对齐，不改变行为）。

**方案 B（弃）：`invokeLater { dialog.show() }`** —— 能绕过检测，但绕开的是平台事件序管控而非对齐它；popup 关闭动画与 dialog show 的时序仍不保证，且比 doFinalStep 多一层嵌套。
**方案 C（弃）：改用 `JBPopupFactory.createPopupChooserBuilder` + `setItemChosenCallback`** —— 重写整个弹窗构造，改动面大，且 callback 同样在选中路径上，非平台指定出口。

## 三、同类模式排查（全仓 onChosen 4 处）

| 位置 | onChosen 内动作 | 是否同步弹窗 | 结论 |
|------|----------------|--------------|------|
| `SqlPreviewPanel.kt:536`（本次） | Junit→`JavaCodePreviewDialog.show()`；SqlScript→后台写盘 | **是**（Junit 分支） | 修复（T-P1） |
| `SqlGutterLineMarkerProvider.kt:116`（右键菜单） | Preview→`SqlPreviewService.previewAsync`（后台转换+EDT 回调）；Recent→后台线程查记录后 `invokeLater` 弹二级 popup | 否（均后台/异步） | 不动 |
| `SqlGutterLineMarkerProvider.kt:177`（最近记录二级列表） | `LogEntryPreviewOpener.open` → `SqlPreviewService.showResult` → `SqlPreviewDialog.show()` 同步弹窗 | **是** | 修复（T-P2，防患） |
| `DataSourceSelector.kt:2627`（Runner 选择） | callback 内 `runRunner` 反射调 runner.run（发起执行，非弹窗）；错误走 onError 面板红字 | 否 | 不动 |

## 四、实施清单

| # | 改动 | 文件 | 说明 |
|---|------|------|------|
| T-P1 | `generateTestAsset` 的 `onChosen` 两个分支统一改 `return doFinalStep { ... }`：Junit 分支弹 `JavaCodePreviewDialog`，else 分支调 `saveSqlScriptAsset`；`saveSqlScriptAsset` 实现不变 | `SqlPreviewPanel.kt`（L536~555） | `doFinalStep` 为 `BaseStep` 继承方法，Kotlin 匿名对象内直接可调；返回类型 `PopupStep<*>` 不变 |
| T-P2 | `showRecentEntriesPopup` 二级列表的 `onChosen` 同样包 `doFinalStep { LogEntryPreviewOpener.open(...) }`（保留 `if (!finalChoice) return null` 悬停分支——防 M37.1 NPE 语义不变） | `SqlGutterLineMarkerProvider.kt`（L177~188） | 防患性修复，同根因不同入口（真机暂未报但同步弹 DialogWrapper，属同一违规面） |
| T-P3 | 测试：`SqlPreviewPanelTest` 补 1 用例（或新增 `TestAssetPopupStepTest`，视可测性定）：构造 `BaseListPopupStep` 匿名类同款 step，断言 `onChosen` 返回的 step `getFinalRunnable()` 非 null（即走 doFinalStep 路径而非 FINAL_CHOICE 直返） | `src/test/.../ui/` | `getFinalRunnable()` 同为 BaseStep 公开方法（javap 已核实）；step 逻辑在 `SqlPreviewPanel` 内部匿名类，若不可直构则以「extract 可测 step 工厂函数」小重构支撑测试 |

实施注意：
- `doFinalStep` 返回 `PopupStep<*>`（Kotlin 视角 `PopupStep<?>`），onChosen 签名不动。
- gutter 二级列表 onChosen 现返回类型 `PopupStep<*>?`，`doFinalStep` 返回非空，`if (!finalChoice) return null` 分支保留则签名必须保持可空——类型不冲突。
- 不改 `TestAssetGenerator`（纯函数，测试已覆盖）。

## 五、验证标准

- 全量 `test` 0 失败（基线 3531 绿 + 新增用例）。
- runIde 真机：点「生成测试资产」→ 选「JUnit 用例骨架」→ idea.log 无 `Showing dialogs in PopupStep.onChosen` 告警；JUnit 预览正常弹出、关闭后焦点回编辑器。
- SQL 脚本分支行为不变（落盘 + 行内提示）。

## 六、回滚

两个源文件小改 + 测试，`git revert` 单提交即可。

## 验收记录

实施日期 2026-09-07 15:00 ~ 16:10，dev worktree（363591b 之后），全部按方案 A 落地。

### 实施差异（相对 §四 清单）

- **T-P1 完成，追加关键发现（Kotlin 非空签名陷阱）**：`doFinalStep` 在平台 jar 里的返回类型标了 `@Nullable`（javap RuntimeInvisibleTypeAnnotations 实证——`BaseStep.doFinalStep` 与 `PopupStep.FINAL_CHOICE` 常量均为 `@Nullable`，FINAL_CHOICE 本身就是 null 哨兵，`PopupStep.<clinit>` 中 `aconst_null` 赋值）。若 onChosen 保持非空 `PopupStep<*>` 返回类型，Kotlin 会对 `doFinalStep(...)` 结果插 `Intrinsics.checkNotNullExpressionValue` 硬抛 NPE。两个文件的 onChosen 返回类型均改可空 `PopupStep<*>?`。
- **T-P1 完成，测试支撑小重构**：`generateTestAsset` 的 step 是方法内匿名类无法直测，新增 `internal fun testAssetStepForTest()` 提供同款 step（onChosen 逻辑一致，弹窗分支包 `proj != null` 防护）。未做「公共 step 工厂」抽象——两处重复可控，避免为测试改动生产结构。
- **T-P2 完成**：保留 `if (!finalChoice) return null` 悬停分支，返回类型本就可空，无陷阱。
- **T-P3 完成**：断言形态与计划略有出入——`doFinalStep` 挂完 runnable 后返回的是 FINAL_CHOICE（null），runnable 挂在 step 自身，故断言为 `onChosen == null && step.finalRunnable != null`（非计划中「返回值 finalRunnable 非 null」）。`ListPopupImpl.disposePopup`/`AbstractPopup.dispose` 反编译证实：popup 关闭后经 `IdeFocusManager.doWhenFocusSettlesDown` 执行该 runnable，即焦点稳定后才弹 dialog——正是消除告警的机制。
- 测试落地位置：并入既有 `SqlPreviewPanelTest`（新增 `testTestAssetOnChosen走DoFinalStep不出同步弹窗`，finally 中 `panel.clear()+dispose()` 释放编辑器防 leak 检查误报），未新建文件。

### 验证结果

- 全量 `test`：**3532 tests / 0 failures / 0 errors**（基线 3531 + 新增 1）。
- 遇到的非问题：一次 `cannot open system clipboard` 失败为并行会话剪贴板争用抖动（重跑即绿，与本次改动无关）。
- runIde 真机复验（无告警 + 关弹窗焦点回归）待下轮 runIde 真机验证时随 M37 批次统一走 [[runide-demo-verify-workflow]]。
