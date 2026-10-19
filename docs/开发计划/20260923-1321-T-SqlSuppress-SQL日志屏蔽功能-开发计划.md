# T-SqlSuppress（O-69）SQL 日志屏蔽（保活心跳类 SQL 静默）— 开发计划

> 需求来源：用户 2026-09-23。保活心跳 SQL（如 `SELECT 1`）持续刷屏 SQL 日志列表，期望在列表右键【屏蔽】，并有独立的管理入口（含 SQL 语法高亮）。
> 状态：已立案（O-69，2026-09-23 用户拍板——§九 D1~D6 全按推荐定案，D7 工具栏图标选漏斗），待实施。

## 一、现状核实（实测 2026-09-23 13:10~13:20，两路探索 + 主控逐条复核出处）

### 1.1 列表与右键菜单

- 日志列表是 `JBList<LogSqlEntry>`（非表格），面板 `SqlLogPanel`（`src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt:345`），数据侧缓冲 `SqlLogEntryBuffer`（纯逻辑类，KDoc 自述可无平台 JUnit 测试，`SqlLogEntryBuffer.kt:21`）。
- 右键菜单**代码内构建**（不在 plugin.xml）：`showContextMenu`（`SqlLogPanel.kt:1666~1696`）现建 `DefaultActionGroup` → `createActionPopupMenu("mpvs.log.list", group)`；现有菜单项为内部类 AnAction（复制 SQL/复制时间戳/删除/发送双 tab/跳转双目标/聚焦双维），新增「屏蔽」照抄同一形态即可。
- 工具栏动作组同为代码内构建：`toolbarGroup = DefaultActionGroup(capture, Clear, Settings, ShowSearch, Help)`（`SqlLogPanel.kt:566~572`）；`SettingsAction` 用 `ShowSettingsUtil.showSettingsDialog(project, SqlLogConfigurable::class.java)` 跳设置子页（`:1126~1133`）。

### 1.2 入队闸门与规范化指纹（本需求的承重墙）

- 入队门序：`addEntryInternal`（`SqlLogEntryBuffer.kt:129~131`）＝暂停检查 → `isFiltered(restoredSql)`（关键字丢弃）→ 去重 → 上限裁剪。审计入队 `addAuditEntry` 走同一私有方法，其 KDoc（`:125`）明确「关键字过滤/去重/上限是列表展示偏好，不是开关」——**屏蔽闸门属同类，对两条入队通道一并生效**，无需区分。
- 既有的「捕获级关键字屏蔽」：设置项 `logFilterKeywords`（`PluginSettings.kt:264`）经 `SqlLogManager.applySettings()` 同步进缓冲（`SqlLogManager.kt:482~486`），命中即入队前丢弃。但它要求用户手抄关键字到设置页，无右键入口、无规则管理、无高亮——本任务补的正是这段体验。
- 匹配键现成：`LogSqlEntry.dedupeKey = sqlFingerprint(normalizeSqlWhitespace(restoredSql))`（`LogSqlModels.kt:150`，lazy 缓存）；规范化＝trim + 连续空白折叠单空格（`:187`，大小写与分号不动）；指纹＝FNV-1a 64 位（`:173`，仓内既有口径已接受 2^-64 碰撞量级）。**屏蔽匹配直接复用这套，空白差异的心跳 SQL 变体天然命中。**

### 1.3 过滤管线与刷新

`refreshList()`（`SqlLogPanel.kt:1398` 附近）：snapshot → 类型过滤 → 聚焦过滤 → 聚焦页签 → 搜索 → 排序 → 差分渲染（`applyIncremental`，失败回退全量重建）。屏蔽的存量过滤插在最前（与类型过滤同级），规则变更后主动触发 `refreshList()` 即可让已捕获的心跳行立刻消失。

### 1.4 管理界面可照抄的样板

- 「只读 SQL 高亮编辑器」标准写法：`SqlClickPreview.buildContent`（`SqlClickPreview.kt:206~233`）＝`EditorFactory.createDocument/createEditor` + `(editor as? EditorEx)` 挂 `LexerEditorHighlighter(SqlListSyntaxHighlighter(), globalScheme)`（不依赖 Database 插件，缺席环境仍高亮）+ settings 定外观；注释明确**调用方负责释放 editor**。管理对话框复用同一写法，行号按需求截图开 `isLineNumbersShown = true`。
- 带编辑器的对话框先例：`ParamImportDialog`（`ui/ParamImportDialog.kt:40~106`，DialogWrapper + Disposable + 内嵌 editor）。
- 持久化先例：`SqlHistoryManager`＝`@Service(Service.Level.APP)` + `@State(name, Storage(..., RoamingType.DISABLED))` + `State(MutableList<Bean>)`，bean 全基本类型/String 字段（`SqlHistoryManager.kt:22~36`、`SqlHistoryEntry.kt:26~45`）。应用级必须 `RoamingType.DISABLED`（Settings Sync 回滚坑，`PluginSettings.kt:18~21` KDoc）。
- i18n：`messages/MpSqlVisualizerBundle.properties` + `_zh` 双文件，键形如 `log.panel.copy`；工具窗 UI 走插件语言 `MpSqlVisualizerBundle.message(...)`。
- 测试落点：`SqlLogEntryBufferTest`、`SqlLogPanelFilterTest`、`LogSqlModelsTest` 已在 `src/test/kotlin/.../log/`。

## 二、管理入口论证（用户点名分析项）

三个候选：

| 候选 | 优点 | 缺点 |
| --- | --- | --- |
| A. 工具窗口工具栏「屏蔽管理」动作 | 与日志场景绑定最紧，和 清空/设置 同排，一键直达；DialogWrapper 自持生命周期 | 需先打开工具窗（屏蔽本身就是日志场景操作，不成立缺点） |
| B. 列表右键「管理屏蔽规则…」 | 从「屏蔽此 SQL」相邻位出发，路径最短 | 菜单已有 9 项，只放管理入口不放编辑功能，不加重 |
| C. Settings → Tools → SQL Visualizer → SQL 日志子页内嵌管理区 | 全局设置习惯位置 | 管理界面核心是「规则列表 + 高亮编辑器」，而设置面板是手摆 Swing + `isModified/apply` 全量委托模型（`SectionConfigurable.kt:24~46`），内嵌 EditorEx 要自管 Disposer 且 Settings 关闭时机不可控，泄漏风险高；且属低频操作，不值得占设置树一页 |

**推荐：A（主）+ B（辅）双入口，打开同一个非模态对话框；C 不做。** 设置页仅在 `logFilterKeywords` 输入框附近维持现状，不加跳转按钮（若用户要可后补，成本低）。

## 三、方案

### F1 屏蔽规则模型与持久化 —— 新增 `SqlSuppressRuleManager`

- `@Service(Service.Level.APP)` + `@State(name = "MpSqlVisualizerSqlSuppressRules", storages = [Storage("mp-sql-visualizer-suppress.xml", roamingType = RoamingType.DISABLED)])`，独立存储文件避免与主设置互踩写盘。
- 规则 bean（XmlSerializer 友好，照 `SqlHistoryEntry` 形态）：`id`（UUID）、`pattern`（**规范化文本**，存储与展示口径，人可读、XML 可查）、`sampleSql`（原始还原 SQL，管理界面高亮展示用）、`createdAt`。上限 500 条，超限拒新增并提示（防无界增长）。
- 运行时匹配集：`@Volatile suppressedKeys: Set<String>`＝各规则 `sqlFingerprint(pattern)` 的不可变集合；增删规则时整体替换发布。缓冲侧只读该集合，命中判据 `entry.dedupeKey in set`（复用 lazy 指纹缓存）。
- 变更通知：监听器列表（照 `SqlLogEntryBuffer.addListener` 形态），供各项目 `SqlLogManager` 订阅重算缓冲集合并触发面板刷新。
- 内存命中计数：`ConcurrentHashMap<ruleId, AtomicInteger>`，闸门命中时 +1，仅会话内有效不持久化；管理对话框展示「已拦截 N 次」，用于确认规则有效。

### F2 缓冲入队拦截 —— `SqlLogEntryBuffer`

- 新增 `@Volatile var suppressKeys: Set<String> = emptySet()`（由 manager 订阅者写入）。
- `addEntryInternal` 在 `isFiltered` 之后（`:131` 旁）加一道：`if (entry.dedupeKey in suppressKeys) return false`。被屏蔽条目不进去重计数、不占捕获序号、不触发任何回调——语义与关键字过滤一致（「从未捕获过」）。
- 纯逻辑改动，`SqlLogEntryBufferTest` 直接覆盖。

### F3 面板存量过滤 —— `SqlLogPanel.refreshList()`

- 管线最前加 `filterBySuppress(snapshot)`：按同一 `suppressKeys` 集合滤 `dedupeKey`。屏蔽动作发生后主动 `refreshList()`，列表里已存在的心跳行**立即消失**；删除规则后仍在缓冲内的条目会回来（已入队丢弃的不可恢复，UI 文案须写明）。

### F4 右键「屏蔽此 SQL」

- 内部类 `SuppressSqlAction(entries)` 加进 `showContextMenu` 的 group（置于「删除」之后），支持多选（去重后的不同指纹各成一条规则，已存在则跳过）。
- 动作后：规则入库 → 缓冲集合更新 → `refreshList()` → balloon 反馈「已屏蔽 N 条 SQL，后续不再显示」，balloon 附「管理屏蔽规则…」超链接动作直达对话框。

### F5 管理对话框 —— 新增 `SqlSuppressManagerDialog : DialogWrapper(project, true), Disposable`

- 主从布局：左侧规则列表（`JBList<SqlSuppressRule>` + 渲染器显示 pattern 摘要/创建时间/命中计数），右侧只读高亮编辑器展示选中规则的 `sampleSql`（照抄 `SqlClickPreview.kt:206~233` 写法，`isLineNumbersShown = true` 对齐需求截图外观）；切换选中用 `runWriteAction { document.setText(...) }` 换文，不重建 editor。
- 操作：新增（弹出可编辑空 editor，粘贴 SQL 后确认，按规范化文本成规则）、删除（单条）、清空（确认后全清）、关闭即生效（规则变更实时经 F1 通知链落地，不设「确定/应用」缓存层——避免与持久化服务双源）。
- Disposer：`Disposer.register(dialog.disposable) { EditorFactory.getInstance().releaseEditor(editor) }`，守「Memory leak detected」红线。
- 非模态（`DialogWrapper(project, false)` 亦可，实施时对照既有对话框定）；记住尺寸不做，跟既有对话框形态走。

### F6 工具栏「屏蔽管理」动作 —— `SqlLogPanel.toolbarGroup`

- `ManageSuppressAction` 插在 `SettingsAction` 旁，文案 `log.suppress.manage`。
- 图标候选已实测（2026-09-23 从本机 IU 262.10968 `lib/intellij.platform.ide.jar` 提取真实 SVG 并渲染比对，字段名 javap 实证；预览页 `%TEMP%\mpsv-icon-preview\preview.html`/`preview.png`）：主推 `AllIcons.General.Filter`（漏斗，16px 最可读；全栏无漏斗形——类型过滤/排序均为文本+`ArrowDown` 按钮，不撞形）；备选 `AllIcons.Toolbar.Filterdups`（去重过滤，语义贴题但与既有「移除重复的sql」心智模型相撞）、`AllIcons.RunConfigurations.ShowIgnored`（中性禁止圈）。`AllIcons.Debugger.MuteBreakpoints` 实渲染为红色，排除。**已定案（D7）：`AllIcons.General.Filter`（漏斗）**。

### F7 i18n 与通知组

- 键清单（en/zh 双文件同步）：`log.suppress.action`（屏蔽此 SQL）、`log.suppress.actionSelected`（屏蔽选中的 SQL（{0} 条））、`log.suppress.manage`（管理屏蔽规则…）、`log.suppress.added`（balloon 标题/正文）、`log.suppress.dialog.*`（标题/列头/新增/删除/清空/确认/命中计数/不可恢复说明）、`log.suppress.limit`（超上限提示）。
- balloon 通知组：新增 `<notificationGroup id="cn.song.mybatisplus.visualizer.logsuppress"/>` 一行声明（plugin.xml 仅此一行，非 EP 非 since-build，不构成构建校验触发条件）；若实施者比对后认为复用现有三组之一更合规（plugin.xml:286~301），按 D5 定案执行。

## 四、待验证前提（实施者先证伪，勿直接照抄）

1. **对话框内嵌 editor 的生命周期**：`DialogWrapper` + `Disposable` + `EditorFactory.releaseEditor` 组合的准确挂点——先读 `ParamImportDialog` 与 `SqlClickPreview` 的释放路径再落笔，别凭本文档转述。
2. **增量刷新对规则变化不可感知**：`applyIncremental` 差分只认缓冲快照变化，规则变更必须显式触发 `refreshList()`——实施时验证「屏蔽后存量行立即消失」确实发生（真机或面板测试），不能只测缓冲层。
3. **balloon 通知组归属**：读 plugin.xml:286~301 现有三组的文案归属，确认新增独立组是常规做法；`notificationGroup` 声明对 `PlatformSignatureCrossVersionTest` 无涉（非反射入口），实施者顺手确认该测试无需登记。
4. **多项目并存**：规则是应用级、缓冲集合是项目级——验证两个项目窗口同时开着时，改规则后两个窗口的 `SqlLogManager` 都收到通知（订阅挂 `SqlLogManager` 生命周期，project close 时注销）。
5. **`entry.dedupeKey` 在闸门处的可用性**：lazy 属性首次访问会在入队线程计算指纹（含一条 regex），热路径成本可接受——与既有去重路径同键同成本，实施时确认无额外分配放大。

## 五、验收标准

1. 闸门（`SqlLogEntryBufferTest` 追加）：注册规则后 `addEntry` 返回 false 且列表不含该条目、`addAuditEntry` 同样被拦；移除规则后恢复 true；被拦条目不产生去重计数、不占序号。
2. 归一化（`LogSqlModelsTest` 或新增用例）：`SELECT 1` 与 `select  1`（多空白/首尾空白）命中同一规则；大小写不同**不**命中（与既有规范化口径一致，写进用例注释防将来口径漂移）。
3. 面板过滤（`SqlLogPanelFilterTest` 追加）：缓冲中已有心跳行时注册规则，`refreshList` 输出不含匹配行；移除规则后复现。
4. 规则管理（manager 测试）：CRUD + 持久化 round-trip（`loadState/saveState`）+ 上限 500 拒绝 + 命中计数累加。
5. **反向验证**：注释掉 `addEntryInternal` 的屏蔽判定行 → 用例 1 必红，贴两次读数（绿/红）进实施记录。
6. i18n：en/zh 双文件键齐全；若仓内有 bundle 完整性守卫测试则一并跑。
7. 定向测试集：`SqlLogEntryBufferTest`、`SqlLogPanelFilterTest`、新增 `SqlSuppressRuleManagerTest`、`LogSqlModelsTest`——按「测试三档分层」纪律只跑定向，不跑全量、不跑构建校验（本期不动 build.gradle.kts / EP / since-build，非大改动）。

## 六、风险

- **指纹碰撞**：FNV-1a 64 位，2^-64 量级，仓内去重已接受同一口径，不另设防。
- **恢复语义落差**：入队已丢弃的条目解屏蔽后不可恢复（缓冲层丢弃），管理对话框与 balloon 文案明示「仅对后续 SQL 生效，列表中已有条目会立即隐藏」。
- **editor 泄漏**：对话框关闭未释放 editor 触发平台 Memory leak 检测——按 F5 的 Disposer 挂法规避，真机验证一次开合。
- **多项目通知遗漏**：某窗口未订阅成功则该窗口规则不生效——待验证前提 4 覆盖。
- **误屏蔽**：右键一键成规则可能误伤相似业务 SQL（同为 `SELECT 1` 形态）。缓解：管理对话框一键删除即恢复；命中计数帮助发现异常命中量。

## 七、实施顺序（单代理可收口，改动面集中、无并行必要）

1. F1 规则模型 + manager + 纯逻辑测试（先行落基础件）。
2. F2 缓冲闸门 + 测试 + 反向验证（贴绿/红读数）。
3. F3 面板存量过滤 + F4 右键动作 + `SqlLogPanelFilterTest` 追加。
4. F5 管理对话框 + F6 工具栏入口（UI 层，参照样板）。
5. F7 i18n 双文件 + （按 D5）通知组声明。
6. 定向测试收口，实施记录回填**实施代理自己工作树**的计划副本（主仓副本只读）。

## 八、不做的事

- 关键字/正则模式的屏蔽规则（关键字路线已有 `logFilterKeywords`，正则本期不做）。
- 按数据源/项目级作用域（规则全局生效，见 D3）。
- Settings 子页内嵌管理区（见 §二，D1 可后补跳转按钮）。
- 屏蔽命中徽章进日志列表（⨉N 是去重语义，不混用；命中计数只在管理对话框）。
- 规则导入导出/云同步。

## 九、定案与推荐（2026-09-23 用户拍板：D1~D6 全按推荐执行，另定 D7 图标）

- **D1 管理入口** → 定案（按推荐）§二结论：工具栏动作（主）+ 右键「管理屏蔽规则…」（辅）双入口开同一对话框；Settings 不加。若用户日后希望 Settings 也有入口，加一个跳转按钮即可，另说。
- **D2 匹配口径** → 定案：「还原 SQL 空白规范化后精确匹配」（指纹比较，复用 dedupeKey 口径）；大小写敏感与既有规范化一致。
- **D3 作用域** → 定案：Application 级全局（心跳 SQL 本就跨项目通用，与 logFilterKeywords/历史记录一致；`RoamingType.DISABLED` 不进同步）。
- **D4 屏蔽反馈** → 定案三层：列表存量行立即消失（最强反馈）+ balloon 可直达管理 + 管理对话框显示「已拦截 N 次」内存计数。
- **D5 通知组** → 定案：新增 `cn.song.mybatisplus.visualizer.logsuppress` 组（plugin.xml +1 行）；实施中若发现复用现有三组之一（plugin.xml:286~301）更合规，允许改并在实施记录里说明。
- **D6 规则上限** → 定案：500 条，超限拒新增并提示（先到先得，不做 LRU 淘汰——静默淘汰会让「屏蔽突然失效」无从排查）。
- **D7 工具栏图标** → 定案：`AllIcons.General.Filter`（漏斗；262 jar 实渲染比对后用户选定，字段名 javap 实证，见 F6）。

## 实施记录

（本节由实施代理回填其工作树副本：读数、读数出处、过程中的干扰项、未做项。以下为 T-SqlSuppress 实施代理 2026-09-23 回填，工作树 mpsv-sql-suppress @ t-sql-suppress。）

### 0. 提交清单（本工作树，全部未 push 未 merge）

| SHA | 内容 |
| --- | --- |
| f7ece3b | F1 SqlSuppressRule bean + SqlSuppressRuleManager + SqlSuppressRuleManagerTest（13 例） |
| 3a9b42b | F2 SqlLogEntryBuffer 屏蔽闸门 + BufferTest 追加 5 例 |
| 43db365 | F5 SqlSuppressManagerDialog（左列表 + 右只读高亮 editor） |
| 0839b40 | F3+F4+F6 面板存量过滤 / 右键 SuppressSqlAction / 工具栏 ManageSuppressAction + SqlLogManager 订阅桥 |
| c636856 | F7 i18n en/zh 21 键 × 2 + plugin.xml logsuppress 通知组 |
| b050e80 | SqlLogPanelFilterTest 追加 4 例（含全链路面板用例）+ LogSqlModelsTest 追加 2 例 |

### 1. §四 五条待验证前提逐条证伪结论

1. **对话框内嵌 editor 生命周期**（读 `ParamImportDialog.kt:44~106、238~242` 与 `SqlClickPreview.kt:159~186`）：DialogWrapper 的成熟路径 = 覆写 `dispose()`，先 `EditorFactory.releaseEditor` 置 null 再 `super.dispose()`（平台 close() 处置自身 myDisposable 时回调）；SqlClickPreview 的 `JBPopupListener.onClosed` 释放口只适用于弹窗，不适用于对话框。N3 教训（`ParamImportDialog.kt:81~87` 注释）确认：挂平台监听的父 disposable 必须用 `disposable` 而非 `this`——本对话框无平台监听，KDoc 留防回归记录。**结论：按 ParamImportDialog 路径落笔，计划 F5 原文「Disposer.register(dialog.disposable){...}」的形态被更简的既有定例取代**。
2. **增量刷新对规则变化不可感知**（读 `SqlLogPanel.refreshList()` 1381~1416：差分只比对 lastRendered vs newVisible，无任何规则感知）：证实必须显式触发。落法：SqlLogManager 的 suppressRuleListener 里规则变更 → 同步 buffer.suppressKeys + `notifyUi()`（invokeLater 幂等 flush → 面板 refreshList）；右键动作额外直调 `refreshList()` 保证即时反馈。**「屏蔽后存量行立即消失」已由面板级全链路用例 `testRegisterRuleHidesExistingHeartbeatRowsViaPanelRefresh` 证实（非只测缓冲层）**。
3. **balloon 通知组归属**（读 plugin.xml:286~307 + `PlatformSignatureCrossVersionTest` 源码）：既有三组各属独立域（updates 更新检查 / preview 预览 toast / feedback 错误上报），且每组都带 bundle+key 本地化。**D5 最终选择：新增独立组 `cn.song.mybatisplus.visualizer.logsuppress`（plugin.xml +5 行含注释，key `notification.group.logsuppress` 进双 bundle）**。理由：屏蔽反馈复用任一现有组都会张冠李戴（组名与功能不符，用户按组过滤通知时错乱），照「一域一组」既有惯例新增最合规；notificationGroup 非 EP 反射入口，`PlatformSignatureCrossVersionTest` 只核 update 动态安装 API（`src/test/.../update/PlatformSignatureCrossVersionTest.kt:26~46`），确认无需登记。
4. **多项目并存**：结构保证——SqlSuppressRuleManager（APP 级）监听器列表；每个项目窗口的 SqlLogManager 构造时 `addListener` + 初始化同步一次，`dispose()`（project close）`removeListener`。规则变更时每个窗口实例各自收到通知并同步各自缓冲 + 刷新各自面板。**验证受限：headless（BasePlatformTestCase 单 project）无法起两个项目窗口实测**；以代码结构保证 + 纯逻辑用例 `增删清都发布通知且订阅者读到新键集`（多订阅者通知语义）+ SqlLogManager dispose 注销路径代码审阅替代，未做真机双窗口验证。
5. **dedupeKey 在闸门处的可用性**（读 `LogSqlModels.kt:150` lazy 实现 + `SqlLogEntryBuffer.isDuplicate`）：闸门处 `entry.dedupeKey in suppressKeys` 与既有去重路径同键同函数（lazy 只算一次）；热路径成本分析——**空集短路放在 lazy 访问之前**，无规则常态（绝大多数用户）零新增成本；有规则时每条入队多一次 Set 查找（O(1)，指纹串已在 lazy 缓存），无分配放大。回调用专用出口 `onSuppressedHit`，与既有回调零耦合。

### 2. 定向测试读数（§五 验收 7 口径，只跑定向）

**gradle 命令**：`./gradlew testFast --tests <FQCN>`（纯逻辑三件）+ `./gradlew test --tests "cn.song.mybatisplus.visualizer.log.SqlLogPanelFilterTest"`（平台件）。

| 轮次 | 范围 | 读数 | 出处 |
| --- | --- | --- | --- |
| 绿 | SqlLogModelsTest | tests=10 failures=0 errors=0 | build/test-results/testFast/TEST-*.xml（tf_run6） |
| 绿 | SqlLogEntryBufferTest | tests=52 failures=0 errors=0 | 同上 |
| 绿 | SqlSuppressRuleManagerTest | tests=13 failures=0 errors=0 | 同上 |
| 绿 | SqlLogPanelFilterTest（含新增 4 例） | tests=24 failures=0 errors=0 | build/test-results/test/TEST-*.xml（test_panel） |

（补记：SqlLogPanelFilterTest 定向轮 `./gradlew test --tests ...SqlLogPanelFilterTest`，tests=24 failures=0 errors=0，BUILD SUCCESSFUL in 32s，出处 build/test_panel.log + build/test-results/test/TEST-cn.song.mybatisplus.visualizer.log.SqlLogPanelFilterTest.xml；XML 内确认 4 个新增用例均在执行清单。）

**反向验证（§五 验收 5）**——探针锚点即 `SqlLogEntryBuffer.addEntryInternal` 的屏蔽判定 if 块：

- **红**：注释掉判定块 → `SqlLogEntryBufferTest tests="52" failures="5" errors="0"`（2026-09-23T16:08:38Z，build/tf_red.log + TEST-XML）。失败恰为新增 5 例：屏蔽规则命中_入队前丢弃 / 审计入队同样被屏蔽闸门拦截 / 被拦条目不占捕获序号不产生去重计数 / 移除规则后恢复入队 / 屏蔽命中走专用计数出口_不触内容与入队回调。
- **绿**：还原判定块 → `SqlLogEntryBufferTest tests="52" failures="0" errors="0"`（2026-09-23T16:10:10Z，build/tf_green.log，BUILD SUCCESSFUL）。
- 探针已还原（`git diff` 干净，除临时 sandbox 行）。

### 3. 干扰项与实施偏差

- **平台 API 两处实证修正（本机 243 distro javap）**：① `Editor` 只有 `isViewer()` 读、`setViewer(boolean)` 写口在 `EditorEx`——Kotlin `created.isViewer = true` 对 Editor 静态类型编译不过（compileKotlin 报 val 不可赋值），改 `(editor as? EditorEx)?.setViewer(...)`；② `com.intellij.ui.Splitter` 不在本仓 243 编译 classpath（Unresolved reference），改 `javax.swing.JSplitPane`（DataSourceSelector 既有先例）。两者均已按「方法名以本地 jar 反编译为唯一权威」用 `ideaIU-2024.3.7.1-win/lib/app-client.jar` javap 落实。
- **构建环境干扰**：首轮 gradle 起 daemon 撞 Metaspace 耗尽（机器并行会话内存压力，非代码问题），daemon 自动重启后恢复；后台命令用 `tail`/管道吞过退出码（管道末命令退出码陷阱），后续一律 `> log 2>&1; echo EXIT=$?` 显式检查。
- **测试自纠两处**（提交前 compileKotlin/编译期拦下，未进历史）：manager 通知计数用例的监听器 lambda 首版 `() -> Boolean`（mutableList.add 返回 Boolean）不符 `() -> Unit`；State 构造参数非空不可传 null，防御分支改用空列表验证。
- **sampleSql 语义敲定**：存原始还原 SQL（仅去首尾空白，内部空白原样保留）——与 pattern（规范化文本）分工「展示用户原始形态 vs 匹配规范化形态」；首版测试断言把它也当规范化文本是测试错非实现错，已改测试。
- **D5 通知组**：见 §1.3 结论——独立组，未复用。
- i18n 追加采用 heredoc 落盘（Bash 写文件陷阱知情操作，跨调用 grep 验证双文件各 21 键齐全后才继续）。

### 4. 未做项与受限项

- **真机双窗口验证（前提 4）**：headless 无法验证，见 §1.4——代码结构保证 + 单窗口链路测试替代。
- **对话框/工具栏/右键/balloon 的真机走查**（F4/F5/F6 的视觉与交互：列表渲染格式、软换行外观、balloon 直达、Memory leak detected 红线）：headless 不可视验证，未做；释放路径按 ParamImportDialog 成熟定例 + 测试访问器（ruleCountForTest/editorTextForTest 等）留了后续 headless 钩子。
- 全量 testShards/test、构建校验（verifyGate/buildPlugin/verifySmoke）：按「测试三档分层」纪律**明令不做**（本期不动 build.gradle.kts 生产段 / EP / since-build，非大改动）。
- bundle 完整性守卫测试（`BundlePlaceholderArityTest`）只扫 RollbackWarning 站点，与本组键无关，未纳入定向集（验收 6 的「若仓内有…则一并跑」条件不成立——键齐全性已人工 grep 双文件比对证实）。
- 工作树 `build.gradle.kts` 的 `sandboxContainer = MPSV-SqlSuppress` 临时行：任务收口前还原（见最终提交/工作树状态），不随提交入库。

## 实施记录·走查返修补记（20260924，主控收口）

### 一段：UI 对齐（`5f821c4`）
用户走查截图退回「弹框中间有一个白边」→ 根因 = 实施时误 import `com.intellij.ui.Splitter` 编译失败，误判「不在 classpath」退回原生 JSplitPane（分隔条新 UI 深色主题呈白竖条）。换仓内既有 `com.intellij.openapi.ui.Splitter`（LogToSqlPanel/SqlPreviewPanel/SqlToJavaPanel 同款）根除；规则行渲染器对齐 `LogEntryListCellRenderer` 两行形态（SQL 主体行 + 灰字元信息行 + matte 底分隔线）；去掉列表 preferredSize hack。教训已记 [[feedback-ui-match-existing]]：照抄既有实现的前置动作是先 grep 仓内同类构件。

### 二段：尾分号剥离（`c7a9e07`）
用户实证手工新增规则「已拦截 0 次」→ 根因 = 规则粘贴态带收尾 `;` 而日志还原链不带（多行/单行空白差异本就折叠命中，分号才是杀手）。新增 `suppressFingerprint`（去重口径 + trimEnd(';') 收尾剥离；字面量内分号触不到；大小写仍敏感 D2），规则侧键集与条目侧闸门/面板过滤三处消费方同源切换；`LogSqlEntry.suppressKey` lazy 体属性。反向验证摘剥离恰 2 用例红。

### 三段：结构匹配（手动开启，用户定案 opt-in）
用户确认「结构匹配需要手动开启，弹框支持全部开启或按规则开启」→ `SqlSuppressRule.structureMatch`（缺省 false，存量 XML 不受影响）；开启后按 `structureFingerprint`（屏蔽口径 + `parameterizeLiterals`：'…' 字符串含 '' 转义与裸数字折叠 `?`；双引号/反引号标识符与标识符内数字保留；IN 列表个数=形状为已知边界；畸形未闭合字面量防御性收 `?`）匹配，参数值不同的同形状 SQL 一并屏蔽。Manager 增 `setStructureMatch(ruleId, enabled)`/`setStructureMatchAll(enabled)`（未知 id no-op、状态无变化不发布）；闸门/面板过滤改双键判定（suppressKey ∨ structureKey，命中键回传 recordHit 归账）。UI：按钮行增「开启/关闭结构匹配」（随选中态动态标签）+「全部按结构匹配」勾选框（派生态：全开才勾选），行内「结构匹配」徽标仅开启时显示，切换提示走状态行；i18n 双 bundle 6 键。读数：Manager 16/0 + Buffer 54/0 + Models 11/0 + PanelFilter 绿；反向验证摘 parameterizeLiterals 恰 4 结构用例红（Models 1/Buffer 1/Manager 2）其余不牵连。
