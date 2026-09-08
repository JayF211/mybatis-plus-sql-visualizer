# T-ClickPreview：SQL 日志列表悬停预览卡顿，改单击预览、双击弹窗 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `dccc0b2`）
> **优先级**：P1（SQL 日志条目变多后鼠标悬停卡顿，主列表日常高频操作）
> **预估工时**：2~3h（含全量回归与真机验证）
> **来源**：用户反馈——列表条目多时「鼠标放上去就卡，因为会弹出预览的弹窗」

---

## 一、需求分析

SQL 日志列表条目变多后，鼠标在列表上移动会卡顿。卡顿来源是悬停弹出的预览气泡：每滑过一行就触发一次弹出/销毁。诉求是把「悬停出预览」改成明确的点击动作，交互分两层：

1. **单击条目**：弹出轻量预览——内容等于现在悬停显示的效果（还原后的完整 SQL）；
2. **双击条目**：打开 SQL 预览弹窗详细查看——现状双击行为，保持不变；
3. **悬停不再弹出任何东西**：消除卡顿源。

## 二、现状与根因

### 2.1 现状行为

主日志列表（`SqlLogPanel`）交互现状：

- `SqlLogPanel.kt:710-731` `buildListPane()`：`fixedCellHeight = -1`（可变行高），`MouseAdapter.mouseClicked` 中 `clickCount >= 2` 时调 `openPreview(entry)`（完整预览弹窗 `SqlPreviewDialog`，支持参数回填/格式化/性能分析）；
- `SqlLogPanel.kt:1246` `LogEntryListCellRenderer` 渲染时设置 `toolTipText = entry.restoredSql`——这就是用户看到的「预览的弹窗」：鼠标悬停任意行即弹出完整 SQL 的气泡；
- `openPreview`（1004-1048 行）逻辑不动，双击弹窗行为保留。

同一 tooltip 写法另有两处：历史 tab 的 `HistorySqlLogPanel.kt:448` 与 F-7 历史工具窗的 `SqlHistoryPanel.kt:213`（均 `toolTipText = entry.sql`），同源卡顿，属范围确认项（见 §3.4）。

### 2.2 卡顿根因

三层叠加：

1. **悬停 tooltip 按行弹出**：ToolTipManager 在气泡已显示时，鼠标在同一组件内移动会立即更替气泡内容（不再等初始延迟），滑过 N 行就有 N 次弹窗创建/布局/绘制/销毁；
2. **弹窗触发行重绘，连动可变行高重排**：每次气泡出现该行重绘，而列表 `fixedCellHeight = -1`，重绘连带可见区行高重新计算；
3. **成本 × 行数叠加**：条目数百行时，单次 hover 的弹窗成本乘上滑过行数，叠加成明显冻结。

（行内 HTML 渲染已有 `cachedListHtml` 缓存（T-LogListFreeze-02），重绘本身成本已压低，不是本轮瓶颈；本轮只处理 tooltip 弹窗这一悬停专属成本。）

## 三、技术方案

### 3.1 改动点 1：移除悬停 tooltip（根治卡顿）

`LogEntryListCellRenderer.getListCellRendererComponent`（SqlLogPanel.kt:1246）删除 `toolTipText = entry.restoredSql`。删掉后悬停零成本，卡顿消失。该渲染器除此外无其他 tooltip 用途。

### 3.2 改动点 2：单击出轻量预览弹窗

在 `buildListPane()` 的 `MouseAdapter` 里增加单击分支，用延时定时器区分单击/双击：

- `clickCount == 1`：重启 `javax.swing.Timer`（`CLICK_PREVIEW_DELAY_MS = 300`，字段持有，可 stop/restart）；
- 定时器触发：对 `list.selectedValue` 构建轻量预览弹窗并显示（锚定到该行）——内容与现在悬停一致：`withTerminator(entry.restoredSql)`；
- `clickCount >= 2`：停定时器（双击的第一击不弹预览）→ `hideClickPreview()`（关掉可能开着的旧预览）→ 走现有 `openPreview(entry)`。

预览弹窗形态：只读等宽 `JTextArea` 包在 `JBScrollPane` 里（长 SQL 可滚动，文字可选中复制——比 tooltip 还多了复制能力），`JBPopupFactory.createComponentPopupBuilder` 构建，`setRequestFocus(false)`（不抢焦点，列表键盘操作不受影响）、`setCancelOnClickOutside(true)`（点列表外即关）、`setCancelOnOtherWindowOpen(true)`、`setLocateByContent(true)`（按内容自适应尺寸）、`createPopup()`，`showInScreenCoordinates(list, 行左上角屏幕坐标)` 显示。行坐标用 `list.indexToLocation(index)` + `list.locationOnScreen` 换算。

弹窗生命周期管理：`private var clickPreviewPopup: JBPopup?` 字段；显示新预览前先 `cancel()` 旧的；面板 `dispose()` 时停定时器并 cancel 弹窗（避免泄漏）。

### 3.3 改动点 3：双击打开完整预览弹窗（现状保留）

`openPreview` → `SqlPreviewService.showResult` → `SqlPreviewDialog` 链路不动，只在前置补 `hideClickPreview()`（防双击前残留旧预览）。

### 3.4 范围确认项（默认只改主列表）

历史 tab（`HistorySqlLogPanel.kt:448`）与 F-7 历史工具窗（`SqlHistoryPanel.kt:213`）是同一 tooltip 写法，条目多了同样会卡。但两处现状只有双击入口、没有单击预览交互，若移除 tooltip 需要另补一套单击预览交互。默认本轮只改主日志列表（用户提的需求范围），两处历史面板是否同步处理留待用户确认，确认后开增量小任务。

### 3.5 API 权威核实（javap）

243 编译基线（本地 gradle 缓存 `com.jetbrains.intellij.idea/ideaIC-2024.1/lib/app-client.jar`）：

```
public abstract ComponentPopupBuilder createComponentPopupBuilder(JComponent, JComponent)   // JBPopupFactory
setRequestFocus(boolean) / setResizable(boolean) / setCancelOnClickOutside(boolean)
setCancelOnOtherWindowOpen(boolean) / setLocateByContent(boolean) / setMinSize(Dimension) / createPopup()
JBPopup.showInScreenCoordinates(Component, Point) / cancel() / isVisible()
```

全部存在。注意 **243 无 `setMaxSize`**——尺寸约束靠给 `JBScrollPane` 设 preferred size（`setLocateByContent` 按内容定尺寸）。

## 四、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 移除渲染器 tooltip | `LogEntryListCellRenderer` 删除 `toolTipText = entry.restoredSql` |
| 2 | 单击预览 | `buildListPane` 增单击分支 + `Timer` 字段 + `buildClickPreviewPopup(entry)` + `showClickPreview()`/`hideClickPreview()` + 弹窗生命周期管理（交互逻辑抽 `internal` 方法供测试直调） |
| 3 | 双击兼容 | `clickCount >= 2` 分支停 Timer + 关旧预览 + `openPreview` 不变 |
| 4 | dispose 清理 | 面板 `dispose()` 停 Timer + cancel 弹窗 |
| 5 | 新增单测 | `SqlLogPanelClickPreviewTest`（见 §五） |
| 6 | 全量回归 | gradlew 前 `python parallel_tasks.py check`；`./gradlew test --no-build-cache --no-configuration-cache` 基线绿 |
| 7 | runIde 真机（人工跟进项） | 统一走 runide-demo-verify-workflow |
| 8 | 文档三处同步（实施后） | 变更记录.md 追加 + 本计划 §实施记录 + docs/MEMORY.md 变更记录 |

## 五、测试计划

headless（`BasePlatformTestCase`）下 `JBPopup.showInScreenCoordinates` 依赖 `Component.getLocationOnScreen()`，headless 抛 `HeadlessException`，因此「弹窗构建」与「弹窗显示」拆开：单测覆盖构建与交互逻辑，显示行为走 runIde 真机。

新增 `SqlLogPanelClickPreviewTest`（`src/test/kotlin/cn/song/mybatisplus/visualizer/log/`）：

| 用例 | 断言 |
|------|------|
| 渲染器不再设 tooltip | 直接渲染 `LogEntryListCellRenderer` 条目 → `toolTipText == null`（回归锁，卡顿根因） |
| 预览内容 == 还原 SQL | 用条目 SQL 构建预览内容组件 → `JTextArea` 文本 `== withTerminator(restoredSql)`、只读、等宽 |
| 双击取消单击预览 | `onListClick(1)` 后定时器运行中；`onListClick(2)` 后定时器停止且弹窗引用为空 |
| 单击延迟内不立即弹 | `onListClick(1)` 后立即断言弹窗引用为空（Timer 未到点不同步执行） |
| 弹窗构建成功 | `buildClickPreviewPopup(entry)` 返回非空 popup，内容文本正确，`cancel()` 后无残留 |

交互逻辑抽 `internal`（`onListClick(clickCount)` / `buildClickPreviewPopup(entry)` / 弹窗引用访问器），测试直接调用，不依赖真实鼠标事件与墙钟。

## 六、风险与回滚

- **风险低**：改动集中在主列表交互；预览弹窗是新增轻组件，不动 `openPreview`/`SqlPreviewDialog` 链路。
- **行为变化（预期内）**：悬停不再弹预览，改为单击触发；双击行为不变。
- **边界**：双击两击间隔大于 300ms 的用户，第一击后预览会先弹出、第二击时被关掉——正常双击间隔（150~250ms）不会闪；如需完全对齐系统双击间隔，可改读 `Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval")`（Windows 默认 500ms，代价是单击预览响应变慢 200ms），实施时权衡定值。
- **回滚**：revert 单个 commit。

## 七、实施记录（2026-09-08，已实施，范围扩为三列表同步）

**范围变化**：用户实施指令明确「sql 日志和 sql 历史同步优化」——§3.4 范围确认项落为确认，历史 tab（HistorySqlLogPanel）与 F-7 历史窗（SqlHistoryPanel）一并改造，三列表共用同一助手。

1. **共享助手**：新增 `log/SqlClickPreview.kt`（internal，三面板共用）——`javax.swing.Timer`（300ms 单发，`isRepeats=false`）区分单击/双击；`onClick(clickCount)`：单击 restart 排程、双击 stop+hide；到点 `show()` 对选中条目 `build(sql)` 出轻量预览弹窗（`JBPopupFactory.createComponentPopupBuilder`：只读等宽 `JTextArea`+`JScrollPane` preferred 520×300、`setRequestFocus(false)`/`setResizable(true)`/`setCancelOnClickOutside(true)`/`setCancelOnOtherWindowOpen(true)`/`setLocateByContent(true)`），`RelativePoint(list, indexToLocation)` 锚定行左上角，弹前 cancel 旧的；`dispose()` 停表关弹窗。API javap 核实同 §3.5。
2. **SqlLogPanel**：新增 `clickPreview` 字段（previewSql = `withTerminator(selectedValue.restoredSql)`）；MouseAdapter 先转发 `clickPreview.onClick(e.clickCount)` 再双击 `openPreview`（链路不动）；`LogEntryListCellRenderer` 删 `toolTipText`（1246 行）；`dispose()` 补 `clickPreview.dispose()`；测试访问器 `clickPreviewForTest`。
3. **HistorySqlLogPanel**：二级 sqlList 同型接线（`sqlClickPreview` 字段 + MouseAdapter 转发 + 渲染器删 tooltip + dispose）；`showFileList()` 返回一级时补 `hide()`（防二级预览残留）。
4. **SqlHistoryPanel**：同型接线（previewSql = `withTerminator(selectedValue.sql)`，与其 tooltip 原内容一致加终止符）；渲染器删 `toolTipText = entry.sql`；`dispose()` 补释放；补 `SqlClickPreview` import。
5. **测试**：新增 `SqlClickPreviewTest`（7 用例）——弹窗构建内容断言（文本=SQL/只读/换行/等宽；`popup.content` 为包裹层组件，文本区经 `UIUtil.uiTraverser` 组件树查找）、双渲染器 tooltip 移除回归锁、三面板单击排程/双击取消、dispose 停表。踩坑两枚：`javax.swing.Timer` 无 1 参构造（`Timer(delay, null)`）；Kotlin 测试函数名禁含空格。
6. **回归**：定向 5 测试类 33 用例全绿；全量 `./gradlew test --no-build-cache --no-configuration-cache` **3094/0/0/6 绿**（基线 3087+7）。
7. **同步情况**：CHANGELOG.md `[Unreleased]` 新增 `### Changed` 条目；`docs/变更记录.md` 顶插本条；`docs/MEMORY.md` 变更记录行。runIde 真机交互验证（悬停无气泡/单击出预览/双击弹窗/双击不闪/Esc 与点外关闭/长 SQL 滚动）留人工跟进，统一走 runide-demo-verify-workflow。
8. **并行会话拆分提交**：实施期间并行会话 T-LogListFreeze04（default sandbox 登记 10:03~11:03）在同一检出对 SqlLogPanel.kt 渲染器管线做重写（HTML 缓存→SqlListFragmentRenderer 逐条片段渲染 + 固定行高），与本任务改动在同一文件交叠；其重写已保留本任务全部接线（clickPreview 字段/onClick 转发/clickPreviewForTest/toolTipText 移除）。拆分方式：SqlLogPanel.kt 以「HEAD + 本任务 5 处编辑」重建（=09:58 定向 33 用例绿时点状态）经 `git hash-object + update-index` 直接暂存进索引（工作树零接触），本任务 fix 提交不含对方任何文件；工作树中对方版本原样保留，其后续提交以本提交为基线只呈现其重写增量。本任务全量回归（3094/0/0/6 绿，10:04）编译时点早于对方 10:05/10:06 的 LogSqlModels/SqlLogPanel 落盘，合并树的最终验证由对方会话测试轮次覆盖。

## 八、二轮实施记录（2026-09-08，T-ClickPreview-02，已实施）

**用户反馈与新需求**：①「点其他地方后未再单击 SQL，鼠标放上去就出现预览」；②预览窗要有关闭按钮和复制按钮；③预览窗希望可编辑（方便复制内容）；④弹窗不得遮住被点行。

1. **复活预览根因与修复**：一轮 onClick 只看 clickCount——空白区/行下留白点击同样 `timer.restart()`，300ms 后 `show()` 读**当时选中态**（点击空白不清选中）→ 借残留选中行复活预览，视觉上像悬浮触发。修复=排程严格绑定「点到真实行」：`onClick(clickCount, clickPoint)` 经 `locationToIndex + getCellBounds(index,index).contains(clickPoint)` 判命中，命中才排程且**点击时捕获该行 SQL**（构造参数改 `sqlAt: (Int) -> String?` 索引式回调，`pendingIndex/pendingSql` 点击时定死，定时器到点直接用）；未命中立即 `timer.stop() + hide()`；仅左键触发（面板侧 `SwingUtilities.isLeftMouseButton(e)` 守卫，右键菜单的 mouseClicked 不再连带排程）。
2. **不遮被点行**：锚点由行左上角改为 `anchorPointFor(index)` = `getCellBounds` 行**下缘 +2px 缝**，弹窗在被点行下方展开（屏幕下方放不下由平台自动贴边回调）；`setLocateByContent` 移除（显式锚点定位更可预期）。
3. **可编辑 + 复制/关闭按钮**：文本区 `isEditable = true`（点进即可选择/编辑，setRequestFocus(false) 保持不抢焦点、setFocusable(true) 支持点击获焦）；头部 `FlowLayout(RIGHT)` 放两枚图标按钮——复制（`AllIcons.Actions.Copy`，取**编辑后当前文本**写系统剪贴板 CopyPasteManager）/ 关闭（`AllIcons.Actions.Close` → `hide()`），tooltip 走新增 i18n 键 `log.preview.copy` / `log.preview.close`（en/zh 双 bundle）。
4. **状态卫生**：`JBPopupListener.onClosed` 将 `popup` 引用清空——点外/Esc 等外部关闭后不再持有已关闭弹窗。
5. **测试**：SqlClickPreviewTest 重写 9 用例——内容可编辑+换行+等宽、双按钮 tooltip 按 i18n 文案匹配（踩坑：popup.content 包裹层含平台内部按钮，不能按数量断言）、复制回调携当前文本/用户编辑后文本、关闭回调触发、本地定高列表（fixedCellHeight=20 + setSize，getCellBounds/locationToIndex 纯数学 headless 可用）单击命中排程+空白区取消隐藏+双击取消、anchorPointFor 行下缘断言（y=行底+2）、三面板空列表单击不排程+dispose 停表。定向 SqlClickPreviewTest 9 绿 + 受影响 5 套件（Layout/Navigation/HistoryPanel/LogListFreeze/HistoryCellRenderer）绿。
6. **提交环境变化**：并行会话已全部收笔提交（T-LogListFreeze04/T-LogDedupeMpMark/T-JdbcMultiline 均已入库，HEAD 渲染管线即对方重写版），本轮工作树只剩本任务 7 文件，直接正常暂存提交（未再走索引级拆分）。遗留：SqlLogPanelRendererUiTest 3 用例红（对方 T-LogListFreeze04 WIP 随其提交入库，去重徽章/两行结构/耗时着色的片段渲染断言，与本任务无关）。

## 九、三轮实施记录（2026-09-08，T-ClickPreview-03，已实施）

**用户需求**：单击预览也要支持 SQL 高亮与编辑——「我想临时改一个字段值再复制」。

1. **内容升级**：`buildContent` 的纯文本 `JTextArea` 换为**原生编辑器**——纯文档（`EditorFactory.createDocument`）+ **SQL FileType 驱动词法高亮**（`createEditor(document, project, fileType, false)` 四参重载，javap 核实在 243 基线）。**刻意不走 PSI**（SqlInputEditor 的 PsiFileFactory 路线会踩「EDT 直调 createFileFromText → 262 read access 硬抛须包 ReadAction」陷阱，show() 由 Timer 在 EDT 触发；纯文档+fileType 词法高亮零 PSI 零风险，关键字/表名/字符串/数字着色即用户所求）。测试环境无 Database 插件时 `findFileTypeByName("SQL")` 为 null → 降级纯文本编辑器（对齐 SqlInputEditor 兜底）。
2. **编辑体验**：`isViewer=false` 可编辑 + 软换行（`setUseSoftWraps(true)`，长 SQL 免横向滚动）+ 紧凑外观（隐藏行号/折叠/缩进导轨/页底留白）；复制按钮取**编辑后当前文档文本**（`editor.document.text`），「临时改字段值 → 点复制」即所求。constructor 增加 `project` 参数（三面板传入）。
3. **生命周期**：编辑器随弹窗关闭释放——`JBPopupListener.onClosed` 中 `EditorFactory.releaseEditor`（点外/Esc/关闭按钮/面板 dispose 的 cancel 全走 onClosed，单一释放路径）。
4. **测试**：SqlClickPreviewTest 9 用例适配——编辑器文档文本等于还原 SQL、`isViewer=false`、软换行、双按钮 tooltip（i18n 匹配）、复制回调携当前文本、**编辑后复制**（`WriteCommandAction.runWriteCommandAction` 改文档 → 复制取编辑值）、关闭回调；排程/锚点/面板接线用例不变。定向 9 绿 + 受影响 4 套件（Layout/Navigation/HistoryPanel/LogListCopy）绿。
5. **插曲**：Kotlin 增量编译陈旧 ABI 假失败——主类已重编译为新签名（javap mtime 13:47 实证），测试编译仍见旧 `buildContent(String,...)` 签名报参型不匹配；重跑即绿（对齐 T-LogDedupeMpMark 同款插曲）。

---

## 十、四轮实施记录（2026-09-08，T-ClickPreview-04，已实施）

**用户反馈**：①「点击预览的高亮应该和 sql 日志的 sql 列表保持一致的颜色，直接复用」；②「目前还是不能编辑单击预览的 sql」。

1. **①根因（高亮两套体系并存）**：三轮的预览编辑器用 `createEditor(document, project, sqlFileType, false)` 挂 SQL FileType——真机上着色由 Database 插件的方言高亮器接管（关键字红/棕系等），而列表条目走插件自研 SqlLexer + SqlHighlightColors（One Dark 风格：关键字紫、表名洋红、字符串绿……）。同一条 SQL 在列表和预览里颜色不同，用户要求直接复用列表配色。
2. **①修复（自建 SyntaxHighlighter + 共享映射）**：新增 `SqlListSyntaxHighlighter`（`ui/highlight` 包，`SyntaxHighlighter` 实现：`getHighlightingLexer` 返回 SqlLexer、`getTokenHighlights` 返回配色键）。token→配色键映射从 `SqlListFragmentRenderer` 内联 map 提为 **`SqlListSyntaxHighlighter.ATTR_BY_TYPE` 唯一权威**——列表片段渲染与编辑器高亮共用同一映射，改色只动一处（结构上保证"同色"，不靠两处对齐）。预览编辑器改 `createEditor(document, project)` 纯文档编辑器 + `EditorEx.setHighlighter(LexerEditorHighlighter(SqlListSyntaxHighlighter(), EditorColorsManager.globalScheme))`——不再依赖平台 SQL FileType / Database 插件，测试沙盒与 Community 同样有高亮（顺带消除三轮的降级分支）。
3. **①API 核实（243 基线 javap，app-client.jar）**：`LexerEditorHighlighter` 唯一构造 `(SyntaxHighlighter, EditorColorsScheme)`（lexer 从 highlighter 取，无 (Lexer, scheme) 构造、无 setLexer）；`setHighlighter(EditorHighlighter)` 在 `EditorEx`（`createEditor` 静态类型为 `Editor`，须 `as? EditorEx` 后调用——首版直接 editor.setHighlighter 编译不过的实证）；`EditorImpl.setHighlighter` 内部完成 `highlighter.setEditor(this)` + `setText(immutableCharSequence)` + 文档监听注册，**不预调 setText/setEditor**（setEditor 二次调用触发 "Highlighters cannot be reused" 断言）。`LayeredLexerEditorHighlighter`（`ex.util` 包）方案不需要——自建高亮器就是基准层，无平台方言层需要叠加。
4. **②根因（键盘焦点不在弹窗）**：弹窗 `setRequestFocus(false)`（一轮遗产，当时为不抢列表焦点）——轻量弹窗不参与焦点转移，键盘输入全落在列表上；编辑器 `isViewer=false` 本就允许编辑，卡在拿不到焦点，"可编辑"名存实亡。修复改 `setRequestFocus(true)`：弹出即聚焦内容组件，焦点自然落进编辑器可直接打字；复制/关闭按钮 `isFocusable=false` 不参与焦点遍历（保持不变）。
5. **测试**：SqlClickPreviewTest 增至 10 用例——新增「编辑器高亮与列表片段同源同色」：断言 `editor.highlighter` 为 `LexerEditorHighlighter`、`createIterator(0)` token 序列从文首覆盖到文末、非空白 token 全部在 `ATTR_BY_TYPE` 有配色键、列表片段通道文本守恒；既有 9 用例不动全绿（文档/非 viewer/软换行/按钮/编辑后复制/排程/锚点/三面板接线）。定向 10 绿 + SqlListFragmentRendererTest 6 绿 + log 包 51 文件 560 绿。
6. **runIde 真机人工跟进**：预览弹窗高亮与列表行同色目检（亮/暗主题各一次）、点进编辑器直接打字改字段值 → 复制按钮取编辑后文本。
