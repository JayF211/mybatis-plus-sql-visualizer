# T-HistoryTabAlways：SQL 历史 tab 常驻开发计划

> 编号占用：O-123 / R69 / D104~D107 / V128~V131（20261008 grep 实测全仓未占用）
> 来源：用户指令「之前 SQL 历史 tab 受 .idea 中的文件限制——没有开启自动保存 SQL 时，只有存在历史 SQL 文件才会有这个 tab，现在希望这个 SQL 历史 tab 一直都存在」+「拍板的四点按照推荐，无关的既有缺陷也修，实施」
> 前序：F-44（历史并入主侧边栏）、T-Perf-17（历史 tab 的 Disposer 双重注册守卫）、T-UxOnboard（历史 tab 空态引导文案）
> 销账：本任务兑现第六轮全景审计的 **R19「历史 tab 显性化」**（该轮已判定「新用户不知道历史功能存在」架空 R15/R16，见 `docs/调研报告/20260926-0645-...第六轮调研报告.md:69` 与 `docs/开发计划/20260926-0645-...:47`）及其 **V17** 前提（「改前先盘点 F-44 显隐既有测试覆盖」——盘点结果＝既有断言 `tabCount >= 3` 无判别力，见 §四.2）
> 状态：**已实施**（2026-10-08，四点按推荐 + 追加修自定义目录读写不同源）

## 一、需求与现状

需求一句话：SQL 历史 tab 的可见性不再与「自动保存开启」和「.idea 中存在历史文件」绑定，恒常驻。

现状是一条双门禁，两处判据同源（读 `.idea/sql-visualizer/sql-history/` 下有无 `.sql` 文件）：

| # | 位置 | 行为 |
|---|---|---|
| 门禁一（构造期） | `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt:697-700` | `if (PluginSettings.getInstance().state.logAutoSaveEnabled \|\| hasHistoryFiles()) addHistoryTab()` |
| 门禁二（运行期） | `SqlLogPanel.kt:1110-1137` `updateHistoryTabVisibility()` | 订阅 `SETTINGS_CHANGE_TOPIC` 后重算 `enabled \|\| hasHistoryFiles()`；两者皆假时**把已挂载的 tab 移除**（先切 `selectedIndex = 0`，再 `removeTabAt`，最后 `Disposer.dispose(historyPanel)` 摘注册） |
| 判据本体 | `SqlLogPanel.kt:1139-1144` `hasHistoryFiles()` | `<basePath>/.idea/sql-visualizer/sql-history` 存在且含 `.sql` 文件 |
| 触发点 | `SqlLogPanel.kt:714-735` | 设置页任何变更 → `invokeLater { updateHistoryTabVisibility(); ... }` |

配套事实（决定改造代价）：

- tab 不是 ToolWindow content，而是 `SqlLogPanel` 内部 `JBTabbedPane` 的第 4 个（`SqlLogPanel.kt:381`、`:693-701`），`plugin.xml:340-342` 只注册一个 content（`SqlLogPanel`）。
- 数据目录常量单点：`SqlLogAutoSaver.DATA_DIR_NAME = "sql-visualizer"`（`log/SqlLogAutoSaver.kt:468`）、`DEFAULT_DIR = "sql-visualizer/sql-history"`（`:470`）；读取端 `HistorySqlLogPanel.getHistoryDir()`（`log/HistorySqlLogPanel.kt:558-561`）是同一路径的第二份拼装（与 `hasHistoryFiles()` 逐字同义）。
- **空态文案现成**：`log.history.files.empty`（`messages/MpSqlVisualizerBundle_zh.properties:543`）＝「暂无历史 SQL 文件\n在设置页开启「自动保存历史 SQL」后，被淘汰的 SQL 会自动存档到此处」。常驻后的空态无需新 UI，且该文案正是为「无数据」写的指路。
- 开关字段 `PluginSettings.kt:287` `logAutoSaveEnabled = false`（默认关）；设置页文案 `settings.log.autoSave.tip`（zh properties:720）只描述「被淘汰的 SQL 落盘」，**没有**「开启才显示 tab」的承诺——拆除门禁不与现有文案冲突。
- `historyPanel` 为 `by lazy`（`SqlLogPanel.kt:387`），Disposer 注册在 `addHistoryTab()` 内带 `historyPanelRegistered` 防重入（`SqlLogPanel.kt:1094-1108`，T-Perf-17 为「构造期 + 设置页循环开关都调用」而设）。

结论：改造面收敛在 `SqlLogPanel` 一个文件的三段代码 + 一个测试用例，「常驻」所需的一切（面板、空态文案、刷新入口、Disposer 释放）都已在位。

## 二、方案

### S1（D104）拆除双门禁，改构造期无条件挂载

- `SqlLogPanel.kt:697-700` → 直接 `addHistoryTab()`，删条件。
- 删 `updateHistoryTabVisibility()`（`:1110-1137`）与 `hasHistoryFiles()`（`:1139-1144`）。
- `SqlLogPanel.kt:714-735` 的设置监听里删掉 `updateHistoryTabVisibility()` 这一行调用；**其余回调必须原样保留**（`applyListCellHeight()` / `list.updateUI()` / `refreshList()` / `historyPanel.repaintSqlList()`——T-LogListFreeze-04 与 T-CrudColor 的生效链）。
- `historyTabIndex`（`:389`）保留：仍被 tab 选中刷新（`:707-710`）使用，常驻后恒为 3。

备选（不推荐）＝保留 `updateHistoryTabVisibility()` 但让它恒真（只在「tab 意外丢失」时补挂）。理由：该分支已无可达触发场景，留着是空转代码，违背本仓「枚举死常量守卫」「投机占位值直接删」的既定口径。

### S2（D105）空态沿用既有文案，零 UI 变更

`log.history.files.empty` 已含「在设置页开启自动保存」指路，常驻后它从「极少见的态」升级为「新用户默认看到的态」，语义仍然正确，不改。

可选项（默认不做，等用户点名）：在空态旁加「打开设置 → 自动保存」按钮。它需要往 `HistorySqlLogPanel` 引设置跳转入口并新增 bundle 键，属新增 UI 形态，按「新增 UI 交互须照抄既有同类实现」的口径应先找到同型载体再定——登记 **R69** 备选，不现做。

### S3（D106）`basePath == null` 时的行为

`hasHistoryFiles()` 与 `getHistoryDir()` 在 `project.basePath == null`（Default Project 等非文件型项目）时分别返回 `false` / `""`，而 `SqlLogAutoSaver.resolveDir()` 此时回落 `user.home/.idea/...`（`SqlLogAutoSaver.kt:237-258`）——即写端与读端在无 basePath 时本来就不同盘。

建议：常驻语义优先，tab 仍存在、扫空目录 → 显示空态；不引入「basePath 为空则隐藏」这一第二套隐藏规则（那等于把门禁换个理由留下）。此差异作为已知边界写入验收文档，不在本任务改动。

### S4（D107）`historyPanelRegistered` / `addHistoryTab()` 守卫的去留

拆除运行期移除后，`addHistoryTab()` 只剩构造期一个调用点，防重入守卫失去实际保护对象；但 `DialogWrapperInitOrderGuardTest.kt:286-288` 把它当作「初值=JVM 默认值」豁免路径的**唯一具名样本**（断言 `"historyPanelRegistered" in result.exemptByDefaultValue`，并注释「两条豁免路径必须真实生效过，否则零违规可能只是扫描面没看到那两处形态」）。

- 推荐 A：保留字段与守卫，`addHistoryTab()` 保留为唯一构造期接线，KDoc 改写为「构造期单次挂载；守卫保留以备后续重复挂载路径」。零风险，且该守卫测试的豁免样本继续真实存在。
- 备选 B：删字段 + 同步改写 `DialogWrapperInitOrderGuardTest` 的豁免样本。**前提是仓内另有同形态站点可替换**——若找不到，删除样本会让该守卫退化成「空绿」（自证断言不再有真样本），故不做。

### S5 测试改造与反向验证

现有唯一驱动该门禁的用例是 `SqlLogPanelLayoutTest.kt:355-380`：先往 `.idea/sql-visualizer/sql-history/` 写一个文件、再重建面板，最后断言 `tabCount >= 3`。该断言**对门禁没有判别力**（关态恰好 3 个 tab、开态 4 个，`>= 3` 两态都过），门禁改完也不会有任何用例变红——必须重写。

新判据（判别点）：

| 用例 | 断言 |
|---|---|
| 无自动保存 + 无历史文件 → 构造面板 | `indexOfTab("SQL 历史") >= 0` 且 `tabCount == 4`（不接受 `>=`，`>=` 是对照不出差异的形态） |
| 设置变更（关闭自动保存）后 | tab 仍在（此前会被移除）——直接调用设置 topic 广播或摘除旧实现后的等价触发路径 |
| 有历史文件时 | 二级导航、预览、复制、跳源码零变化（既有 `HistorySqlLogPanel*Test` 四类继续覆盖） |

反向验证（本仓硬性口径）：把 `addHistoryTab()` 换回 `if (logAutoSaveEnabled || hasHistoryFiles())`，上述第一条必红，贴两次读数。再顺手核一次「摘掉 `Disposer.register(this, historyPanel)` → 面板释放用例/泄漏告警可见性」是否需要补断言（见 §四.4）。

定向套件：`SqlLogPanelLayoutTest`、`SqlLogPanelFilterTest`（`:200` 断言自动保存默认关，不受影响但要复跑）、`HistorySqlLogPanelNavigationTest`、`HistorySqlLogPanelPreviewTest`、`HistorySqlLogPanelJumpToSourceTest`、`HistoryCellRendererTest`、`DialogWrapperInitOrderGuardTest`（仅动 S4 时）；收口跑 `testFast --rerun` 冒烟，全量门禁按三档分层统一执行。

## 三、改动清单

| 文件 | 位置 | 改动 |
|---|---|---|
| `log/SqlLogPanel.kt` | 386-389 | KDoc 更正：「仅 logAutoSaveEnabled 时创建」→「常驻；数据来自 .idea 自动保存文件」 |
| `log/SqlLogPanel.kt` | 697-700 | 删条件，直接 `addHistoryTab()` |
| `log/SqlLogPanel.kt` | 714-735 | 删 `updateHistoryTabVisibility()` 调用，其余回调保持逐字不动 |
| `log/SqlLogPanel.kt` | 1094-1108 | 按 D107 定去留（推荐保留 + 注释改写） |
| `log/SqlLogPanel.kt` | 1110-1144 | 删 `updateHistoryTabVisibility()` + `hasHistoryFiles()` |
| `log/SqlLogPanelLayoutTest.kt` | 355-380 | 重写：不写历史文件，断言恰 4 tab 且历史 tab 在场 |
| 测试（新增用例） | 上述文件或同包新类 | 「关闭自动保存后 tab 不消失」 |

不改动：`HistorySqlLogPanel.kt`（面板、空态、两级导航、刷新链全部复用）、`SqlLogFileReader` / `SqlLogAutoSaver`（读写端零改动）、`PluginSettings` / 设置页（开关语义不变，只影响「写不写文件」，不再影响 tab 存亡）、bundle 文案、`plugin.xml`。

## 四、待验证前提

1. **面板恒实例化的成本**（已核销）：常驻后 `historyPanel`（`by lazy`）在所有项目上必被构造，其 `init` 会调用 `refresh()`（`HistorySqlLogPanel.kt:215-218`）→ 起 `Task.Backgroundable` 扫目录（T-Perf-05 早已把 IO 移出 EDT，目录不存在即空扫描）。实测口径＝`HistorySqlLogPanelNavigationTest` 15 例（多数用例「写历史文件 + 构造面板 + 两级导航」）整类耗时 2.696s、`HistorySqlLogPanelPreviewTest` 3 例 0.479s——**未出现构造期阻塞**；「面板构造到首屏」的独立计时在 headless 下无首屏可测（沙盒真机走查项，见 §五.5）。
2. **现有测试的判别力缺口已实证**：`SqlLogPanelLayoutTest:355-380` 的 `tabCount >= 3` 两态皆过（关态 3：logs / 日志转 SQL / SQL→Java；开态 4）。改造必须把判据提到等值断言，否则本次行为变更无任何用例锁住。
3. **守卫测试样本依赖已实证**：`DialogWrapperInitOrderGuardTest:286-288` 断言豁免集合含 `historyPanelRegistered`，并有「样本必须真实生效过」的自证断言 → 删字段会让该测试红（属真红，不是 flake）。D107 两条路都须先跑该测试确认。
4. **Disposer 注册必须保留**：`Disposer.register(this, historyPanel)` 是 `SqlLogPanel` dispose 时释放历史面板的唯一挂点；删掉移除分支时**不能连注册一起删**，否则工具窗关闭后历史面板与其中两套 renderer / 防抖 Timer 不被释放（IDE 退出会报内存泄漏）。
5. **重复挂载路径**：`SqlLogPanel` 由 `SqlLogToolWindowFactory.createToolWindowContent` 每次 tool window content 创建时 new 一次（content 已 `setDisposer(panel)`），故「重开项目 / 重启 IDE / 隐藏再显示工具窗」预期每次都是新面板单次挂载；实施时以 runIde 真机确认 tab 不重复出现（若出现，说明另有复用路径，需回到 S4-A 的守卫语义）。
6. **`.idea` 目录的另两种边界**：①项目已打开但 `.idea` 尚未落盘（首次索引期）——目录不存在即空态，符合预期；②自定义保存目录（`settings.log.autoSave.path`）非空时读端只认默认目录——**该项经用户拍板并入本任务一并修**（见 §八）。

## 五、验收标准

1. 关闭自动保存、`.idea` 无任何历史文件的干净项目：SQL 日志侧边栏第 4 个 tab「SQL 历史」存在，点开显示既有空态文案。
2. 设置页把「自动保存历史 SQL」开→关→开：tab 全程存在，不再出现「关闭后 tab 消失」。
3. 有历史文件时全部既有行为零变化：一级文件列表、二级条目（#N 行号 / 类型色条 / 元信息行）、单击预览浮窗、双击完整预览、Ctrl+C 复制、右键复制与跳源码、搜索过滤、返回。
4. 自动保存新写入文件后，在历史 tab 点「刷新」（或切走再切回）能看到新文件与新条目。
5. 关项目 / 重启 IDE：无 tab 重复、无内存泄漏告警。
6. 定向用例 + 反向验证 + `testFast` 读数在册；全量门禁按统一收口执行。

## 六、文档同步（实施后）

1. 本计划文档 §七实施记录回填。
2. `docs/变更记录.md` 顶部追加 2026-10-08 行（含占号 O-123/R69/D104~D107/V128~V131）。
3. `docs/MEMORY.md`「需求文档与开发计划」节（时间倒序表，约 88-97 行区）加一行索引。
4. `docs/架构设计/02-核心流程详解.md:416` 复用图注只描述历史 tab 的两级导航、无门禁描述——**核对后无需改**（此条登记为已核对项，避免下次复查重复排查）。
5. 设置页文案与 `log.history.files.empty` 均不改（§二 S2 已说明无矛盾）。
6. `docs/架构设计/01-项目架构总览.md:33/70` 的「工具窗口 4 tab」措辞**现在才名副其实**——第六轮审计曾判该口径不实（实际 3 常驻 + 1 条件），本任务落地后无需改文档，实际形态与既有描述对齐。
7. 发行说明并入下次发行（本任务不改 `docs/发行说明/`）。

## 七、实施记录

**改动落点**（4 个生产文件改动 3 个，测试 3 个）：

| 文件 | 改动 |
|---|---|
| `log/SqlLogPanel.kt` | 构造期无条件 `addHistoryTab()`（门禁一删）；删 `updateHistoryTabVisibility()` + `hasHistoryFiles()`；设置监听去掉显隐调用（其余回调逐字未动）；`historyPanel`/`historyTabIndex` KDoc 更正；新增测试访问器 `tabsForTest` |
| `log/SqlLogAutoSaver.kt` | 目录解析上收为 companion `resolveHistoryDir(customPath, projectBasePath)`（internal，写端 `configure` 改调它，逻辑逐字等价）；`DATA_DIR_NAME` KDoc 里失效的方法名引用更新 |
| `log/HistorySqlLogPanel.kt` | `getHistoryDir()` 改用同源解析（修复自定义目录不可见）；新增 `onSettingsChanged()` / `historyDirChangedSinceLastScan()`（目录变更即重扫）；`refresh()` / `refreshForTest()` 记录 `lastScannedDir`；类 KDoc 补目录同源说明 |
| `log/SqlLogPanelLayoutTest.kt` | 重写 `test切换历史Tab_历史面板正常装配`（判据从 `tabCount >= 3` 提为「历史 tab 在场 + 选中组件是历史面板」）＋新增 `test历史Tab常驻_未开自动保存且无历史文件也存在`、`test设置变更_关闭自动保存后历史Tab不消失` |
| `log/SqlLogAutoSaverTest.kt` | +3：`目录解析_自定义目录优先并去空白` / `目录解析_默认落项目根idea子目录` / `目录解析_无项目根回落user_home` |
| `log/HistorySqlLogPanelNavigationTest.kt` | +2：`test自定义保存目录_历史页读取该目录` / `test保存目录变更判据_改过才重扫` |

**读数**：

| 轮次 | 命令 | 读数 |
|---|---|---|
| 定向（首批 8 类） | `test --tests …` | **104/0/0/0**（LayoutTest 12 含 3 新例、AutoSaver 28 含 3 新例、Navigation 15 含 2 新例） |
| **反向验证（探针：还原双门禁）** | 同上单跑 LayoutTest | **12 例恰 3 红，逐条对应分支**：`test历史Tab常驻…`＝「expected:\<4\> but was:\<3\>」（构造期门禁）、`test切换历史Tab…`＝「历史 tab 应在场」（构造期门禁）、`test设置变更…`＝「expected:\<3\> but was:\<-1\>」（**运行期摘除分支**，tab 被摘掉）；其余 9 例保绿 |
| 回滚探针后 | 同上单跑 LayoutTest | **12/0/0/0** |
| 定向（最终 10 类） | `test --tests …` | **134/0/0/0** |
| 冒烟 | `testFast --rerun` | **3409/0/0/6**（266 套件，报告时间戳 13:17:06 实证真跑；`--rerun` 强制真跑防 UP-TO-DATE 假绿。较上轮基线 3406 净增 3＝新增的三个纯 JUnit 目录解析用例，平台型新例不在 testFast 取样面） |
| **全量分片门禁** | `testShards` | **SHARD TOTAL 6250/0/0/8**（5 片逐片 1340/1134/1286/1329/1161 全零失败；546 套件产物全部新鲜、陈旧混入 0——按本仓定例核过新鲜度）。8 例 skip 均属既有环境受限口径：`DialectDockerVerificationTest` 6 例＝Docker 测试未启用（`docker info` 实测 daemon 不活，server 段连不上 npipe），`ExecWholeScriptOptionTest` 2 例＝Database 插件不在测试 classpath |
| agent 子项目（**本轮唯一红灯，判为在册 flake**） | `:sql-visualizer-agent:test` | 首跑 **106/2/0/0**：`HeartbeatRhythmTest.心跳节奏_首条即时且间隔不超设定值`＝「相邻心跳最大间隔应 ≤ 600ms，实测 752ms」、`SenderLoopBackoffTest.连上即被关_退避增长且达上限后放弃该端口`＝「第 1 段 418ms，第 2 段 323ms」。**隔离复跑（`--rerun`，全机无其他构建）106/0/0/0 全绿**。判定依据三条：①本次改动零触及 agent（`sub-projects/` 下改动文件 0、agent 源码对 `resolveHistoryDir`/`hasHistoryTab` 等改动符号零引用）；②两条均为**在册 flake 家族**（`SenderLoopBackoffTest` 退避时序在 T-TestPerf10/T-JpaDeleteWhereGuard 等多轮门禁有先例且判为「并行负载下的时序抖动，串行复跑即绿」；`HeartbeatRhythmTest` 计时容差在 T-PerfSweep6 发现 14 与本仓调研报告第 14 项已登记「同代码复跑 82 例全绿」）；③本仓红线**禁以放宽容差处置 flake**，故只登记不修。门禁实质结论＝零失败（分片 6250/0/0 + agent 隔离 106/0/0） |
| 构建校验 | 未跑 | 判据：未动 `build.gradle.kts`/依赖/`plugin.xml` EP/since-build/新平台 API 面/MCP 工具声明（按 AGENTS 三档分层，构建校验只在大改动或发版前跑） |

**实施偏差与如实登记**：

1. **反向验证的第一次探针判别力不足（已修）**：首次探针只还原构造期门禁时，「设置变更后不摘 tab」那条用例是在**构造期**就被拦下而红的（红在「前提：tab 应在场」），并没有打到被删的运行期分支——等于该分支无判别证据。改法＝用例改成「构造期先开着自动保存（旧实现此态也会挂 tab）→ 再关掉并广播」，探针也补回运行期摘除分支；重测后该用例红在「expected:\<3\> but was:\<-1\>」，两条门禁各有独立判别点（探针两次读数都在册）。
2. **自定义目录缺陷的修复边界**：读端改同源后适用范围＝历史页（列表/刷新/二级）；`settings.log.autoSave.path` 与「项目根 `.idea` 路径」的切换已由 `onSettingsChanged()` 即时重扫。至于「无项目根时读写都回落 `user.home`」——写端本来就如此，读端现在跟随（此前读端在无 basePath 时直接返回 `""` 空扫描，属半失效态）。
3. **`historyPanelRegistered` 保留**（D107-A），因此 `DialogWrapperInitOrderGuardTest` 的豁免样本仍真实存在，4 例全绿。
4. **未做（三项真机项，留用户走查）**：①tab 常驻观感与空态文案；②自定义目录端到端（设置页改「保存目录」→ 历史页看到新目录的文件）；③**§四.5 的重复挂载路径**（重开项目 / 重启 IDE / 隐藏再显示工具窗后 tab 不重复、无内存泄漏告警）——headless 起不出真工具窗，本机沙盒 GUI 自动化又不可用（CUA 对 IDE 窗口不落地），故这条只能真机判。代码侧依据：`SqlLogToolWindowFactory.createToolWindowContent` 每次 new 一个 `SqlLogPanel` 且 content 已 `setDisposer(panel)`，单次挂载 + 单次 Disposer 注册由守卫保证。
5. **k2j 移植债**：`feature/kotlin-to-java` 轨三文件均有 Java 对应件（`SqlLogPanel.java`/`SqlLogAutoSaver.java`/`HistorySqlLogPanel.java`），本次 Kotlin 侧改动计入移植债，随下次 K2J 同步批清偿。

## 八、追加需求（用户拍板：「无关的既有缺陷也修」）

原先登记的边界缺陷——读端 `HistorySqlLogPanel.getHistoryDir()` 自己拼一份同形路径且只认默认 `.idea` 目录，而写端 `configure` 认自定义目录——改为读写两端同源：

| # | 举措 |
|---|---|
| 单点化 | 目录解析上收到 `SqlLogAutoSaver.resolveHistoryDir(customPath, projectBasePath)`（companion，internal）：自定义目录优先（去首尾空白）→ 项目根 `.idea/sql-visualizer/sql-history` → 无项目根回落 `user.home` 同一相对路径；写端 `configure` 改调它（行为逐字不变），读端改用同一函数 |
| 目录变更即时生效 | `HistorySqlLogPanel` 新增 `onSettingsChanged()`（设置 topic 入口）：解析目录与上次扫描不同则重扫（用户改完「保存目录」不必手动点刷新），未变则维持原 `onDisplayModeChanged()` 重绘；判据拆成 `historyDirChangedSinceLastScan()` 供测试直接断言（`refresh()` 是后台任务，headless 到点不可控） |
| 测试 | `SqlLogAutoSaverTest` +3（自定义目录优先并去空白／默认落项目根 `.idea` 子目录／无项目根回落 `user.home`）；`HistorySqlLogPanelNavigationTest` +2（自定义目录下写的文件在历史页可见／目录变更判据在重扫后复位） |
