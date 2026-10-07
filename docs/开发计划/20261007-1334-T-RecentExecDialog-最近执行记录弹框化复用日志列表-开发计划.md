# 开发计划：T-RecentExecDialog「最近执行记录」弹框化——复用 SQL 日志列表

- 编号占用：O-118 / R64 / D86~D87 / V118（落库前先 git 实测是否被并行会话占用）
- 日期：2026-10-07
- 状态：**已拍板（20261007 用户令：D86/D87 均按推荐），实施中**——分支 `t-recentexec-dialog` + 独立工作树
- 分支策略：独立分支/worktree 实施（master 工作树存在他会在途改动，不混提交）
- 决策记录：**D86=A**（恒弹对话框，空态文案；轻通知分支删除）；**D87=A**（右侧编辑器=restoredSql）

---

## 一、需求与目标

gutter 图标右键菜单「最近执行记录」当前弹的是**二级 ListPopup**（单行摘要、最多 20 条、点选后直接开预览），信息密度低、不可回看。目标改为：点击「最近执行记录」后弹**对话框**，交互形态对齐既有「SQL 屏蔽规则管理」对话框（SqlSuppressManagerDialog）——左侧 SQL 列表 + 右侧只读高亮编辑器；左侧列表**直接复用 SQL 日志面板的条目渲染器**，视觉与日志页完全一致。

不变的：入口（右键菜单项）、数据源（锚点反查 `EntrySourceMatcher.match`）、左键直预览、200ms 防抖。

## 二、现状分析（代码级）

### 2.1 当前交互链（gutter → 最近执行记录）

`SqlGutterLineMarkerProvider.showRecentEntriesPopup`（ui/gutter/SqlGutterLineMarkerProvider.kt:164）：

1. EDT 捕获 `PsiSnapshot.capture(elt)`；
2. 池化线程 `ReadAction` 内 `validatedRecentAnchor` 校验快照并解析行号（失效→null）；
3. `EntrySourceMatcher.match(manager.entries.snapshot(), file, line)` 反查（同文件按时间倒序，行号窗口 ±30 内优先，上限 `MAX_RESULTS = 20`）；
4. `invokeLater` 回 EDT：**空 → 轻通知**（`gutter.popup.recent.empty`，测试模式跳过）；**非空 → JBPopup 二级列表**（仅 `entry.summary` 单行文本）；
5. 点选条目 → `doFinalStep` 内 `LogEntryPreviewOpener.open`（统一识别通道 `toPreviewResult` → `SqlPreviewService.showResult`，含字面量恢复/格式化设置）。

痛点：二级列表只有一行摘要文本，长 SQL 看不全；没有参数还原后的完整 SQL、没有时间/耗时/来源等元信息；无法在多条记录间从容对比。

### 2.2 对标形态：SqlSuppressManagerDialog（log/SqlSuppressManagerDialog.kt）

T-SqlSuppress O-69 交付、经 O-80 拖拽缩放走查定型的成熟形态，全部要件可直接照抄（用户偏好：UI 新交互须照抄既有同类实现）：

| 要件 | 既有实现 | 位置 |
| --- | --- | --- |
| 非模态 DialogWrapper | `DialogWrapper(project, false, IdeModalityType.MODELESS)` | 构造器 |
| 可拖拽 + 尺寸/位置记忆 | `isResizable=true` + `getDimensionServiceKey` | init + O-80 |
| 左右主题化分栏 | `com.intellij.openapi.ui.Splitter`（**勿 import com.intellij.ui.Splitter**，编译失败前科在案） | createCenterPanel |
| 最小尺寸兜底 | 中心面板 `minimumSize`（DialogWrapper 无 setMinimumSize） | 同上 |
| 只读高亮编辑器 | `EditorFactory.createDocument/createEditor` + `LexerEditorHighlighter(SqlListSyntaxHighlighter(), globalScheme)` + `setViewer(true)` + 软换行 | createEditorPane |
| editor 生命周期 | 覆写 `dispose()`：`releaseEditor` → 置 null → `super.dispose()`（ParamImportDialog 成熟路径） | dispose |
| 选中换文不重建 editor | `runWriteAction { document.setText(...) }` | updateEditorFromSelection |
| 只挂「关闭」 | `createActions() = arrayOf(cancelAction)` | — |

### 2.3 可复用件清单

- **`LogEntryListCellRenderer`**（SqlLogPanel.kt:2364，`internal class`，同包可直用）——SQL 日志面板条目渲染器：两行结构（元信息行：时间·耗时·来源·行数·MCP 徽标·报错片段；SQL 行：类型色条 + 摘要），选中态走平台选中色。**直接设为新对话框列表的 cellRenderer 即可**，「复用 sql 日志中的 sql 列表」字面满足；`dedupeCounts` 徽章数据源留空 Map（反查场景无去重聚合语义）。
- **`LogSqlEntry`**（LogSqlModels.kt:38）——数据模型原样复用，`summary`/`statementType`/角标字段全在。
- **`EntrySourceMatcher.match`**——纯函数有单测（EntrySourceMatcherTest），数据链零改动。
- **`LogEntryPreviewOpener.open`**——打开预览动作复用（统一识别通道三口同源，勿另起炉灶）。
- **`EntrySourceMatcher.MAX_RESULTS = 20`**——展示上限沿用（见 §七 前提 3）。

## 三、方案设计

### 3.1 新对话框 `RecentExecutionsDialog`（log 包新文件）

```
┌─ 最近执行记录（标题含锚点文件名，见 §五 文案）─────────────┐
│ ┌─ 左：执行记录列表 ─────────┐ ┌─ 右：SQL 只读高亮编辑器 ─┐ │
│ │ 10:32:11 · 23ms [UserService:48]      │ │ SELECT id, name FROM user │ │
│ │ ▌SELECT id, name FROM user...         │ │ WHERE id = ?    ← 还原SQL │ │
│ │ 10:31:05 · 12ms [UserService:48]      │ │                           │ │
│ └───────────────────────────────┘ └───────────────────────────┘ │
│ 状态行：共 N 条（空态=未找到该代码位置的捕获记录）              │
│              [ 打开预览 ]   [ 关闭 ]                            │
└─────────────────────────────────────────────────────────────────┘
```

- **构造入参**：`project` + `entries: List<LogSqlEntry>`（调用方后台匹配完成后传入，构造期不做 IO/匹配）；可选锚点描述文本（文件名:行号）。
- **左列表**：`JBList<LogSqlEntry>` + `CollectionListModel`，`SINGLE_SELECTION`，cellRenderer = `LogEntryListCellRenderer()`；选中切换 → 右编辑器 `runWriteAction` 换文为 `restoredSql`（决策点 D87）；双击条目 = 「打开预览」。
- **右编辑器**：照抄 `SqlSuppressManagerDialog.createEditorPane` 全套（含 `EditorWhitespaceSettings.applyTo`、行号开、软换行、`setViewer(true)`），恒只读（无新增态）。
- **按钮行**：「打开预览」（默认按钮，`LogEntryPreviewOpener.open(project, selected)`，开预览后不关本对话框——用户可能连续对比多条；双击同路径）+「关闭」。无增删清（只读浏览，与屏蔽规则对话框的差异点）。
- **状态行**：`JBLabel` 灰字——空态文案 / 「共 N 条」。
- **布局**：`Splitter(false, 0.45f)` 左列表右编辑器（日志列表信息密度高于规则名，左栏比例放宽；真机可调）；`isResizable=true`、`getDimensionServiceKey`（新键 `mpsv.recent.executions.dialog`）、中心面板 `minimumSize`、`createActions` 只挂关闭——四处全部照抄 SqlSuppressManagerDialog。
- **线程约定**：构造与 `show()` 必须在 EDT（调用方既有 `invokeLater` 已保证）；匹配留在池化线程不变；对话框内零 PSI/零匹配逻辑。
- **资源释放**：照抄 `dispose()` editor 释放路径；无平台监听、无 Disposer 挂载需求（对齐 N3 教训注记）。

### 3.2 入口改造（SqlGutterLineMarkerProvider.showRecentEntriesPopup）

`showRecentEntriesPopup` 的 `invokeLater` 回调内：

- 空 → 行为按决策点 D86（推荐 A：仍弹对话框显示空态；备选 B：保留轻通知）；
- 非空 → `RecentExecutionsDialog(project, matched).show()` 替代 `BaseListPopupStep` 二级列表；

`doFinalStep` 焦点告警规避不再需要（不在 popup 选中路径内同步弹窗——dialog.show 非模态无此约束，与 SqlSuppressManagerDialog 入口同形态）。防抖、右键菜单、快照校验链全部不动。

## 四、改动清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `src/main/kotlin/.../log/RecentExecutionsDialog.kt` | **新增**，约 200 行（对话框本体 + 测试访问器） |
| 2 | `src/main/kotlin/.../ui/gutter/SqlGutterLineMarkerProvider.kt` | `showRecentEntriesPopup` 改调对话框，删二级 ListPopup 分支（约 −40/+15 行） |
| 3 | `src/main/resources/messages/MpSqlVisualizerBundle.properties` | 新增 §五 文案键（~7 键） |
| 4 | `src/main/resources/messages/MpSqlVisualizerBundle_zh.properties` | 同上中文 |
| 5 | `src/test/kotlin/.../log/RecentExecutionsDialogTest.kt` | **新增** headless 测试（见 §六） |
| 6 | `docs/开发计划/...`（本文档）+ CHANGELOG `[Unreleased]` 行 | 随实施同步 |

## 五、i18n 文案（双语新增）

| 键 | zh | en |
| --- | --- | --- |
| `log.recent.dialog.title` | 最近执行记录（%s） | Recent Executions (%s) |
| `log.recent.dialog.openPreview` | 打开预览 | Open Preview |
| `log.recent.dialog.close` | 关闭 | Close |
| `log.recent.dialog.count` | 共 {0} 条 | {0} entries |

- `gutter.popup.recent` / `gutter.popup.recent.empty` **保留**（前者是右键菜单项文案；后者 D86=A 后改由对话框空态直接复用，**不新增** `log.recent.dialog.empty` 重复键——实施定案）。
- `log.recent.dialog.noneSelected` **实施时删**：主按钮空态禁用已承载该语义，零产出文案键按枚举死常量守卫精神不留。
- 标题锚点参数传「文件名:行号」（`UserService.kt:48` 形态，MessageFormat `{0}` 占位），null 时用无参键。

## 六、测试与验证

1. **定向单测（新增 RecentExecutionsDialogTest）**：headless（`isUnitTestMode` 约定）覆盖——①空列表构造→状态行空态文案；②3 条构造→列表行数=3、首条默认选中、编辑器文本=首条 restoredSql；③选中切换→编辑器换文；④测试访问器驱动「打开预览」无选中→状态行提示。renderer 片段级断言沿用仓内 LogEntryListCellRenderer 既有测法（SqlLogPanel 渲染测试族）。
2. **既有回归**：`EntrySourceMatcherTest`、gutter 右键菜单既有测试不改动应保持绿（菜单结构未动）；`--tests` 定向跑 log + gutter 两包。
3. **守卫红线自查**：池化块跨线程读 PSI 的 `psiCapture` 守卫（收口函数 `validatedRecentAnchor` 保留原位调用）；`com.intellij.ui.Splitter` 禁 import（既有编译前科）；对话框无新增反射入口（不触 PlatformSignatureCrossVersionTest）。
4. **真机走查（V118）**：runIde 沙盒——右键 gutter → 弹框 → 选中切换/双击预览/拖拽缩放/尺寸记忆/空命中表现；与日志页同条目视觉比对（渲染器复用应完全一致）。
5. **门禁口径**：纯 UI 新增+入口改写，属小改动——按三档分层只跑定向 + testFast 冒烟；不触发 buildPlugin/verifyGate（无依赖/EP/平台 API 面变化）。

## 七、待验证前提（实施者先证伪再动手）

1. **`LogEntryListCellRenderer` 同包直用无障碍**——代码已证（internal + 同 log 包，SqlLogPanel.kt:2364），但它读 `PluginSettings` 的列表样式设置（T-ListMode 单行/多行模式、T-CrudColor 色条开关），headless 测试环境 service 缺失时的降级行为需实测（gutter 侧 `gutterEnabled` 有 `runCatching` 前例可照抄）。**待证**。
2. **headless 可实例化 DialogWrapper**——参照仓内自动化 UI 回归 61 例的夹具形态；若本测试类不可行，退化为主链纯逻辑测试 + V118 真机走查兜底。**待证**。
3. **20 条上限在对话框形态下是否够**——`EntrySourceMatcher.MAX_RESULTS=20` 原为防弹popup刷屏设计；对话框有滚动，长会话同文件高频执行可能截断。首版沿用不动（改上限动纯函数需重审单测），真机走查若实测截断明显再立项放宽（加重载参数，不动既有语义）。**待真机定**。
4. **双击打开预览无焦点告警**——非模态 DialogWrapper 内开 `SqlPreviewDialog`（同为非模态）无 popup doFinalStep 问题，判据=SqlSuppressManagerDialog 入口同形态无告警；仍列入真机走查项。**待真机证**。

## 八、决策点（20261007 用户拍板，均按推荐）

- **D86 空/命中行为 = A**：恒弹对话框——空时显示空态文案。交互统一（「查过但无记录」比一闪而过的通知更可感），且删掉轻通知分支少一条路径。（`gutter.popup.recent.empty` 键改由对话框空态复用，轻通知分支删除。）
- **D87 右侧编辑器内容 = A**：`restoredSql`（参数还原后可执行 SQL，与日志列表摘要同源、与预览默认一致）。

## 九、实施步骤（已批准）

1. 计划文档落 master（显式路径暂存本文件，**不动**他会在途改动：SqlToJavaPanel.kt / JavaCodeGenerator.kt / bundle×2 / hs_err·replay 日志）。
2. 新增 `RecentExecutionsDialog`（§3.1）+ bundle 键两处。
3. 改 `showRecentEntriesPopup` 接线（§3.2）。
4. 新增定向测试并跑绿（§六.1~2）；BannedApiSourceScanTest 等守卫族复跑。
5. testFast 冒烟；达标即提交（分步 commit，文案/对话框/接线可各一笔）。
6. V118 真机走查清单交用户；发行八处同步随下轮发版批（本任务不单独发版）。

## 十、实施记录（20261007 当日收口）

| 步 | 提交 | 内容 |
| --- | --- | --- |
| S1 | master `f329686` | 计划文档落 master（D86=A/D87=A 已拍板；master 主树在途改动未混提交） |
| S2 | `aa9b31e` | 分支 `t-recentexec-dialog` 实现：`RecentExecutionsDialog.kt`（非模态+可拖拽+尺寸记忆 `mpsv.recent.executions.dialog`+`Splitter` 0.45 分栏+`LogEntryListCellRenderer` 复用+`SqlListSyntaxHighlighter` 只读编辑器+dispose 释放 editor）+gutter 接线（二级 ListPopup/轻通知分支删除，`NOTIFICATION_GROUP_ID` 随退役）+bundle 5 键×2+headless 测试 7 例+CHANGELOG `[Unreleased]` 行 |
| S3 | `7717cb1` | **用户指示：先并 master 再跑测试**——master `1841c3f`（T-SqlToJavaPromote 验收+CHANGELOG）并入，bundle 双方键共处核验过 |
| S4 | `9f580f2` | 测试闭环两修（见下「实施偏差」） |
| S5 | `4f60ab6` | master 增量 `142a8df`（T-MapResultVo 计划，**纯 docs** 2 文件）并入——与已测态零生产码差，读数不失效；最终回并 master 走 ff-only，落地态=已测态 |
| S6 | — | 定向 66 例 0 失败（RecentExecutionsDialogTest 7＋SqlLogPanelRendererUiTest 33＋EntrySourceMatcherTest 10＋gutter 16）；testFast 冒烟读数见下 |

**定向门禁读数**：定向 66/0（对话框 7＋渲染器回归 33＋matcher 10＋gutter 16）；testFast 冒烟 BUILD SUCCESSFUL（GRADLE_EXIT=0，52s——testFast=纯逻辑快桶，本任务 UI 测试在平台桶已由定向批覆盖；全仓编译+快桶全绿即冒烟口径达成）。

**实施偏差与发现（两处，均已闭环）**：

1. **`doDispose()` 不是平台 API，是仓内对话框自有惯例**——`DialogWrapper.dispose()` 平台侧 protected，`SqlPreviewDialog`/`RollbackSqlDialog` 各自定义公开 `doDispose(){ dispose() }` 供测试程序化关闭；首版漏抄，compileTestKotlin 七处 `Unresolved reference` 实锤后补齐（照抄惯例）。
2. **`JBList.getCellRenderer()` 返回平台包装壳**——`ExpandedItemListCellRendererWrapper` 包住用户渲染器，「getter `is` 断言」误报不复用（壳下即 `LogEntryListCellRenderer`，渲染未变）。修法=对话框自持 `entryRenderer` 字段、测试钉字段（不猜包装 API，铁律合规）。

**按钮形态定案（对 §3.1 的实现细化）**：走 DialogWrapper 惯用 OK/Cancel 双钮——`setOKButtonText`「打开预览」+ `doOKAction()` 覆写不调 super（开预览后对话框在场）；Enter=打开预览、Esc=关闭为平台默认行为，零自挂监听。

**遗留**：V118 真机走查（§六.4 清单：右键→弹框→选中切换/双击预览/拖拽缩放/尺寸记忆/空命中/与日志页视觉比对）；`EntrySourceMatcher.MAX_RESULTS=20` 上限真机观察（§七.3）。

### 二段：右侧编辑器默认格式化 + 切换原文 + 复制（D88=A，用户真机走查提点）

真机截图反馈：右侧编辑器单行 SQL 挤成软换行长条，可读性差。用户提点「自动格式化+格式化按钮+复制按钮」，拍板 D88=A（本对话框恒默认格式化开，与预览页全局 `logFormatSql` 设置解耦；切换状态不持久化）。

| 项 | 落法 | 复用件（同源，不另起炉灶） |
| --- | --- | --- |
| 默认格式化 | 编辑器装载 = `SqlFormatter.format(withTerminator(restoredSql))`；格式化异常按容错优先降级原文 | `SqlFormatter`（预览 `formatIfNeeded` 同一函数）+ `withTerminator`（log 包 internal，与预览口径逐字一致） |
| 切换原文/格式化 | 纯 `JButton` 标签随状态改写（「查看原文」↔「格式化」）= 点击后到达的形态；原文态精确 = `restoredSql` | 形制照抄 `SqlSuppressManagerDialog.structureButton`（不走 DialogWrapperAction：动作名刷新时机不可控） |
| 复制 | 复制**当前编辑器显示文本**（格式化态即格式化版）；反馈写状态行 | `UiTextClipboard`（T-UiNative 收口：进 IDE 复制历史）+ 通用键 `dialog.copy.done` |
| 空态 | 三控件（打开预览/切换/复制）统一禁用 | 既有 `syncSelectionScopedControls` 联动点 |

- 提交：master `ae59bea`（主分支直改，用户令「在主分支实施」；显式路径暂存，未碰并行会话在改的 `JavaCodeGenerator.kt`）。
- 读数：**定向 10 例 0 失败**（7 例原用例 + 新 3 例：默认格式化判别性形态断言/切换精确回原文再切回/复制取当前显示文本）。
- 环境事故（一次，已闭环）：首次跑测试时测试 JVM 原生内存分配失败——`os::commit_memory failed ... 页面文件太小 (DOS error/errno=1455)`，executor 非零退出且无断言报告。根因=**用户 runIde 沙盒 IDE（master 树）+ demo 应用 + 捕获 agent 同时在跑**，commit 水位 71.2/74.7GB、free 仅 3.5GB（R16 定例的用户真机在跑变体）。处置：不杀任何进程（用户真机会话），改用降足迹参数 `-PtestHeapSize=384m -PtestTieredStop=true -PtestCiCount=2 -PtestGcThreads=2` 重试即绿（27s）。事故产物 `hs_err_pid14876.log` 已清（另两个 `hs_err_pid19280/replay_pid19280` 属并行会话，未动）。
- 遗留：V118 走查清单追加两项——格式化默认态视觉、切换/复制手感。
