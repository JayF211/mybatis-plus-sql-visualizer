# T-MergeMasterToDev2——master 并入 dev 冲突解决与全量门禁收口 开发计划

> 立项：20261010-1542，用户令。任务名 t-mergemastertodev2，沙盒 mpsv-mmgd2。
> 方向合法性：master→dev 属常规回流同步；dev→master 才须用户明令（在册纪律），本任务不涉及。

## 一、背景与目标

用户令：master 最近更新了大量功能（91 笔），要求切到 dev、把 master 合并进 dev、解决冲突、全量测试并修复问题、确保插件构建校验（verifyGate）通过。先出本计划再动手。

基线实测（20261010-1540）：

| 项 | 值 |
|---|---|
| merge-base | `cf95acc`（v2026.10.9 发行 tag） |
| dev | `8198908` == origin/dev（worktree `mybatis-plus-sql-visualizer-dev`，工作树干净） |
| master | `d9eb3c0`（ahead 1 未 push） |
| 分叉 | master..dev = 62 笔（dev 独有）；dev..master = 91 笔（master 独有） |
| 冲突预演 | `git merge-tree --write-tree` 实测：10 文件真冲突，约 300 个双侧改动文件可自动合并 |

master 独有 91 笔=v2026.10.9 发行+批9 走查修复（CountSimToggle/ExportSqlFormat/DataSourceBadgeMode/SearchToggleNativeLook 等）+attach 达梦扩面与去重窗修复+PremainReattach+空态链接（T-SettingsFocusFromLink）+单击复制修复等；dev 独有 62 笔=R53 用户暂停、R60 展示/导出链脱敏（DisplayMasker 为 dev 独有新文件）、R61 EXPLAIN 跨次对比、R62 更新可诊断化、R54 历史耗时回填、阶段二批等。

注意事项：master 主树工作区有未提交改动（CHANGELOG + 一份 20261010 发行说明草稿，疑似发行会话在途）。本任务全程只在 dev worktree 操作，合并基线=master 已提交态 `d9eb3c0`，不读不碰 master 工作树的未提交内容。

## 二、冲突清单与逐文件裁决策略

预演树 `172a7b9`，冲突块数实测标注。总原则：零语义丢失，双侧特性共存，不做单边取舍。

| # | 文件 | 块数 | 裁决 |
|---|---|---|---|
| 1 | CHANGELOG.md | 2 | union 双保留，按各自时间序归位 |
| 2 | docs/变更记录.md | 1 | union 双保留，「最新在上」惯例插入 |
| 3 | SqlClickPreview.kt | 1（imports） | 双保留：dev 的 `DisplayMasker`（R60）+ master 的 `EditorCopyShortcuts`（单击复制） |
| 4 | SqlExportService.kt | 2 | `format` 合并为「master 四格式（含 SQL 分支）+ dev `mask` 参数」：`fun format(rows, format, mask: Boolean = false)`，先投影脱敏再四格式分发；`formatPortable` 取 master 的 `SQL -> error(...)` 防御分支 + dev 的 mask 参数 |
| 5 | SqlLogPanel.kt | 2 | 搜索条整取 master（nativeSearch/NativeSearchTextField 全套，弃 dev 的 `buildSearchField`）；保留 dev 的 `buildStatusBar`（R53 状态栏，master 从未有）；`syncDiagnostics` 合成=master 的空态链接路径（`applyEmptyTextWithEnhancedLink`）+ dev 的 `syncCaptureStatusBar()` 首行调用 + 定时器门取 dev 的 `manager.capturing`（master 的 `active` 是其子集，保 R53 状态栏捕捉期常刷） |
| 6 | AgentManager.kt | 2 | 块一：`gateNow = online && captureConfirmedActive() && !userPaused`（dev R53 暂停位并入 master 的 gateNow）+ master 通道纪元重置段原样保留；块二：master 首条事件 INFO 日志 + dev 暂停丢弃计数 early-return，先后共存（先日志后丢弃，首条证据不受暂停影响） |
| 7 | META-INF/plugin.xml | 1 | 整取 master（v2026.10.9 后发行描述重写版，dev 侧条目均系其旧措辞子集）；合并后 grep 校验 dev 无独有 EP 丢失 |
| 8 | PluginSettingsPersistenceTest.kt | 1 | 复选框计数合并态=41 基数+displayMask（dev）+paginationCount+badgeDetail（master）=预期 44，注释逐条列来源；以实跑读数为准修正，不预设 |
| 9 | SqlExportServiceTest.kt | 1 | 双保留：dev 脱敏判别组（mask 两态）+ master SQL 格式组 |
| 10 | SqlLogPanelRendererUiTest.kt | 1 | 双保留：dev 列表脱敏两态组 + master 数据源角标门控组 |

自动合并的 ~300 个文件语义面不预设正确，交门禁兜底（编译门→testShards 单路→agent→verifyGate）。

## 三、待验证前提（实施时先证伪，不当作既成事实）

1. **`entries.size()` 存活性**：dev 的 syncDiagnostics 用 `manager.entries.size() == 0`，master 同函数用 `manager.entries.snapshot().isEmpty()`——合并取 master 写法还是保 dev 写法，以合并树上该 API 是否仍存在为准（编译门验证）。
2. **R53 字段链完整性**：dev 的 `userPaused`/`pausedProvider`/`pausedDropped`/`statusBarPanel` 等字段分布在自动合并区，合并后须 grep 在场 + HelpDialogTest 守卫（工具栏组数）与 StatementWeaveWiringGuardTest 不红。
3. **搜索条无残留旧引用**：dev 侧 `top.add(searchField, BorderLayout.SOUTH)` 等引用区与 master 的 searchBar 宿主结构发生自动合并，若出现 `searchField` 重复声明或类型不符（SearchTextField vs NativeSearchTextField），按 master 结构收编 dev 引用，编译门暴露。
4. **SqlExportFormat 枚举**：合并树须含 SQL 成员（master 侧加）且 dev 侧 `formatPortable(..., mask=)` 调用点签名兼容；when 穷尽性编译验证。
5. **复选框计数 44 是推断**：以 PluginSettingsPersistenceTest 实跑读数为准，若自动合并区还有其他开关增员则按实际值修断言并逐条注释来源。
6. **dev 无新 EP/新反射入口**：dev 独有 62 笔均为既有面板行为扩展，无 plugin.xml EP 变更；由 SettingsTruthTest/PlatformSignatureCrossVersionTest 兜底。

## 四、实施步骤

0. `parallel_tasks.py check`（已做：无活跃任务）→ `register --name t-mergemastertodev2 --sandbox mpsv-mmgd2`。
1. 提交本计划（dev，随批转 CRLF，`-F` 文件提交）。
2. `git merge master`（预期 10 文件冲突）→ 按 §二逐文件裁决 → `grep -rn '<<<<<<<\\|>>>>>>>\\|======='`（限定冲突标记形态）复核零残留。
3. 编译门快跑：`./gradlew compileKotlin compileTestKotlin :sql-visualizer-agent:compileJava`——快速暴露 §三语义缝，不跑测试。
4. 提交合并 commit（消息按上轮 6002808 惯例：冲突裁决逐文件列明，`-F` 文件）。
5. 全量门禁（单路，先 `timeout 10 docker info` 验 daemon 活性）：`./gradlew testShards :sql-visualizer-agent:test` 后台跑，不 sleep 等待；读数目标=testShards N/0/0/9 + agent M/0。
6. 红→定位修复→定向复验；凡修复触及 src/main 或测试源，权威轮 testShards 必复跑后再进 verifyGate。
7. `./gradlew verifyGate` 后台跑（三目标 243/253/262 Compatible）。
8. 收口：变更记录行 + 本计划 §实施记录回填（读数+裁决实录）+ 与 MEMORY/盘点同步评估 + `parallel_tasks.py done --name t-mergemastertodev2`。

## 五、风险与回退

- 资源定例：全量分片只单路收口；R16「空机也可能顶 Commit」处置=清自家 daemon 原样重跑；R17 用户真机在跑时测试被杀=降足迹参数原样重试，禁杀用户进程；R15 两份全量门禁互斥——本任务全程串行单路。
- 合并中途废弃=`git merge --abort`，工作树回到 dev `8198908` 干净态。
- 门禁期间主控不碰 src/main（收口纪律）；修复走独立提交并触发权威轮复跑。
- 不 push：用户未令 push，收口后 dev 领先 origin 若干笔，push 与否待用户令。

## 六、实施记录

（随实施回填：裁决实录/编译门读数/全量读数/verifyGate 读数/修复笔清单）
