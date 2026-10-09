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

- **P1**：243 平台 `com.intellij.find.SearchTextArea` 的可见性/构造与状态读写（读 243 源码 jar：`SearchTextArea`、`EditorSearchSession`、`FindPopupPanel` 的用法；确认是否 internal 及其依赖面）。
- **P2**：SearchTextArea 的 regex/matchCase 态可程序化读写（供既有过滤链与单测驱动）。
- **P3**：三版本一致性——243/253/262 的该 API 面相同（verifyGate 三目标 + 必要时反射探测）。
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
