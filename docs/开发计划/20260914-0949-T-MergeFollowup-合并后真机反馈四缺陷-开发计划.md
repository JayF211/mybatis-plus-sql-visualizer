# T-MergeFollowup：合并后真机反馈四缺陷修复开发计划

- 立项日期：2026-09-14
- 任务性质：真机走查回归缺陷修复（T-MergeDevCrudcolorIntoMaster / beta.2 验收反馈）
- 反馈来源：用户按《验收文档》真机走查反馈（截图三张 + 预览弹窗一张，2026-09-13/14）
- 目标：四缺陷全修复，真机走查复查通过，全量测试绿 + verifyPlugin 三目标 Compatible

## 一、反馈清单与优先级

| # | 任务号 | 现象 | 级别 | 根因状态 |
|---|--------|------|------|---------|
| 1 | T-MergeFollowup-01 | 预览弹窗没有「执行计划」按钮（M37.2 功能入口丢失） | P1 | **已实锤**（合并语义丢失） |
| 2 | T-MergeFollowup-02 | gutter 图标右键无反应（左键待确认） | P1 | 实现在位，待复现取证 |
| 3 | T-MergeFollowup-03 | 显示模式多次切换后列表间距/字体错乱（历史页格式化模式严重重叠） | P2 | 嫌疑链已定位，待复现取证 |
| 4 | T-MergeFollowup-04 | 显示模式下拉 UI 样式与 IDEA 原生不符（两面板） | P3 | 明确（自绘 JComboBox 默认样式） |

用户已确认：窄侧边栏下模式下拉「不可见」实为折行到第二行，拓宽侧边栏可见——非丢失缺陷；折行可见性顺带核查列为 P3 随 #4 一起处理。

## 二、根因分析

### 2.1 T-MergeFollowup-01：EXPLAIN 按钮丢失（已实锤）

**证据链**：
- 合并树 `SqlPreviewPanel.toolbar()`（约 481~505 行）：按钮序列为 复制错误/压缩/格式化/关键字换行/竖向排列/**刷新/更多**——无「执行计划」；500~501 行却残留 dev 侧注释「M37.2：EXPLAIN（真实执行计划……dev 侧功能保留内联，导入/对比/分享已按 T-PreviewTopOverlap F2 折叠进「更多」菜单）」。
- M37.2 原始交付 `2536745`（dev）：按钮行确有 `add(smallButton(MpSqlVisualizerBundle.message("panel.explain")) { explainSql() })`。
- `explainSql()` 函数本体随合并入库（约 632 行起，含 renderExplainStatement 分派/执行链/showLoading），但**全树零调用者**——master 侧 T-PreviewTopOverlap 单行方案重构按钮行时删掉了 dev 的 EXPLAIN add 行，dev 侧注释行经自动合并保留，形成「注释说保留、代码已删除」的语义矛盾。无冲突标记，编译测试全绿（explainSql 无人调用不报错），正是「自动合并 ≠ 语义正确」的又一实锤。
- 更多菜单（`moreActions()` 508~512 行）三项为 导入参数/对比/分享——EXPLAIN 也不在其中（被折叠丢失）。

**修复**：`toolbar()` 在「竖向排列」与「刷新」之间补回 `add(smallButton(MpSqlVisualizerBundle.message("panel.explain")) { explainSql() })`；删除 500~502 行过时注释，改为指向本任务号。顺带决策：EXPLAIN 属高频核心动作，保持内联不进「更多」菜单（对齐 M37M38 文档 §2.3 入口描述）。

**测试**：`SqlPreviewPanelTest`（或 SqlPreviewTopRowLayoutTest）补断言——toolbar 组件文本含「执行计划」键（对齐 moreActions 文案断言的既有套路）；更优：补一条按钮点击冒烟（project=null 时 explainSql() 早退不抛，仅验接线）。

### 2.2 T-MergeFollowup-02：gutter 右键无反应（实现已在位，复现优先）

**现状**：`SqlGutterLineMarkerProvider.SqlGutterNavigationHandler.navigate`（88~104 行）逻辑完整：`SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger` → `showMenuPopup`（预览 SQL / 最近执行记录两项）；左键 `previewSource`。防抖 200ms。NPE 修复已在（finalChoice 判定）。

**嫌疑面（复现时按序排查）**：
1. **平台事件拦截**：243 平台 `EditorGutterComponentImpl` 对 gutter 图标区域右键可能先消费弹平台级菜单（或以 `navigate(e=null)` 合成路径调用），导致 handler 收不到右键事件——需 runIde 真机 + idea.log 断点取证；若属实，平台机制改用 `GutterIconRenderer` 的 context-menu 能力或编辑器级 mouseListener 接管（实现方案经 243 平台源码/javap 三重核实后定案，禁止臆测 API）。
2. **用户点击对象**：左右键语义仅 `SqlGutterLineMarkerProvider`（MP Wrapper 锚点）具备；XML/注解/JPA/hbm 等其余 provider 仍为单击直接预览（无右键菜单）——复现第一问先确认左键是否正常弹预览（左键正常 = 右键事件链问题；左键也无 = 图标/注册问题）。
3. **防抖误伤**：200ms 窗口内连点（左键后立刻右键）第二次被丢弃——若复现稳定复现则提高防抖日志可见性。

**修复**：按取证结论定（平台拦截 → 换平台正解 API；事件未达 → provider 注册链排查）。修复后 M37M38 说明文档 §2.1 补验证记录（该功能 dev 交付时真机走查即留人工未做，属首次真机验证）。

**测试**：handler 纯逻辑已有测试覆盖（finalChoice/防抖）；事件级无法 headless 的部分在计划 §五 真机走查清单钉死。

### 2.3 T-MergeFollowup-03：模式切换后列表渲染错乱

**现象**（用户截图，SQL 历史页「格式化」模式）：首个条目文字重叠成乱码状、条目间距挤压、视觉上字体大小不一。

**嫌疑链（按优先级复现排查）**：
1. **行高失效链断裂**：`HistorySqlLogPanel.onDisplayModeChanged`（134~140 行）→ `applySqlListCellHeight()`（`fixedCellHeight = -1` 切可变行高）后仅 `sqlList.repaint()`——**未 revalidate、未重置列表布局状态**。JList 从固定行高切可变行的缓存（cellHeights/layoutState）若未失效，旧行高框内画新多行内容即呈「重叠挤压」。日志页同款处理（`applyListCellHeight` + settingsChanged 监听 refreshList）对齐核查。
2. **组件池扩容属性不同步**：`ensureSqlLineCount` 新建的 `SimpleColoredComponent` 未显式设 font/opaque（既有行组件可能有 setFont 链）——扩容行字体与首行不一致的候选来源。
3. **片段缓存跨模式污染**：`entry.cachedListLineFragments` 键含模式自动失效（理论上）；复现时验证切换往返后缓存键比对是否真生效。
4. **选中态描边叠加**：选中行 selectedBorder insets 与 normalBorder 不同（W=2 描边），可变行高测量在选中/非选中切换时的测量一致性核查。

**修复方向**：模式切换统一走「`applyXxxCellHeight()` + `revalidate()` + 强制一次全量重建刷新（增量刷新管线在行高模型变更后先全量一次）」；渲染器组件池扩容时显式对齐 font/opaque。防回归测试：三模式循环切换 ×N 后断言 `fixedCellHeight` 状态、渲染器行组件数与首行 preferred 高一致（headless 可断言）；真机截图对比留走查。

### 2.4 T-MergeFollowup-04：显示模式下拉样式原生化

**现状**：两面板均为裸 `JComboBox`（SqlLogPanel 321 行 / HistorySqlLogPanel 102 行），新 UI 下呈 Swing 默认样式（含 Windows 风箭头/列表），与 IDE 原生下拉观感不符。

**方案对比（实施时按平台 API 核实择一，双面板同改）**：
- 方案 A（推荐）：`ComboBoxAction`（平台工具栏原生下拉按钮——Git 分支切换器同款）：点击弹三选列表菜单，按钮文本随当前模式切换；与工具栏既有「SQL类型」按钮、预览弹窗按钮行风格统一；i18n 复用既有三键零新增。
- 方案 B：保留 JComboBox，套平台原生 renderer/边框（`JBUI` insets + 自绘最小化）——改动小但仍难完全对齐原生。
- 否决：模态对话框（T-CrudSql 弹窗原生化教训——用户已三次否决非原生形态）。

**顺带核查（P3）**：窄侧栏下 WrapLayout 折行第二行的可见性（用户拓宽可见=折行发生；确认第二行是「可见折行」还是「被 NORTH 高度裁剪」——若裁剪，修 WrapLayout preferred 高度时序，同款家族 2026-08-27 已修过一次）。原生化改 ComboBoxAction 后单组件宽度减小，折行概率同步下降。

**测试**：`PluginSettingsPanelBindingTest`/两面板既有模式测试迁移到新控件（选中态断言改按钮文本）；`SqlLogPanelTypePopupToggleTest` 套路参照（headless 不弹真菜单，断言 step/文案与执行体）。

## 三、实施顺序与预估

1. T-MergeFollowup-01 EXPLAIN 恢复（根因实锤，直接修 + 测试，~0.5h）
2. T-MergeFollowup-02 gutter 右键复现取证 → 修复（取证 1h + 修复视结论 0.5~2h）
3. T-MergeFollowup-03 切换错乱复现取证 → 修复（取证 1h + 修复 1~2h + 防回归测试 1h）
4. T-MergeFollowup-04 下拉原生化（0.5~1h，含折行核查）
5. 全量回归 + verifyPlugin 三目标 + 真机走查复查（§五清单）

## 四、验收清单

- [ ] 预览弹窗按钮行出现「执行计划」，点击按方言出真实执行计划（无数据源时优雅报错）
- [ ] MP Wrapper 锚点右键弹出「预览 SQL / 最近执行记录」两项目菜单；左键直接预览不回归
- [ ] 历史页/日志页三模式循环切换 ×10 后列表渲染无重叠、行距正常、字体一致
- [ ] 两面板模式下拉呈 IDE 原生样式（方案 A 则为原生下拉按钮形态）
- [ ] 全量测试 0 失败；verifyPlugin 三目标 Compatible
- [ ] 验收文档对应条目按实测结果修订（含 §3.1/§3.3 真机走查项勾销）

## 五、真机走查清单（runIde，修复后一次性复查）

1. gutter 左键预览 / 右键菜单 / 菜单项各自执行（MP Wrapper 锚点 + 任一非 Wrapper 图标对照）
2. 预览弹窗：执行计划按钮（有数据源真实执行 + 无数据源提示）
3. 历史页：单行 → 关键字换行 → 格式化 → 关键字换行循环切换观察渲染
4. 日志页：同上；窄侧栏（~570px）与宽侧栏两档宽度下拉可见性
5. 颜色标记 × 三显示模式组合抽查（合并渲染融合面）

## 六、实施记录（2026-09-14 回填）

### 交付提交（四笔）

1. **T-MergeFollowup-01 EXPLAIN 恢复**：`toolbar()` 竖向排列与刷新之间补回 `add(smallButton(panel.explain) { explainSql() })`，过时注释改指本任务号；`SqlPreviewTopRowLayoutTest.test直显按钮集合` 直显断言加 `panel.explain`。
2. **T-MergeFollowup-02 gutter 右键根治**：javap `app-client.jar` 实证 243 平台 `GutterIconRenderer.getRightButtonClickAction()` 基类恒 `aconst_null`（返回 null）且 `LineMarkerGutterIconRenderer` 未覆写——平台在事件分发层就把右键分流走该 API，M37.1 的 navigate 内判右键永远收不到事件。修复=getLineMarkerInfo 返回匿名 `LineMarkerInfo` 覆写 `createGutterRenderer()` 返回自定义 renderer 覆写 `getRightButtonClickAction()`，新增 `SqlGutterRightClickAction`（AnActionEvent.inputEvent → MouseEvent）→ `SqlGutterNavigationHandler.onRightClick`（共享 200ms 防抖窗口与菜单逻辑）。Kotlin 嵌套类 import 注意：`LineMarkerInfo.LineMarkerGutterIconRenderer`。
3. **T-MergeFollowup-03 切换错乱**：`HistorySqlLogPanel.onDisplayModeChanged` 补 `sqlList.revalidate()` 且二级列表由 repaint 改 `applySqlSearch()` 全量重建（model 全量变更触发 layoutState 重算双保险）；`SqlLogPanel` settingsListener 同步补 `list.revalidate()`（refreshList 增量路径在行高模型变更后不触发布局失效）。渲染器 grep 核实零 setFont——截图「字体大小不一」系重叠绘制视觉假象。防回归断言：模式切换后二级条目经全量重建不丢（HistorySqlLogPanelNavigationTest）。
4. **T-MergeFollowup-04 下拉原生化**：新增共享 `SqlListDisplayModeComboBoxAction`（`com.intellij.openapi.actionSystem.ex.ComboBoxAction`——注意包路径 ex；javap 实证 `createCustomComponent(presentation, place)`/`getPreselectCondition()`），按钮文本随当前模式、菜单内当前项高亮；双面板 `createCustomComponent(templatePresentation, MAIN_TOOLBAR)` 创建原生 ComboBoxButton；同步链简化为 presentation 文本单向同步（弃 syncingDisplayModeCombo 防重入守卫——点菜单项写设置广播、按钮文本由监听统一 syncFromSettings，天然无环）。测试断言 selectedIndex→按钮文案。

### 门禁

- 全量 clean test `--no-build-cache`：**3968/0/6 绿**（基线 3880 + dev 合并带入测试与本轮新增断言，0 失败）
- **verifyFull/buildPlugin 未跑**（用户口径 2026-09-14 定案：verifyPlugin 三目标校验与发行包构建仅发版前或大功能时执行，缺陷修复轮全量测试绿即达标；原计划任务因并行会话 T-FeedbackDlg 占用默认沙盒触发隔离守卫失败，顺势取消）
- 工程坑：`ComboBoxAction` 在 `actionSystem.ex` 子包（首写主包 Unresolved）；gutter renderer 嵌套类 import 须 `LineMarkerInfo.LineMarkerGutterIconRenderer`

### 遗留与真机复查（§五清单执行）

- gutter 右键修复为平台 API 正解（字节码级根因实锤），但弹窗交互仍需 runIde 真机点击验证（headless 无法覆盖平台事件分发）。
- 切换错乱修复后需真机三模式循环切换复查（截图对比）。
- 下拉原生化后窄侧栏折行可见性复查（ComboBoxButton 单组件较 JComboBox 窄，折行概率下降；仍建议复查）。
- 计划外发现：`git add -A` 曾把并行会话的调研文档捎带进 01 提交，已 reset 拆分（调研文档恢复未跟踪状态）。
