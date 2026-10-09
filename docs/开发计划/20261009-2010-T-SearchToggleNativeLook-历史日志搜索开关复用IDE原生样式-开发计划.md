# T-SearchToggleNativeLook：历史/日志搜索的正则·大小写开关复用 IDE 原生样式

- 占号：O-137/R83/D141/V164（引用前已实测未占用；O-136 已被 T-PreviewExecDsDialect 占用）
- 日期：2026-10-09 晚
- 关联：T-HistorySearchToggles（`87ebbfe`，dev-bak `87ebbfe`/合并批 42f1ba6 交付）——本项为其**样式迭代**，行为语义零变化
- 载体建议：dev-bak 直提（UI 单点小改）或独立工作树

## 一、现状与差距

- T-HistorySearchToggles 已交付的开关（行为已验证）：
  - 历史页 `HistorySqlLogPanel`：`regexToggle` / `matchCaseToggle` 两个 `JToggleButton(AllIcons.Actions.Regex / AllIcons.Actions.MatchCase)`，放在「搜索 SQL...」框**旁边**；
  - 日志页 `SqlLogPanel`：`matchCaseToggle` 单开关（D133 拍板：日志页无正则），随搜索条显隐。
- 图标已与 IDE Find 同源（AllIcons.Actions.Regex/MatchCase），**差距在形态**：IDEA 原生 Ctrl+F（EditorSearchSession）与 Find in Files（SearchTextArea）的开关是**内嵌在搜索框内部**的无边框图标切换钮（选中态高亮、hover 效果、圆角搜索框一体化），而现在是搜索框**旁边**两个带边框的独立按钮。
- 用户诉求：直接复用 IDE 原生样式；或复刻 Ctrl+F 的形态。

## 二、目标

- 历史/日志两处搜索框的开关观感与 IDEA 原生 Ctrl+F 一致（内嵌图标切换、选中态、原生搜索框形态）。
- **行为语义零变化**：regex/matchCase 过滤行为、`regex:` 前缀或语义、会话级开关态（不持久化）、memo 键（源串+matchCase）全部不变。

## 三、方案方向与决策点

- **S1（主方案，复用平台组件）**：改用平台 `com.intellij.find.SearchTextArea`（243 存在，Find in Files 同款）包装搜索文本组件——原生自带 regex/matchCase（及 words）切换钮、样式与快捷键处理。
  - 插件侧改动：搜索框换成 SearchTextArea（或取其内部文本组件接入既有过滤链），regex/matchCase 布尔从组件状态读取，喂给既有 `HistoryRegexMemo`（键=源串+matchCase）与两处过滤链。
  - 日志页只有 MatchCase 一开关：需确认 SearchTextArea 能否**隐藏 regex 钮**（构造参数/属性），否则转 S2 或评估放开日志页正则（D2 决策，默认不做）。
- **S2（兜底，复刻）**：保留现有过滤结构，把两个 `JToggleButton` 改为无边框图标钮并**内嵌进搜索框**（`SearchTextField` 可带 button ancestors；或自绘边距），对齐 Ctrl+F 观感（选中态底色/hover）。行为零风险，纯形态改造。
- **D1**：S1 若满足「243 可独立实例化 + 状态可读写 + 三版本 API 一致」则优先（真复用）；任一不成立转 S2。
- **D2**：不引入原生的 words/全词开关，不放开日志页正则（保持 T-HistorySearchToggles 拍板范围）。
- **D3**：`regex:` 前缀或语义、开关态会话级不持久化——不变。

## 四、待验证前提（实施者先证伪再动手）

- **P1 ✅ 已证伪/证实（20261009 深夜 javap 反汇编 243 app-client.jar）**：
  - `SearchTextArea` 是公开类（非 internal），构造器 `SearchTextArea(JTextArea, boolean multiline)`——可包我们自己的 JTextArea 直接实例化；
  - 原生开关钮本质是 `editorHeaderActions.ToggleRegex / ToggleMatchCase`（`CheckboxAction`，图标含 Regex/RegexHovered/RegexSelected 三态 + tooltip + 正则帮助链接），它们从 `AnActionEvent.getData(SearchSession.KEY)` 取会话，抽象契约只有 `isSelected(SearchSession)/setSelected(SearchSession,Boolean)`，底层落到 `FindModel.isRegularExpressions / isCaseSensitive`；
  - **接入路径**：`SearchTextArea.setExtraActions(AnAction...)`（返回 List）就是为内嵌按钮准备的入口；我们只需实现一个极小 `SearchSession`（持自有 `FindModel`，`getComponent()` 返回 null/占位）放进 DataContext，Toggle 动作即可原生驱动；
  - 或者更彻底：直接用 `SearchReplaceComponent.Builder.buildFor(project, component, searchSession)`（243 同 API，FindPopupPanel/EditorSearchSession 同款），原生框+原生钮一步到位；
  - 结论：**S1 成立**，243 可独立实例化、状态可经 FindModel 读写。
- **P2**：SearchTextArea 的 regex/matchCase 态可程序化读写（供既有过滤链与单测驱动）。
- **P3（缩小后）**：243 已反汇编证实；253/262 只需 verifyGate 编译通过 + 沙盒冒烟（API 面极小：`SearchTextArea(JTextArea,boolean)`、`setExtraActions(AnAction...)`、`ToggleRegex/ToggleMatchCase(FindModel)`，均为稳定 find 包 API）。
- **P4**：日志页（SqlLogPanel）搜索条与历史页结构不同，两处改造互不影响、过滤链回归为零。
- **P5**：`.*`/`Cc` 的既有定向测试（T-HistorySearchToggles 交付的用例）在样式改造后仍可驱动（测试缝 `regexToggleForTest` 等需同步迁移）。

## 五、验收清单（拟）

- **V1（真机观感）**：历史/日志两处搜索框 = 原生 Ctrl+F 形态（内嵌图标切换、选中态、无独立带框按钮），截图对照。
- **V2（行为回归）**：regex 关 `^SELECT`=0 条 / 开=出条目；`Cc` 开=小写 0 条 / 关=命中；`regex:` 前缀或语义不变（既有定向全绿 + 真机抽查）。
- **V3**：`testFast`（或相关定向）零回归 + verifyGate 三目标。
- **V4**：日志页仅 MatchCase、历史页 Regex+MatchCase 的差异保持。

## 六、影响面 / 不做

- 不改过滤语义/memo 键/`regex:` 前缀语义/MCP 历史检索；不改持久化（会话级开关态）。
- 不做 words/全词开关；不放开日志页正则（D2 默认不做，如要做另立增量）。

## 七、运行读数

（实施时回填：定向测试读数、反向验证读数、真机观感截图对照）

## 八、实施记录（20261009 深夜，主控亲跑）

### 平台反汇编（P1 证实，243 app-client.jar）

- `com.intellij.find.SearchTextArea`：公开类（非 internal），构造 `SearchTextArea(JTextArea, boolean multiline)`——可包我们自己的 JTextArea 直接实例化。
- 原生开关钮本质 = `editorHeaderActions.ToggleRegex / ToggleMatchCase`（CheckboxAction；Regex 含 Regex/RegexHovered/RegexSelected 三态图标 + tooltip + 正则帮助链接），从 `AnActionEvent.getData(SearchSession.KEY)` 取会话，抽象契约只有 `isSelected(SearchSession)` / `setSelected(SearchSession, Boolean)`，底层落到 `FindModel.isRegularExpressions / isCaseSensitive`。
- 内嵌入口 = `SearchTextArea.setExtraActions(AnAction...)`（返回 List，供 icons panel 布局）。
- 平台自己（EditorSearchSession）的用法 = `SearchReplaceComponent.Builder.buildFor(project, component, session)` → `addPrimarySearchActions(ToggleMatchCase, ToggleWholeWordsOnly, ToggleRegex)` → `build`；FindPopupPanel 直接 `new SearchTextArea(JTextArea, false)`。
- 结论：**S1 成立**，243 可独立实例化、状态经 FindModel 读写。

### 实施

- 新增 `ui/search/NativeSearchTextField.kt`：自有 `FindModel`（不与 IDE 全局 Find 串线）+ 极小 `SearchSession` 实现（getFindModel/getComponent 最小契约）+ `SearchTextArea(JTextArea, false)` + `setExtraActions(ToggleMatchCase, ToggleRegex)`（顺序对齐原生 Ctrl+F）；暴露 `getTextField()`（挂 DocumentListener）与 `getFindModel()`（读两旗）。
- `HistorySqlLogPanel`：搜索框迁 NativeSearchTextField；regex/matchCase 两旗改从 `getFindModel()` 读，DocumentListener 喂既有 `HistoryRegexMemo`（键=源串+matchCase）与文件/条目过滤；`regexToggleForTest`/`matchCaseToggleForTest` 测试缝改代理 FindModel 旗（断言零语义变化）。
- `SqlLogPanel`：同样迁移；matchCase 单旗从 FindModel 读（不传 ToggleRegex ⇒ 原生不显示正则钮，D2 保持「日志页无正则」拍板）。
- 删除两个 `JToggleButton` 与其边框/悬停处理（原生钮接管形态）。

### 读数

- 定向：T-HistorySearchToggles 既有用例迁移后全绿（regex 两态/Cc 两态/`regex:` 前缀或语义）。
- 反向验证：摘除 SearchSession 装配（DataContext 不供 KEY）⇒ 开关钮失效/不出现（红），还原复绿。
- testFast（--rerun-tasks 强制全跑）：46/0/0/0（HistorySearchFilterTest + SqlLogPanelFilterTest +
  NativeSearchTextFieldProviderContractTest 三类；其余 up-to-date 见构建产物）。verifyGate 见下。
- 迁移中补发现：SqlLogPanelLayoutTest 的「搜索条默认隐藏」断言落在 searchField.isVisible 上，
  随可见性契约迁到宿主 searchBar（searchFieldVisibleForTest），断言语义不变。
- 真机（runIde 243 沙盒）：两处搜索框观感=原生 Ctrl+F，regex/Cc 两态行为截图对照在册。

### 遗留

- 原生组件的多行/历史弹层能力未启用（withMultilineEnabled、ShowSearchHistory 不接）。
- 若 253/262 出现 API 漂移，fallback=类内部换 S2 复刻（对外接口不变）。

### 框体修正轮（20261010 凌晨，用户真机指出两处缺陷后修复）

- **缺陷**（用户指出+真机复现）：①SearchTextArea 裸组件自绘 PseudoSeparatorBorder（新 UI 下仅一条淡分隔线），与 IDEA Ctrl+F 的圆角框体不一致；②点图标区焦点不进输入区（输入落空误触 Search Everywhere）。
- **修正**：NativeSearchTextField 改走平台 `SearchReplaceComponent.Builder.buildFor(project=null, targetComponent, session)` 全套——原生框体/边框/焦点处理由平台接管（EditorSearchSession 同款装配）；`withShowOnlySearchPanel()` 去 replace 行；开关钮经 `addSearchFieldActions(ToggleMatchCase[, ToggleRegex])` 内嵌（vararg 形态，Builder 真实 API；此前误按 addPrimarySearchActions/数组形态调用均被编译器拦下）；SearchReplaceComponent 自身应答 SearchSession.KEY（本类兜底自答同源）；文本组件 requireNotNull 断言为 JBTextArea（平台变更即启动期显式失败，不静默降级）。
- **读数**：定向 46/0（含 ProviderContractTest 正反向）；testFast --rerun-tasks 46/0/0/0；verifyGate 三目标 Compatible。
- **焦点引导**：SearchReplaceComponent 原生处理点框聚焦；面板侧 showSearch 改 `component.requestFocusInWindow()`。
