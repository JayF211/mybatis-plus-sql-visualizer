# T-RefreshDbSteal：预览刷新后内嵌 Database 控制台丢失（参数失同步 + 执行落 Database 工具窗）开发计划

> **创建日期**：2026-09-09
> **分支**：master（HEAD `22eeff7`；工作树有 T-PreviewFlash / T-LogRowNumber 在途未提交改动，涉及同文件，实施前先并行协调）
> **优先级**：P1（M36 内嵌控制台是核心交互，用户真机复现且三症状并发；与并行计划 T-ExecCopyFix 同族，建议合流实施）
> **预估工时**：5~6h（埋点 1h + 弹窗单例化与监听治理 2h + 所有权卫队 1.5h + 测试回归 1h + 真机复验 0.5h）
> **来源**：用户真机反馈（2026-09-09 14:53 截图两张）——预览弹窗点「刷新」后：① 右侧内嵌 Database 控制台（DBN 工具栏）消失，变回带行号的自研回退编辑器；② 左侧动态条件/参数改动不再同步到右侧 SQL；③ 点「执行 SQL」不在当前弹窗执行，而是在 Database 工具窗的控制台执行。不点刷新一切正常。

---

## 一、症状与定位

### 1.1 环境与版本核实

- 用户真机 IDE 2026.2.1，安装插件 **v2026.9.8**（`%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\mybatis-plus-sql-visualizer\lib\mybatis-plus-sql-visualizer-2026.9.8.jar`，安装时间 09-08 19:22）。
- 已反编译部署 jar 的 `SqlPreviewPanel.refreshView` 字节码核对：`compactMode = !formatSqlSetting() → modeButton.setText → renderSql()`，与仓库 2b9f7c8 源码完全一致——**排除版本漂移**，以下代码行号按当前工作树。

### 1.2 真机日志时间线（idea.log 轮转链，14:49:49 → 14:53:33）

| 时间 | 事件 | 来源行 |
|---|---|---|
| 14:50:05 / 14:50:25 / 14:50:51 | 预览弹窗 ×3 打开（`preview switch` + `shared console reused`） | idea.3.log |
| 14:50:55 / 14:51:02 | 执行 ×2 成功（`executeSql prepared=true`，内嵌控制台） | idea.3.log |
| 14:51:20 / 14:51:31 | 预览弹窗 ×2 再开 | idea.3.log |
| 14:51:22 / 14:51:37 / 14:52:05 / 14:52:08 | 执行 ×4 成功（内嵌） | idea.3.log / idea.2.log |
| 14:52:16 → 14:53:27 | **71 秒空窗：插件零日志**（连 Database 插件也零活动） | idea.2.log |
| 14:53:18.9 | **坏态截图**：右侧为行号 1-8 回退编辑器、无 DBN 工具栏 | 截图 mtime |
| 14:53:27.6 | 新预览弹窗打开，共享控制台重新嵌入成功 | idea.2.log |
| 14:53:27.647 | **旧弹窗（14:51:31 开的）rehost 监听 6 连抢共享控制台**：`console component released by DBN → re-host (since=116324ms)`×6——since 精确对上旧面板 14:51:31 的 lastRehostAt | idea.2.log |
| 14:53:27.872 | 新面板以首拍抢回（`since=1788936807872ms`，lastRehostAt=0 即未初始化） | idea.2.log |
| 14:53:33.5 | **好态截图**：DBN 工具栏 + 执行对号齐全 | 截图 mtime |

关键计数：用户在 14:50:05→14:53:27 连开 **6 个预览弹窗，一个都没关**（`SqlPreviewService.showResult` 每次都 `SqlPreviewDialog(project, result).show()` 新建 modeless 弹窗，SqlPreviewService.kt:1343）。每个弹窗 = 新 `SqlPreviewPanel` + 新 rehost HierarchyListener（**永不移除**，SqlPreviewPanel.kt:611-626）+ `pendingOnConsole` 静态单槽接管（DataSourceSelector.kt:430）。

### 1.3 三症状一家族：单例 Swing 组件 + 多弹窗抢占 + 静默回退黑洞

共享控制台是**全插件唯一一个** DBN 控制台 Swing 组件（`sharedConsole`/`sharedFullComponent`），同一时刻只能有一个 parent。三方在抢它：

1. **僵尸军团**：旧弹窗的 rehost 监听随组件共享而存活（代码注释「旧组件的 hierarchy 监听随组件废弃，无需显式解绑」的假设在共享场景不成立），parent==null 即抢回自己的 consoleHost——14:53:27 的 6 连抢是日志实锤；
2. **DBN 自身 dance**：`results refreshed` 期间高频 reparent（M36.5.44 立项背景），刷新引起的文档写入会触发 `SqlDialectPusher` dumb dance（沙盒 12:54 日志实证每次写入成对 dumb 任务），给抢占制造窗口；
3. **去抖黑洞**：800ms 内的释放事件**静默 return，无日志、无重试**（SqlPreviewPanel.kt:617）——设计假设「真正释放间隔必然 > 门槛，兜底不丢」，DBN dance 收尾恰好落在窗口内时该假设被证伪，控制台永久悬空。

**回退黑洞清单**（任一失手，面板无日志落入坏态）：

| 静默点 | 位置 | 后果 |
|---|---|---|
| rehost 去抖 skip | SqlPreviewPanel.kt:617 | 控制台悬空不重挂，零日志 |
| `swapResultIntoPersistentConsole ?: return false` | DataSourceSelector.kt:414 附近 | embed 半途而废，面板停留回退态 |
| `reuseSharedConsole` 六个前置 `?: return false` | DataSourceSelector.kt:310-315 | 静默走全链或静默终止 |
| `prepareDefaultConsole` 两个早退 | SqlPreviewPanel.kt:489-490 | 不发起准备，零日志 |
| `showSql → createEditor` 回退 | SqlPreviewPanel.kt:1225-1229 | 行号编辑器替换 consoleHost，**零日志、带栈不可考** |

**坏态截图鉴定**：行号 1-8 + 拼写检查波浪线是 `createEditor` 自研编辑器独有特征（`settings.isLineNumbersShown = true`，SqlPreviewPanel.kt:1305；DBN 控制台编辑器无行号——好态截图对照），即坏态 = `showSql` 走到 `createEditor` 回退分支，成立条件 `nativeEditorDoc == null && editor == null`。精确调用栈现有日志不可考（createEditor 无埋点），F1 埋点后真机一锤定音——这不阻塞修复：无论从哪条黑洞落入回退态，下列卫队都能兜住。

**三症状对应**：控制台被抢走/悬空 → 症状①；`showSql` 的 `nativeDoc.setText` 写进已被偷走的控制台文档（落在别的弹窗里，本面板不可见）→ 症状②；`SqlExecutor.isPrepared()` 是全局静态，控制台被抢后 `executePrepared` 把 SQL 执行到**当前不归本面板所有**的控制台（或状态失效走全链 → Database 工具窗开控制台执行）→ 症状③「在 IDEA 控制台执行」。**刷新按钮的角色**：它是唯一会在共享控制台文档上做 `WriteCommandAction(setText)` 的常规按钮（触发 DBN dumb dance 制造抢占窗口），且自身零日志——与空窗期零日志吻合；「不点刷新没事」是概率而非豁免，results refreshed dance 同样能触发。

---

## 二、修复方案

### 2.1 F1：关键路径埋点（先行，诊断收口）

1. `createEditor` 入口：`LOG.info("[M36] createEditor fallback: nativeDoc=${nativeEditorDoc != null}, editor=${editor != null}, embedded=${embeddedConsole != null}, current=${current != null}", Throwable().stackTrace.take(8).joinToString("\n    at "))`——坏态成因一锤定音。
2. `showSql` 三分支各一行 debug（native 写入 / 自研编辑器写入 / createEditor 回退）。
3. `refreshView` 入口一行 info（含四态快照，同上）。
4. rehost 去抖 **skip 分支补 info**（现静默 return 是最大黑洞，SqlPreviewPanel.kt:617）。
5. `removeNotify` / `releaseEditor` 各一行 info（带 identityHashCode）。

### 2.2 F2：预览弹窗单例化（治本——消灭僵尸军团）

`SqlPreviewService.showResult`（:1334-1343）改为持 project 级弹窗引用：

- **默认路线 B（止血，3 行级改动）**：已开弹窗先 `dispose()` 再开新的。时序保证：旧面板 dispose（detachConsole + 监听随 Disposer 树销毁）→ 新弹窗 embed 干净接管。注意 dispose 走 DialogWrapper 生命周期，Disposer 树已注册面板（SqlPreviewDialog.kt:36-38），面板 `dispose()` 只置 `previewDisposed` + `Disposer.dispose(this)`，需在 F3 中把 rehost 监听移挂到面板 Disposable。
- **路线 A（后续可选）**：不关不新建，旧弹窗 `panel.showResult(newResult)` 面板复用（showResult 本身可重入）。动弹窗尺寸记忆与 `sourceLocation` 注入，风险中，本期不做，单列附录。

选 B 的理由：僵尸问题的根是「多弹窗存活」，B 一行语义消灭它；A 的收益（保滚动态）用户未提诉求。

### 2.3 F3：rehost 监听生命周期治理 + 去抖兜底

1. `attachRehostListener` 保存监听器引用到字段；`detachConsole()` 与面板 `dispose()` 中 `removeHierarchyListener`——僵尸监听根治。
2. 去抖 skip 不再静默：记 `pendingRehostCheck = true` 并 `AppExecutorUtil`/`invokeLater(timeout)` 调度**单次延迟复查**（800ms 后若仍 `parent == null && component === embeddedConsole` 且距上次重挂 > 门槛 → 幂等重挂 + 日志）。循环风险与现有重挂同界（同一去抖门槛约束），加日志后可观测。

### 2.4 F4：面板所有权卫队（三症状直修）

新增私有判定 `consoleAttachedToMe(): Boolean = embeddedConsole != null && consoleHost.isAncestorOf(embeddedConsole)`：

1. **`showSql` native 写入分支前置卫队**：`consoleAttachedToMe()` 为 false 时不写失踪文档——改走 `recoverConsole()`（用当前 SQL 重新 `prepareConsole`，onConsole 回调 embedConsole，幂等；执行中先写自研回退编辑器保显示）。治症状②（参数失同步 = 写进别人的控制台）。
2. **`executeSql` 前置卫队**：`SqlExecutor.isPrepared()` 为 true 但 `consoleAttachedToMe()` 为 false 时，不走 `executePrepared`（会把 SQL 执行到别的弹窗/工具窗的控制台），先 `recoverConsole()` 待 `onConsole` 回调后继续既有 `executePrepared` 链。治症状③。
3. **`createEditor` 回退分支卫队**：`isPrepared() == true` 但本面板未嵌时，优先重嵌而非落入回退编辑器（回退是 UX 悬崖：无 DBN 工具栏、形态突变）。治症状①的残留路径。

### 2.5 F5：与 T-ExecCopyFix 合流

并行计划 T-ExecCopyFix（20260909-1447）的 F2a「createTempPsiFile 失败可见」与本计划 F1 同向且同文件（DataSourceSelector.kt），实施时合并为一批提交，避免两轮回归。其 R1（262 慢操作硬抛）结论直接复用。

### 2.6 API 权威核实清单（铁律：本地 jar 反编译为准）

- `JComponent.isAncestorOf`：JDK 标准_api，无需核。
- `HierarchyListener` 移除时机与 Disposer 树注册：对照 SqlPreviewPanel 现有 `dispose()`（:2228-2234）与 `Disposer.register(disposable, panel)`（SqlPreviewDialog.kt:37）。
- `DialogWrapper.dispose()` 与 `Disposer.dispose(panel)` 级联顺序：以平台源码/沙盒日志核实后再动 F2（本计划唯一的平台行为疑点）。

---

## 三、实施顺序

1. F1 埋点（独立提交，先行——真机日志先跑起来）；
2. F3 监听治理 + 去抖兜底（SqlPreviewPanel 单文件）；
3. F4 所有权卫队（SqlPreviewPanel + SqlExecutor/DataSourceSelector 各一小口）；
4. F2 弹窗单例化（SqlPreviewService + SqlPreviewDialog）；
5. F5 与 T-ExecCopyFix 合流提交、全量回归。

---

## 四、测试与验收

### 4.1 单测（先测后改）

- `rehostConsoleForTest` 已有测试缝：补「去抖 skip → 延迟复查重挂」纯逻辑用例（时间源注入）。
- `consoleAttachedToMe` / `showSql` 卫队：面板级测试仿 `SqlPreviewPanelRendererUiTest` 惯例（embeddedConsole 指向未挂入 consoleHost 的组件 → 断言不写 nativeDoc、走 recover 分支）。
- 弹窗单例化：`SqlPreviewService` 层断言二次 showResult 时旧 dialog disposed（仿 SqlPreviewDialog.panelForTest 访问器）。

### 4.2 全量回归

基线 3153/0/6（master 22eeff7）。K2J 陈旧输出目录与回归假失败两类坑照 [[pitfall-regression-false-failure-flakes]] 预防。

### 4.3 runIde/真机人工验收

1. 连开 3 个预览（不手动关）→ 桌面上只存在最新一个预览弹窗，旧弹窗自动关闭；
2. 新弹窗内点「刷新」×5 → DBN 工具栏不消失、无行号编辑器回退；
3. 动态条件勾选/参数输入 → 右侧 SQL 实时同步；
4. 「执行 SQL」→ 结果出现在**当前弹窗内嵌控制台**，Database 工具窗不被拉起；
5. idea.log 出现：刷新入口日志、零 `createEditor fallback`（或出现时带完整栈）、rehost skip 有日志且兜底重挂成功、零「旧面板抢回」（单例化后无旧面板）。

---

## 五、风险与回滚

- F2 动 `showResult` 公共入口（9 处调用方全部经过）：影响面集中但语义单一（关旧开新），回滚 = revert 单提交。
- F3 去抖兜底若与 DBN dance 互搏（理论循环）：同一 800ms 门槛约束 + 日志可观测，M36.5.44 的 2753 次刷屏教训已被该门槛压制，兜底不放宽门槛。
- F4 卫队误判（控制台短暂 reparent 期被判失联）：`recoverConsole` 幂等且 embedConsole 同实例防抖（M36.5.47）吸收重复回调，最坏表现 = 一次多余重挂。
- 工作树在途改动冲突：实施前 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check`，T-PreviewFlash/T-LogRowNumber 同文件段落先对表。

## 六、文档同步

- `docs/变更记录.md` 顶插一行（注意表格行误合并坑，awk 字段数校验）；
- CHANGELOG + 发行说明（若赶发行窗口）；
- 本计划回填「真机复验结论」小节：F1 埋点抓到的 createEditor 实际调用栈定性回填，未决点销项。

---

## 七、实施记录（2026-09-09 回填）

按「F1→F3→F4→F2」顺序完成，全量回归 3188/0/6 绿（基线已含同日 T-LogRowNumber c83fb56 与
T-ExecCopyFix 642d73b 两笔合入，非本计划引起的基线增长属预期）。runIde/真机人工验收（§4.3
清单）待用户复测，本记录以单测与日志埋点交付为界。

### 7.1 落地明细（与 §2 方案的偏差）

- **F3 去抖复查改用单发 Swing Timer**（`REHOST_RETRY_DELAY_MS=800`，flashCopied 同款范式），
  而非计划的 invokeLater/timeout：invokeLater 无延迟，复查会永远落在去抖窗口内被自身的
  `since` 复查拦下，等于从未重试；Timer 到期点距上次重挂必然越过门槛。
- **F2 dispose 可见性**：`DialogWrapper.dispose` 平台侧 protected，新增
  `SqlPreviewDialog.doDispose()` 同类内 public 包装（语义与用户点关闭一致），单例化路径经此
  关旧弹窗；直接调 dispose 编译不过，这是 §2.6 平台行为疑点的落地答案。
- **F4 executeSql 卫队**：失联时先 `recoverConsole`，若恢复未同步落地（准备链异步回调未回）
  则放弃 `executePrepared` 落 `SqlExecutor.execute` 全链兜底（全链自身会重嵌），比计划原文
  多一层防打在别人控制台上的保险。
- **F4 recoverConsole 复用 prepareDefaultConsole**：准备链以回退编辑器文本发起，
  复用路径会同步 SQL 进控制台文档并经 onConsole 幂等重嵌，与计划语义等价、少一条并行链。

### 7.2 新增文件与缝

- `RefreshDbStealTest`（8 用例）：监听解绑三态（detach/换组件/dispose）、归属矩阵（嵌入/
  被收养/完全释放/重挂恢复）、去抖复查兜底（EDT 泵轮询仿 CopyFeedbackUiTest）、弹窗单例化
  与关闭幂等（面板 clear 收尾防 EditorImpl 泄漏——两个用例各踩一次后修得的惯例）。
- 测试缝：`detachConsoleForTest` / `consoleAttachedToMeForTest` /
  `rehostListenerAttachedForTest`（组件 hierarchyListeners 实测）、
  `SqlPreviewService.activePreviewDialogForTest`。

### 7.3 遗留

- 真机复验（§4.3 五条）与 createEditor 实际调用栈定性：待用户真机刷新复现后由 F1 埋点回填，
  本计划不据此再改码（卫队已覆盖全部静默分支）。
- `.http` 文件占控制台致零 SQL 入队的复测陷阱（[[branch-dev-to-master-merge-probe]]）在
  真机复验时注意避开。
