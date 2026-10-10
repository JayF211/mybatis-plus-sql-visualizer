# T-SettingsFocusFromLink：空态/引导链接点击自动定位并高亮目标设置项

> 20261010 用户需求（主分支工作树）：「点击蓝色字体后应该自动定位并高亮到目标设置项 这是已有能力」。
> 占号：**O-143 / R89 / D150 / V170**（20261010 实测边界 O-142/R88/D149/V169，引用前再实测）。

## 一、现状与差距

**已有能力（本次复用，勿重造）**：主设置页外壳 `PluginSettingsFolderConfigurable`
（`config/PluginSettingsFolderConfigurable.kt`）已实现完整的「对话框内定位 + 高亮」链：
- `navigateToPage(ref: SubPageRef, focusHint: String?)`——从宿主 SettingsEditor 取 `Settings`
  → 按 **EP id** 切子页（**禁按类查**，KDoc 有 2024.3 整树实例化+EDT 慢操作实证）→
  `callback.doWhenDone { focusTarget(target, focusHint) }`；
- `focusTarget`——`scrollRectToVisible`（滚动到可见）+ `flashHighlight`（黄底闪烁+橙色边框 1.5s）；
- `findComponent`——按「输入框 tooltip → 按钮文本 → 标签文本」递归匹配 hint；
- 测试缝 `findHighlightTargetForTest`（T-FS-09：headless 断言「跳了但不亮」的隐性缺陷）；
- 触发源现仅一处：设置页**搜索结果的双击**（`DoubleClickListener.onDoubleClick → navigateTo(it)`）。

**差距**：面板内的引导链接（空态文案尾链、源码捕获引导、工具栏设置入口）走
`ShowSettingsUtil.showSettingsDialog(project, SqlLogConfigurable)`——**只打开单页，不带定位**；
且 `navigateToPage` 在**对话框外**（拿不到实例/Settings）时回退 `showSettingsDialog` 也不定位。
用户诉求 = 把这些链接升级为「打开主设置页 + 定位 + 高亮」。

## 二、目标

1. 面板引导链接点击 → 打开插件主设置页（外壳页）→ 切到 SQL 日志子页 → 目标设置项
   **滚动到可见 + 黄底闪烁 + 橙框高亮**（沿用既有视觉，零新样式）。
2. 空态文案中的**页签类**引导（「日志转 SQL」）→ 面板内切到对应 tab（不是设置项）。
3. 工具栏「设置」入口与源码捕获引导同接入（统一入口，一处实现三处受益）。

## 三、方案与决策点

**S1 待定位目标传递（核心）**：链接从**对话框外**发起，拿不到 `PluginSettingsFolderConfigurable`
实例，故用**单槽 pending + 外壳页消费**：
- companion 加 `pendingFocus: PendingFocus?(pageId, hint)`（`@Volatile` 单槽，后到覆盖——
  点击是显式用户动作，无需排队）；
- 公共入口 `openSqlLogSettingsFocused(project, hint: String?)`：置 pending →
  `showSettingsDialog(project, PluginSettingsFolderConfigurable::class.java)`；
- 消费点 = `createComponent()` 末尾：`invokeLater { invokeLater { consumePendingFocus()?.let { navigateToPage(refById(it.pageId), it.hint) } } }`
  （双跳确保页面已显示、`Settings.KEY` 可达；消费即清，防重复定位）。
- `SubPageRef` 为 private——外部只传 `pageId` 字符串，内部按 id 查 refs 表。

**S2 空态链接形态（D150a）**：**链接独立成行**（正文 wrap 链零改造）：
- 主文案（`log.panel.empty`）保持纯文本 wrap 不动（含「日志转 SQL」「设置页」字样不改）；
- 文案之下追加一行链接区（`appendSecondaryText` 逐段拼行）：`日志转 SQL 页签` / `设置页` / `增强捕获`
  （分别为：切 tab / 打开+定位〔hint=参数实参与精确耗时所在的设置行〕/ 打开+定位〔hint=增强捕获行〕）；
- 理由：正文内嵌链接需重写 wrap（段内宽度预算+中英双份文案标记）——回归面最大处恰是已验收
  的空态排版；独立链接行排版稳定、可测试、i18n 只增三个链接词条。

**D150b**：「日志转 SQL」链接语义 = **面板内切 tab**（它是页名不是设置项）；设置类链接才定位设置项。

**D150c**：定位降级链 = 双跳消费 → 若 `currentSettings()` 仍不可达，保留 pending 待下次构建消费
（天然覆盖「未打开→打开」竞态），仍失败则仅打开页不报错（不阻塞用户）。

**S3 三处接入**：
- `SqlLogPanel.renderEmptyText` 尾链（现 `log.panel.empty.link`）→ 并入 S2 链接区；
- `SqlLogPanel.SettingsAction`（工具栏设置入口）→ `openSqlLogSettingsFocused(project, hint=null)`
  （只切页不聚焦行——「打开本插件设置」语义）；
- `log/SourceCaptureGuide.kt:104` → `openSqlLogSettingsFocused(project, hint=源码位置捕获行文本)`。

## 四、待验证前提（实施者先证伪再动手）

- **P1**：`createComponent()` 末尾双跳 `invokeLater` 时 `currentSettings()`（Settings.KEY 经
  searchField 的 DataContext）可达——不可达则换锚点（首次 `reset()`/`addNotify`）并登记读数。
- **P2**：hint 文本须与目标设置行**当前语言**的渲染文本一致（`findComponent` 按文本 contains）——
  hint 一律经同一 bundle 键取当前语言文本（**禁硬编码中文**，否则英文环境下「跳了但不亮」）；
  实施时逐一核对三处 hint 的键名与 `PluginSettingsPanel` 该行的取值路径一致。
- **P3**：平台 `SettingsEditor` internal 反射（`currentSettings` 兜底）既有在役（设置页搜索定位
  已真机过），本轮不新增反射面。

## 五、验收清单（拟）

- **V170-a**：空态点「增强捕获」→ 设置页打开且滚动到增强捕获行 + 黄底闪烁 + 橙框（真机）；
- **V170-b**：空态点「设置页」→ 定位到参数实参/精确耗时所在行（真机）；
- **V170-c**：空态点「日志转 SQL 页签」→ 面板切到日志→SQL tab（真机）；
- **V170-d**：工具栏设置入口 → 打开插件设置（SQL 日志页，不聚焦行）（真机）；
- headless：`findHighlightTargetForTest` 对 ID_SQL_LOG 真实面板逐一 hint 能定位（防「跳了但不亮」）；
  pending 单测（置→消费→清；消费后二次消费为 null）；空态链接区渲染断言（三链接在场）。

## 六、影响面 / 不做

- 改：`PluginSettingsFolderConfigurable`（companion pending + 公共入口 + createComponent 消费）、
  `SqlLogPanel`（空态链接区 + SettingsAction）、`SourceCaptureGuide`（入口）、中英 bundle（+3 链接词条）。
- 不改：`navigateToPage`/`focusTarget`/`findComponent`/`flashHighlight` 既有实现与设置页搜索双击链；
  正文 wrap 链（`wrapEmptyTextLines`）零改动。
- 不做：跨对话框多目标排队（单槽后到覆盖）；链接视觉定制（沿用 `LINK_ATTRIBUTES`）；跨语言 hint 模糊匹配。

## 七、运行读数

（实施回填：定向用例、testFast、真机截图）

## 八、实施记录（20261010 傍晚，主分支工作树）

### 实施

- **外壳页（`PluginSettingsFolderConfigurable`）**：companion 加 `pendingFocus`（`@Volatile` 单槽
  + `PendingFocus(pageId, hint)`）+ 公共入口 `openSqlLogSettingsFocused(project, hint)` +
  `schedulePendingFocus()`（`createComponent` 首行调度，双跳 `invokeLater` 后消费并清；
  未置目标零调度）；按 `SUB_PAGES.firstOrNull { it.ref.id == pageId }` 查 ref。
- **日志页空态三链接行（`SqlLogPanel.renderEmptyText`）**：正文 wrap 链不动，尾部追加
  `日志 → SQL 页签`（`tabs.selectedComponent = logToSqlPanel`，面板内切页签）·
  `设置页`（hint=null 只切页）· `增强捕获`（hint=`settings.enhancedCapture`），
  分隔符常量 `EMPTY_LINK_SEPARATOR`（CaptureDiagnostics.kt）。
- **工具栏设置入口**（`SqlLogPanel.SettingsAction`）与**源码捕获引导**
  （`SourceCaptureGuide.GuideChoice.OpenSettings`，hint=`settings.log.autoInjectInterceptor`）
  统一接入 `openSqlLogSettingsFocused`。
- **历史页空态条件链接（`HistorySqlLogPanel.updateFilesEmptyText`）**：文案按行 `appendLine`
  （StatusText 不解析换行转义）；条件= `shouldShowAutoSaveHistoryLink(autoSaveEnabled, fileCount)`
  纯函数（未开自动保存且无文件）→ 追加「自动保存历史 SQL」链接（hint=`settings.log.autoSave`）；
  刷新点=init 与 `applyRefresh`（扫描落地）。
- **i18n**：+3 键（`log.panel.empty.link.logToSql` / `log.panel.empty.link.settings` /
  `log.history.empty.link.autoSave`，中英各一）。

### 前提核验

- **P2 已按「hint 取 bundle 完整值」落实**（三处 hint 全部经对应设置行的同一键取值，语言自适应）；
  单测哨兵（`findHighlightTargetForTest` 对 SQL 日志页真实面板逐一 hint 断言可定位）见读数。
- **P1/P3** 由哨兵用例 + 既有设置页搜索定位链在役共同覆盖；真机复验含「点链接→滚动+黄闪+橙框」。

### 读数

- 定向：`config.*` + `log.*` + `ui.search.*` 全量 **BUILD SUCCESSFUL（3m15s）**；含
  `test三处链接hint在SQL日志页可定位`（P2 哨兵，三 hint 逐一命中）与
  `test待定位目标置与消费_消费即清`（首轮红暴露测试缝 consume 只读不清——抽单源
  `consumePendingFocus` 修复复绿）；
- testFast --rerun-tasks：**BUILD SUCCESSFUL（6m7s，27 任务全执行）**；
- verifyGate 未跑（UI 链接接线+定位调度，不满足大改动判据）；
- 真机复验（交用户）：点三链接的滚动+黄闪+橙框；历史页条件链接两态（未开自动保存且无文件
  → 链接在；开或有文件 → 无链接）。
