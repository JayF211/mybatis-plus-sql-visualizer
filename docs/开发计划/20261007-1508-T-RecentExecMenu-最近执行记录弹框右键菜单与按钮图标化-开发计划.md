# 开发计划：T-RecentExecMenu——「最近执行记录」弹框右键菜单复用 + 编辑器按钮图标化

- 编号占用：O-119 / R65 / D89~D92 / V119（落库前先 git 实测是否被并行会话占用）
- 日期：2026-10-07
- 状态：**待用户确认**（用户令：复制时间戳也要、其余按推荐、去掉「查看原文」并把两枚按钮改成日志转 SQL 下方那两枚的图标形态）
- 实施方式：master 直改（用户令「在主分支实施」的同一口径；靠显式路径暂存避开并行会话在改的文件）

---

## 一、需求（用户原话要点）

1. 「sql日志页面的右键功能是不是也可以复用到这个弹框上」→ 弹框列表加右键菜单，**复制时间戳也要**，其余按推荐范围（见 §八 决策记录）。
2. 「查看原文和复制按钮改下，用日志转sql下方的两个按钮图标」→ 两枚按钮由 `JButton` 改为图标动作（形制照抄日志转 SQL 结果区工具栏）。
3. 「默认就是格式化的 不要查看原文功能了」→ **删除「查看原文」切换**，格式化恒为默认显示。

## 二、现状分析

### 2.1 日志页右键十项与其实现形态（SqlLogPanel.kt:1804 `showContextMenu`）

| 菜单项 | 实现类 | 依赖面 |
| --- | --- | --- |
| 复制 SQL | `CopySqlAction` | `batchCopySql`（**文件级 internal，可直接复用**） |
| 复制时间戳 | `CopyTimestampAction` | `batchCopyTimestamps`（文件级 internal；受时间戳**显示开关**门禁——`clip` 为空则置灰） |
| 删除 | `DeleteSqlAction` | `manager.entries.removeEntry`（改全局日志缓冲） |
| 屏蔽此 SQL | `SuppressSqlAction` | `SqlSuppressRuleManager.addRule`（**公开**）+ 面板私有 balloon + `refreshList()` |
| 发送到 SQL → Java | `SendToSqlToJavaAction` | **面板私有** `sqlToJavaPanel` / `tabs` |
| 发送到日志转 SQL | `SendToLogToSqlAction` | **面板私有** `logToSqlPanel` / `tabs` |
| 跳转到 Java | `JumpToSourceAction` | **面板私有** `jumpToSource(entry)`（`HistorySqlLogPanel.kt:718` 有**第二份**同名实现） |
| 跳转到 Mapper XML | `JumpToMapperXmlAction` | **面板私有** `resolveEntryXmlJump(entry)`（底层 `MapperXmlLocator` 是共享件） |
| 方法级聚焦 | `FocusCallerMethodAction` | 聚焦页签栏（跳走页签） |
| 按请求聚焦 | `FocusRequestAction` | 聚焦页签栏（跳走页签） |

动作全是 `private inner class`，弹框**不能直接实例化复用**；下层可复用件与面板私有胶水并存（上表加粗区分）。

### 2.2 弹框现状（T-RecentExecDialog 一/二段已交付）

列表 `JBList<LogSqlEntry>`（单选）复用 `LogEntryListCellRenderer`；编辑器上方两枚 `JButton`（「查看原文/格式化」切换 + 「复制 SQL」）；底部 `DialogWrapper` 的「打开预览/关闭」；编辑器默认格式化（`SqlFormatter.format(withTerminator(restoredSql))`）＋「查看原文」可切回原文。

### 2.3 按钮形态照抄对象（本次的视觉基准）

日志转 SQL 的**结果区工具栏**（`LogToSqlPanel.buildResultToolbar`，LogToSqlPanel.kt:117）为 `DefaultActionGroup` + `ActionManager.createActionToolbar(...)`，其头两枚：

| 按钮 | 实现类 | 图标 | 文案键 |
| --- | --- | --- | --- |
| 复制 | `CopyAction` | `AllIcons.Actions.Copy` | `logToSql.panel.copy` |
| 格式（压缩/多行切换） | `ToggleFormatAction` | `AllIcons.Actions.ReformatCode` | `logToSql.panel.toggleFormat` |

两枚均为 `AnAction.update` 内设图标+文案、`actionPerformed` 落动作；复制走 `UiTextClipboard.copy` + 状态行反馈——与弹框现有实现同源，改造只是**换外壳形态**（JButton → ActionToolbar 图标动作）。

## 三、方案设计

### 3.1 弹框列表右键菜单（六件）

组装照抄日志页同款：`ActionManager.getInstance().createActionPopupMenu("mpvs.recent.exec.list", group)` + `setTargetComponent(entryList)`；`showContextMenu` 的命中行解析同样照抄（`list.locationToIndex`）。菜单项与 `update` 规则：

| # | 菜单项 | 动作落点 | update 规则 |
| --- | --- | --- | --- |
| 1 | 屏蔽此 SQL | `SqlSuppressRuleManager.addRule(entry.restoredSql)`；成功→**从弹框列表即刻移除该行**（见 §3.4）+ balloon 附「管理屏蔽规则…」直达 | 恒显示；达上限→balloon 提示（文案复用 `log.suppress.limit`） |
| 2 | 跳转到 Java | 共享件 `LogEntryJumpOps.jumpToSource(project, entry)`（§3.2） | 恒显示（无位置时该函数内部弹引导/toast，与日志页一致） |
| 3 | 跳转到 Mapper XML | `LogEntryJumpOps.resolveEntryXmlJump(project, entry)` → `OpenFileDescriptor.navigate(true)` | **定位不到即隐藏**（`isEnabledAndVisible = resolved != null`，与日志页同规则） |
| 4 | 发送到 SQL → Java | 经送页签通道（§3.3）：`entry.restoredSql` → `SqlToJavaPanel.setInputSql` + 选中该页签 | 恒显示 |
| 5 | 发送到日志转 SQL | 送页签通道：`LogToSqlPanel.loadFromEntry(entry)` + 选中该页签 | 恒显示 |
| 6 | 复制时间戳 | `batchCopyTimestamps(listOf(entry))` → `UiTextClipboard.copy` | **空即置灰**（`presentation.isEnabled = clip.isNotEmpty()`，镜像 `CopyTimestampAction.update`） |

文案：复用日志页既有键（`log.panel.copyTimestamp` / `log.panel.jumpToJava` / `log.panel.jumpToXml` / `log.panel.sendToReverse` / `logToSql.sendTo` / `log.suppress.action`），不造新键——两处菜单同文案是同一条能力的物理体现。

明确**不做**（含理由）：删除（弹框是浏览视图，删全局日志条目属越权副作用）、方法级聚焦（弹框锚点即方法位置，重复）、按请求聚焦（跳走页签）、复制 SQL（按钮已有且口径更优：所见即复制）。

### 3.2 跳转函数收口共享（消除既有二重）

新增 `log/LogEntryJumpOps.kt`（object，纯转发 + 平台导航）：

- `fun jumpToSource(project, entry)`：把 `SqlLogPanel.jumpToSource`（SqlLogPanel.kt:1748）整体搬迁，含 `resolveEntryJump` 分流（Precise/MapperXml/NoSource/NotFound/MissingSource）与既有 toast/引导调用；
- `fun resolveEntryXmlJump(project, entry): LogJumpTarget.MapperXml?`：搬迁 SqlLogPanel.kt:1735。

三个调用方改为委托：`SqlLogPanel`、`HistorySqlLogPanel`（其私有同名实现一并收编）、`RecentExecutionsDialog`。**行为红线**：搬迁为纯搬家，日志页既有 toast 文案、引导弹窗、导航语义逐字不变（两面板既有跳转测试即回归闸门）。

### 3.3 送页签通道（弹框 → 工具窗页签）

`sqlToJavaPanel` / `logToSqlPanel` 是 `SqlLogPanel` 的私有字段（SqlLogPanel.kt:375-377），弹框无法直达；照抄仓内既有回调注入惯例（`SqlLogManager.onAutoShowRequest`，SqlLogManager.kt:162）：

- `SqlLogManager` 增 `internal var onOpenEntryInTab: ((LogSqlEntry, LogEntryTab) -> Unit)? = null` 与 `enum class LogEntryTab { LOG_TO_SQL, SQL_TO_JAVA }`；
- `SqlLogPanel.init` 注册（`setInputSql` / `loadFromEntry` + `tabs.selectedComponent`，与现有两个 Send 动作逐字同体）；`dispose` 注销（置 null）；
- 弹框动作调用：通道为空（面板未曾创建）时按「面板未就绪」轻提示降级，不静默（对齐 `showJumpToast` 的「不静默」约定）。

### 3.4 屏蔽动作在弹框的语义（D92，推荐 A）

弹框列表是构造期快照。推荐 **A**：抑制成功后**即刻从弹框列表移除该行**（只动弹框本地模型，不重查日志缓冲）+ 状态行/balloon 反馈；空列表时状态行回到空态文案。理由=与日志页「存量行立即消失」的最强反馈一致，且避免「balloon 说已屏蔽、行还杵在那」的自相矛盾。备选 B：保留该行（快照语义），仅 balloon 反馈。

### 3.5 编辑器按钮图标化 + 删除「查看原文」

- 删除 `showFormatted` 开关与 `toggleFormatDisplay` 相关链路（`updateEditorFromSelection` 恒走格式化分支，格式化失败降级原文的容错保留）；bundle 键 `log.recent.dialog.showOriginal` 删除。
- 两枚 `JButton` → `DefaultActionGroup(ReformatAction(), CopyAction())` + `ActionManager.createActionToolbar(TOOLBAR_ID, group, true)`（`targetComponent` = 对话框；横排 `FlowLayout(LEFT,4,2)` 装在编辑器上方，位置不变）：

| 动作 | 图标 | 行为 |
| --- | --- | --- |
| 格式化 | `AllIcons.Actions.ReformatCode` | 对当前条目**重新格式化**（幂等）；主要价值=格式化降级为原文后的手动重试与观感一致性 |
| 复制 | `AllIcons.Actions.Copy` | 复制当前编辑器显示文本（`UiTextClipboard`）＋状态行 `dialog.copy.done`（现状不变） |

- 文案键改挂 tooltip（ActionToolbar 图标按钮的 `presentation.text` 即 tooltip）：`log.recent.dialog.format=格式化`（新）、`log.recent.dialog.copy=复制 SQL`（已有，复用）；`showOriginal` 键删除。
- 无选中（空态）时两动作 `isEnabled=false`（镜像现按钮禁用逻辑）。

### 3.6 不变项（防回归）

单选保持（D90 按推荐）；「打开预览/关闭」主按钮与双击；尺寸/位置记忆；条目装载即格式化；`LogEntryListCellRenderer` 复用；锚点快照校验与后台匹配链；`EntrySourceMatcher.MAX_RESULTS=20`。

## 四、改动清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `log/LogEntryJumpOps.kt` | **新增**：`jumpToSource` / `resolveEntryXmlJump` 收口（自 SqlLogPanel 搬迁） |
| 2 | `log/SqlLogPanel.kt` | 右键菜单动作改委托共享件；`init` 注册送页签回调、`dispose` 注销 |
| 3 | `log/HistorySqlLogPanel.kt` | 私有 `jumpToSource` 改委托共享件（消除二重） |
| 4 | `log/SqlLogManager.kt` | 增 `LogEntryTab` 枚举 + `onOpenEntryInTab` 回调字段 |
| 5 | `log/RecentExecutionsDialog.kt` | 右键菜单（六件）+ 按钮图标化 + 删「查看原文」链路 |
| 6 | `MpSqlVisualizerBundle{,_zh}.properties` | 新键（格式化 tooltip、菜单未就绪提示）；删 `log.recent.dialog.showOriginal` |
| 7 | `test/.../log/RecentExecutionsDialogTest.kt` | 补例（菜单在场/置灰、图标动作、无「查看原文」） |
| 8 | `test/.../log/LogEntryJumpOpsTest.kt` | **新增**（若既有跳转测试已覆盖等价分支则改挂新入口，防三处漂移） |
| 9 | CHANGELOG + 本计划实施记录 | 随实施同步 |

## 五、i18n

- 新增：`log.recent.dialog.format=格式化 / Format`；`log.recent.dialog.menuPanelNotReady=工具窗口未打开，无法发送 / Tool window is not open yet`。
- 删除：`log.recent.dialog.showOriginal`（zh/en 两处）。
- 复用既有：`log.panel.copyTimestamp`、`log.panel.jumpToJava`、`log.panel.jumpToXml`、`log.panel.sendToReverse`、`logToSql.sendTo`、`log.suppress.action`、`log.suppress.limit`、`dialog.copy.done`、`log.recent.dialog.copy`。

## 六、测试与验证

1. **定向（新例，RecentExecutionsDialogTest）**：①菜单动作六件在场（按 group 遍历断言 action 类/文案，不驱动真实弹窗）；②跳转 XML 不可解析时该项隐藏/置灰（喂无 `mapperStatement` 的条目）；③复制时间戳受显示开关门禁（关开关→置灰）；④「格式化」图标动作幂等（连点两次编辑器文本不变）；⑤`showOriginal` 键已无引用（键删除后 `MpSqlVisualizerBundle.message` 调用面 grep 为 0）。
2. **送页签通道**：`SqlLogPanel` 未创建时调用→轻提示、不抛（headless 断言状态行/通知分支）；已注册回调时→回调收到 `(entry, tab)`（用测试缝注入录制）。
3. **跳转共享提取的回归闸门**：既有 `SqlLogPanel` / `HistorySqlLogPanel` 跳转测试（含 `jumpToSourceForTest` 缝）全绿＝纯搬家证明；**反向验证**：临时改共享件一分支（如把 NotFound 分支改成静默）→ 对应用例必红，贴两次读数。
4. **反向验证（守卫纪律）**：菜单置灰规则摘掉 `clip.isNotEmpty()` → 置灰用例必红；渲染器复用钉断言保持。
5. **冒烟**：`testFast`（纯逻辑快桶）；本任务触达 log 包 UI → 定向批含 `SqlLogPanelRendererUiTest` 与两个跳转测试类。
6. **真机走查（V119）**：右键六项逐项走一遍（含「屏蔽后行消失」「时间戳复制」「发送到两个页签并落到正确 tab」）；两枚图标按钮观感与日志转 SQL 对齐；格式化无「查看原文」。
7. **门禁口径**：动 `SqlLogPanel` 结构（回调注册）与 log 包共享件——按三档分层跑定向 + testFast；**不**触发 verifyGate/buildPlugin（无 EP/依赖/平台 API 面变化）。**注意**：本轮实施时用户真机在跑（runIde 沙盒 + demo 应用），测试须带降足迹参数跑（见项目记忆 R17：`-PtestHeapSize=384m -PtestTieredStop=true -PtestCiCount=2 -PtestGcThreads=2`）。

## 七、待验证前提（实施者先证伪再动手）

1. **「日志转 SQL 下方两个按钮」的指认**：本文按**结果区工具栏头两枚**理解——`CopyAction`（`AllIcons.Actions.Copy`）与 `ToggleFormatAction`（`AllIcons.Actions.ReformatCode`）。若用户所指是结果区后两枚（Execute/GC）或输入区两枚（Execute/GC），图标方案随之改。**实施前一句话确认即可**（真机截图比对最直接）。
2. **格式化按钮在「默认已格式化」下是否仍有价值**：本文按「幂等重排 + 降级重试」保留；若实测觉得是死按钮，可在走查后撤掉（撤掉则与「两枚按钮」需求冲突，需用户点头）。
3. **送页签从弹框触发后的观感**：工具窗若未打开，`setInputSql` 后切页签需要工具窗可见（`SqlLogManager.onAutoShowRequest` 只 show 不 activate 的既有语义）——是否要顺带 show 工具窗，走查后定；首版按「通道为空才提示，通道存在即发」实现。
4. **共享跳转搬迁的等价性**：`SqlLogPanel.jumpToSource` 与 `HistorySqlLogPanel.jumpToSource` 是否**逐字同体**（若历史页有细微差异，搬迁须按并集保留差异分支，不得静默取一侧）。**未验证**。
5. **抑制后行移除（D92=A）在弹框的连带**：移空后主按钮/复制/格式化的禁用联动（现由 `syncSelectionScopedControls` 统一收口，确认覆盖新路径）。

## 八、决策记录

- **D89 菜单范围 = 六件**（用户 20261007 令：推荐五件 + 复制时间戳）；明确不做删除/方法级聚焦/按请求聚焦/复制 SQL（理由见 §3.1）。
- **D90 列表选择模式 = 保持单选**（按推荐；批量复制/批量屏蔽留在日志页，弹框不重复建设）。
- **D91 编辑器按钮 = 图标化并删除「查看原文」**（用户令）：默认格式化不可切回原文；「格式化」= 幂等重排 + 降级重试。
- **D92 屏蔽后行为 = A 即刻移除该行**（推荐；空态回退由状态行承载）。若选 B（保留快照行），实施时改一处即可。

## 九、实施步骤（确认后执行）

1. 占号实测（O-119/R65/D89~D92/V119）与 master 工作树状态核对（并行会话在途文件清单，实施只碰本任务文件）。
2. §3.2 共享跳转提取 + 两面板委托 → 先跑两面板既有跳转测试（纯搬家回归闸门）。
3. §3.3 送页签通道（SqlLogManager 回调 + SqlLogPanel 注册/注销）。
4. §3.1 弹框右键菜单六件 + §3.4 抑制后行移除。
5. §3.5 按钮图标化 + 删「查看原文」链路与键。
6. 定向测试（新例）+ 反向验证两次读数 + testFast 冒烟（带降足迹参数）。
7. 达标即提交（共享件重构与弹框功能可分两笔）+ push master；CHANGELOG 与本文实施记录回写（含读数、实施偏差）；V119 走查清单交用户。

## 十、实施记录（20261007 当日收口）

| 步 | 提交 | 内容 |
| --- | --- | --- |
| S1 | `dbc6efb` | 跳转原语收口 `LogEntryJumpOps`（resolveSource/resolveXml/navigate/showToast/showMissingSourceGuide）+ 两面板改委托；历史页顺带清死引用。**闸门**：6 类 41 例 0 失败 |
| S2 | `064b387` | `LogEntryTab` 枚举 + `SqlLogManager.onOpenEntryInTab` 通道 + 面板 init 注册/dispose 同一性注销 + `openEntryInTab` 单一落点（原两 Send 动作改委托）+ `jumpToSourceRealtime` 收口（实时页整体策略，弹框同文案同反馈）+ 守卫白名单登记 |
| S3/S4 | `fe8e320` | 弹框右键菜单六件 + D92=A 抑制后行移除 + 两枚按钮图标化（`ReformatCode`/`Copy`）+ 删「查看原文」链路与键 |
| S5 | `1fba449` | 守卫红线修复：送页签取管理器改只读入口 `sqlLogManagerIfCreated`（`SqlLogManagerLookupGuardTest` 抓出直接 `getService` 违规） |

**读数**：定向批 **234 例 233 过**——弹框 15/0、日志面板族（RendererUi 33/DedupeToggle 6/Filter 24/FocusTab 4/Layout 10/LiveJump 10/RequestFocus 3/SortAndSlowQuery 7/TypePopup 6/XmlJump 4）、LogToSqlPanelTest 19/0、SqlToJavaPanelTest 58/0、HistorySqlLogPanelJumpToSourceTest 6/0；**testFast 3406 例 0 失败**（S5 后复跑）。

**唯一失败＝并行会话既有红（非本改动）**：`BannedApiSourceScanTest.catch_Throwable块必须判fatal或登记豁免` 报 `SqlToJavaPanel.kt` 豁免站点 `expected:<1> but was:<2>`。定性依据：①失败信息只点名 `SqlToJavaPanel.kt`；②本任务 8 个改动文件与该文件零交集（`git status` 该文件干净）；③`git log -1 -- SqlToJavaPanel.kt` = 并行会话已提交笔 `f61ed07`（T-MapResultVo v2 修复，新增 `catch (t: Throwable)` ≒ 977/1239 两处）。**未动其文件**，留给其作者按守卫要求补判定或重登记。

**反向验证（守卫纪律）**：临时把「复制时间戳」的置灰条件改成恒可用（`RED PROBE` 注记）→ 门禁用例必红（15/0 → **15/1**，红点精确落在 `test复制时间戳动作_受显示开关门禁`）；探针已还原并 grep 复查零残留。

**实施偏差与发现（四处）**：

1. **计划 §七 前提 4 被证伪（跳转两函数不同体）**：实测 `SqlLogPanel.jumpToSource` 与 `HistorySqlLogPanel.jumpToSource` **策略不同**（实时页 `MissingSource` 走轻通知、历史页并入 `NoSource` 走引导弹窗；toast 键 `log.panel.*` vs `log.history.*`），并非逐字同体。处置：共享件只收口逐字相同的原语 + 实时页整体策略（两个使用方：日志页与弹框），历史页策略原样保留——避免「提取取一侧丢另一侧」。
2. **构造期序坑（本仓第四次触发，已避开）**：`openEntryInTabCallback` 首版写在 `init` 之后，而 init 内即读它完成注册——按仓内既有的「监听器命名引用声明于 init 前统一区块」惯例前移并加注。
3. **测试门禁的构建期语义**：`CopyTimestampAction` 的 clip 与日志页同款「构建菜单时取值」，故门禁用例须在每次取动作前设置开关（首版断言旧动作导致假红，已改为现取现断言）。
4. **两道项目守卫在本轮各抓出真问题（守卫有效性的正面证据）**：①`BannedApiSourceScanTest` 跳转族读权限规则命中新文件 `LogEntryJumpOps.kt` → 按规则登记 EDT 白名单并注明依据；②`SqlLogManagerLookupGuardTest`（在 testFast 快桶里）命中弹框直接 `project.getService(SqlLogManager)` → 改走只读入口 `sqlLogManagerIfCreated`（T-ST-02b2 定论：读路径发起创建会在 temporarily-disposed 窗口把服务永久毒化）。两条都不是「补个白名单了事」，后者是真实语义修正。

**遗留**：V119 真机走查（六件菜单逐项 + 两枚图标按钮观感与日志转 SQL 对齐 + 「查看原文」确已消失 + 工具窗未创建时送页签提示）；计划 §七 前提 1（「日志转 SQL 下方两个按钮」指认为结果区头两枚 `Copy`/`ReformatCode`）待真机一眼确认，若指认为后两枚（执行/清空）则换图标。
