# T-MergeDevCrudcolorIntoMaster：dev 与 t-crudcolor 分支合并 master 开发计划

- 立项日期：2026-09-13
- 任务性质：分支合并 + 冲突语义融合 + 全量回归 + 构建校验
- 目标：master 吸收 dev（119 提交）与 t-crudcolor（7 提交）全部增量，全量测试 0 失败，verifyPlugin 全目标 Compatible，buildPlugin 产出正常
- 用户原始需求：主分支合并 dev 分支和「SQL 增删改查自定义背景色」分支；dev 近期功能更新多可能有冲突；合并解决冲突、全量测试并修复问题、确保插件构建校验通过

## 一、分支事实（git 实证，2026-09-13）

| 事实 | 证据 |
|---|---|
| merge-base(master, dev) = 81ecf14 | `git merge-base master dev` |
| dev 领先 master 119 提交 | M37 竞品融合 28 提交（84eb34f）、T-ErrReport、T-ListMode 三显示模式、T-TwoLine 元信息换行、T-Perf-05/12/13/14、T-PerfSweep、gif 录制套件、T-MergeMasterIntoDev2 等 |
| master 领先 dev 30 提交 | T-CrudSql 全系（实体/表右键生成 CRUD SQL + 弹窗原生化三轮 + JBPopup）、T-AutoFold、T-SelToggle、v2026.9.14-beta.1 发行、市场页/README 文档、Gitee 发行工作流文档等 |
| t-crudcolor 领先 master 7 提交 | T-CrudColor 实现（641c815 主体 + dbc45bf 图例 + e57a34e perf + 2 docs + 2 次 merge master），已两度反向合并 master，基点 f09c88c |
| feature/t-crudsql 已完全在 master | `git rev-list --count master..feature/t-crudsql` = 0，无需处理 |
| dev 树无 CrudColor 符号 | `git grep -i crudcolor dev -- src/` 零匹配，无同域重复实现 |

「SQL 增删改查自定义背景色」分支即 t-crudcolor（T-CrudColor：SQL 日志/历史列表 CRUD 类型颜色标记，色条/整行淡背景/关闭三形态 + 四色自定义 + 2px 描边选中态）。

## 二、合并顺序定案

先合小后合大：① `git merge t-crudcolor`（预演仅 3 个文档冲突，代码全自动合并）→ ② `git merge dev`（13 文件冲突，核心是渲染器语义融合）。

理由：t-crudcolor 已对齐 master 近期基线，先合入让第二步冲突一次性暴露在「master(含 CrudColor) × dev」真实组合上；串串联预演已按此顺序执行（merge-tree 两步：master+crudcolor → tree 0115cc3 / commit e65163f，再 ×dev → tree 14b88ae）。

## 三、预演冲突面与逐块定案

### 3.1 第一步：merge t-crudcolor into master（3 文件，全部文档）

CHANGELOG.md、docs/MEMORY.md、docs/变更记录.md——双方追加型冲突，取两侧条目并集，时间序按各文件既有惯例排布。

### 3.2 第二步：merge dev into master（13 文件）

预演冲突块计数：SqlLogPanel.kt 3 块、HistorySqlLogPanel.kt 3 块、其余文件各 1 块。

#### A. 渲染器融合（核心，两文件同构同解法）

背景：dev 侧 T-TwoLine/T-ListMode 把渲染器从「单 sqlLine 组件 + GridLayout 双行面板」重构为「sqlLinePool 组件池 + linesPanel（行数随显示模式伸缩）+ 条目级片段缓存」；T-CrudColor 在旧结构上加装 colorBar（WEST）、normalBorder/selectedBorder 动态描边、useSelectionFill 前景分派。两侧改动都落在渲染器 init 与 getListCellRendererComponent，git 必冲突，需手工语义融合。

定案（SqlLogPanel.kt 与 HistorySqlLogPanel.kt 各 3 块同解）：

1. init 块：保留 dev 组件池骨架（`sqlLinePool[0].isOpaque = false` + `linesPanel.add(...)`），叠加 crudcolor 的 `add(colorBar, BorderLayout.WEST)`；`border = normalBorder` 取代 dev 的静态 compound border。
2. getListCellRendererComponent 中段块：crudcolor 前置段全保留（style/tint/useSelectionFill/applyCrudColorMark/fg 分派，entry 声明已在方法开头不重复）；SQL 行填充取 dev 结构（`val lineFragments = if (entry != null) { ... sqlLineFragmentsFor(entry) }` + `ensureSqlLineCount` + `sqlLinePool.forEachIndexed`）。
3. 方法尾部块：保留 dev 新增的 `ensureSqlLineCount` 与 `sqlLineFragmentsFor` 两个私有函数（crudcolor 侧无对抗内容）。
4. HistorySqlLogPanel 第 1 块（非渲染器）：crudcolor 的 `repaintSqlList()` 新函数与 dev 的 `refresh()` T-Perf-05 KDoc 改写并存，两者都保留。

融合语义风险点（实施时逐项核实）：

- normalBorder 构成是否包含 dev 侧的行底分隔线（`createMatteBorder(0,0,1,0,JBColor.border())`）视觉语义——读合并后 SqlListTypeColor.kt 的 border 构造，若缺则以 compound(matte, crudcolor border) 叠加，保持 dev 列表观感不回退。
- dev 的 `formatDurationFragment(entry.executionTimeMs, useSelectionFill)` 已由自动合并区接上 crudcolor 的 useSelectionFill 变量——语义恰好兼容（淡背景样式下耗时着色不被选中白字抹掉，正是 T-CrudColor 设计意图），编译+用例双验证。
- sqlLine（组件池首位）作为测试缝仍被 crudcolor 侧测试引用（metaTextForTest 等），确认保留。

#### B. ConvertToSqlAction.kt（1 块）

master 侧：EDT 分发 0/1/多语句，多语句时后台 ReadAction 预计算摘要（T-M23.8 慢操作守卫）；调用 `SqlSelectionDialog.Row.of(...)`（T-CrudSql 弹窗原生化后的 Row 模型）。
dev 侧：T-Perf-14——XmlTag 全文件遍历 + 摘要预计算合并进同一后台 ReadAction，EDT 只做预览与弹窗；调用 `SqlSelectionDialog.displayText(...)`（dev 不知道 Row 模型）。

定案：取 dev 的 T-Perf-14 全后台结构（它是同一逻辑的性能超集）；SqlSelectionDialog 调用形态以合并后 SqlSelectionDialog.kt 实际 API 为准——若 master 的 Row 模型已取代 displayText，则 dev 侧调用点同步换 `Row.of(...)`（编译期决断，禁止调用不存在的方法）。

#### C. LogToSqlPanel.kt（1 块）

master 侧多出 T-AutoFold F4-c 的 `SqlAutoFolding.applyTo(resultEditor, out)` 行；dev 侧对该区域周边有 T-HibDualCapture 等改动但无对抗内容。定案：双方并存——保留 master 的 applyTo 行 + dev 的周边逻辑。

#### D. 测试文件（2 个）

- SqlLogPanelRendererUiTest.kt：import 冲突（crudcolor 加 assertNotSame/assertSame，dev 加 assertNull）取并集；测试区块两侧新增用例并存。注意该文件头部 KDoc 已含 T-ListMode 语义描述，确认 crudcolor 的色条/描边用例与 dev 的模式切换单行 setUp（SINGLE_LINE）不互斥——crudcolor 用例若依赖默认形态设置需显式设样式，沿用其原测试自身的设置管理。
- SqlPreviewPanelTest.kt：master 的 T-AutoFold F4-a 测试区块与 dev 侧改动并存，取并集。

#### E. 资源与文档（6 个，全部并集型）

plugin.xml（双方追加 action/EP/依赖注释）、MpSqlVisualizerBundle.properties 与 _zh.properties（双方追加键，键名无碰撞则并集，有碰撞人工对齐文案）、marketplace-custom-page.html、README.md、docs/MEMORY.md、docs/变更记录.md——全部取两侧并集，注意 plugin.xml 的 extensions 顺序与 optional depends 结构不被打乱。

### 3.3 自动合并但需语义核查的高危区

merge-tree 显示 PluginSettings.kt、PluginSettingsPanel.kt、SqlPreviewPanel.kt、build.gradle.kts、gradle.properties 自动合并成功——这些是双方都动过的文件，自动合并不代表语义正确：

- PluginSettings：dev 侧新增字段（显示模式等）与 master 侧 T-CrudColor/T-AutoFold/T-HistParam 字段共存，确认序列化兼容与 isModified/load/apply 无互相覆盖。
- build.gradle.kts：dev 侧 -PgifRecord 开关等与 master 侧发行相关改动共存，确认 verifyPlugin/buildPlugin 任务配置未被冲掉。
- SqlPreviewPanel：dev 侧 T-PreviewTopOverlap 转正（344977c 预览顶部「更多」折叠菜单）与 master 侧 T-AutoFold/T-SelToggle 共存，编译+用例验证。

## 四、实施步骤

1. 备份与登记：`git branch master-backup-pre-dev-merge-20260913`（对齐 dev-backup-pre-master-merge-20260908 惯例）；`python C:/Users/USER/.zcode/cli/parallel_tasks.py check` 无活跃任务后开始（此后每次 gradlew 前复查）。
2. 第一步合并：`git merge t-crudcolor`，解 3 个文档冲突（并集），`./gradlew compileKotlin compileTestKotlin` 过后跑一次全量 `./gradlew clean test`（t-crudcolor 交付基线 3393/0/0/6，此时应仍绿；防 K2J 陈旧类劫持，clean 保证干净输出）。
3. 第二步合并：`git merge dev`，按 §三 定案逐块解 13 文件冲突；先 `compileKotlin compileTestKotlin` 过编译（ConvertToSqlAction 的 Row.of/displayText 决断在此落地），再做渲染器语义核查（§3.2-A 风险点逐项）。
4. 全量测试：`./gradlew clean test`，合并后用例数应 ≥ dev 侧 3788 + CrudColor 增量（约 40+），0 失败、6 跳过（既有口径）。失败用例先证伪再排逻辑（Kotlin 增量陈旧类/clean 首跑崩溃/剪贴板占用 flake 三类假失败优先排除），真缺陷逐个修复并在本计划 §六 登记。
5. 构建校验：`./gradlew verifyPlugin -PverifyIdes=full`（或 verifyFull 封装任务）全目标 Compatible；`./gradlew buildPlugin` 产出 zip 无误。
6. 文档同步：CHANGELOG Unreleased、docs/变更记录.md、docs/MEMORY.md 三件套补合并记录行；本计划 §六 回填实施记录。
7. 提交拆分：两个 merge commit（`merge: t-crudcolor into master`、`merge: dev into master`）+ 一个 docs 同步 commit；不自动发版（v2026.9.14-beta.1 已发行，正式版另立项）。

## 五、验收清单

- [ ] master 包含 dev 全部 119 提交与 t-crudcolor 全部 7 提交（`git rev-list --count master..dev` = 0 且 `master..t-crudcolor` = 0）
- [ ] `./gradlew clean test` 全量 0 失败（跳过数不增）
- [ ] `verifyPlugin` 全目标 Compatible
- [ ] `buildPlugin` 产出 zip
- [ ] 合并后功能抽查符号在位：CrudColor（SqlListTypeColor/applyCrudColorMark）、T-ListMode（sqlLineFragmentsFor/ensureSqlLineCount）、T-TwoLine、T-CrudSql（右键 action）、T-AutoFold（SqlAutoFolding）、T-ErrReport（feedback 包）——特征符号 git grep 终审（对齐丢功能审计教训）
- [ ] 渲染器融合四要素共存：组件池行数伸缩 × WEST 色条 × 动态描边 × 行底分隔线
- [ ] 三件套文档同步完成

## 六、实施记录（2026-09-13 回填）

### 提交与顺序

- 备份锚点 `master-backup-pre-dev-merge-20260913`（指向合并前 master 6f626ab）。
- 第一步 merge t-crudcolor（a5d9307）：与预演一致，仅 CHANGELOG.md / docs/MEMORY.md / docs/变更记录.md 三 docs 冲突，取两侧并集（CHANGELOG 的 T-CrudColor 两条留在 Unreleased——未随 beta.1 发行；MEMORY 计划表按 1612/1118/1003 立项号倒序穿插）。代码全自动合并。
- 第一步全量 clean test：3401 用例 1 失败——**T-SelToggle 弹窗测试与 T-CrudColor 图例的真实交互冲突**（图例把每个复选框包进 FlowLayout 行面板，`content.components.filterIsInstance<JCheckBox>()` 顶层直查取到 0 个）。修复=测试改递归收集（collectCheckBoxes 帮助函数，两处调用点），产品代码不动（7f20336）。t-crudcolor 分支树上没有该测试（分叉早于 T-SelToggle），属跨分支适配欠账。
- 第二步 merge dev（8d2c919）：13 文件冲突与预演完全一致，逐块按 §三 定案解决。

### 冲突解法实录（偏离计划的两处）

- ConvertToSqlAction：合并树 SqlSelectionDialog 已是 master 的 Row 模型且 `displayText` 已删（预判兑现）——dev 侧 T-Perf-14 结构里 `displayText(...)` 换 `Row.of(...)`，`Pair<List<XmlTag>, List<String>>` 与两处 catch 兜底同步 Row 化。
- LogToSqlPanel：HEAD 侧 applyTo 原位置在 convert 函数内，但 dev 已把该函数重构为 `computeConversion`（纯计算、后台线程安全、注释明言不触碰 Swing）+ `applyConversion`（EDT 应用）——直接保留 HEAD 两行构成后台写 document 的 EDT 违规。融合=删原两行，applyTo 移入 applyConversion 的 `setDocumentText` 之后。
- 渲染器融合比计划预想更顺：crudcolor 的 `normalBorder` 构成里**已含** dev 侧 matte 行分隔线（CompoundBorder(matte, EmptyBorder(5,5,5,8))，左 5+色条 3=8 与 dev 的 EmptyBorder(5,8,5,8) 等价），§3.2-A 风险点一不成立；`formatDurationFragment(entry.executionTimeMs, useSelectionFill)` 由自动合并区正确接线（淡背景样式下耗时着色不被选中白字抹掉，正是 T-CrudColor 设计意图）。
- 市场页文案（plugin.xml 双语描述/README/marketplace-hero）取 master 2026-09-12 定稿版；marketplace 功能矩阵区的 dev 侧 M37 行由自动合并并入。

### 全量测试（含一次大规模假失败）

- 第二步合并后首个 clean test：3880 用例 **503 失败**，全部 `NoSuchMethodError`——首例 `ColumnInfo.<init>(8 参 + int mask + DefaultConstructorMarker)`。排查：主类 javap 显示 13 参新形态（dev 侧 M39/T-J14、T-J18 尾部追加带默认值字段，源码注释明确「既有位置构造零影响」，源码自洽），测试字节码仍调 8 参旧形态=测试产物陈旧。`clean` 后 build cache 命中陈旧 compileTestKotlin 产物（本次构建 5 个任务 from cache）——**K2J 陈旧类劫持坑的 build-cache 变体**。处置=删 build 目录 + `./gradlew test --no-build-cache` 重跑。
- 重跑：**3880/0/6 绿**（303 测试类），超 dev 侧基线 3788（含 CrudColor 44 例与合并组合），0 失败、6 跳过（口径与两侧基线一致）。

### 构建校验

- `./gradlew verifyFull buildPlugin --no-build-cache`：verifyPlugin 三目标 **Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.9437.185；warning 级计数 243=3 scheduled-removal + 3 deprecated + 12 experimental、253=3+6+12、262=3+49+12，与两侧既往基线同量级，无 incompatible）；Dynamic Plugin Eligibility 通过。buildPlugin 产出 `build/distributions/mybatis-plus-sql-visualizer-2026.9.14-beta.1.zip`。

### 验收清单勾销

- [x] `master..dev` = 0 且 `master..t-crudcolor` = 0（合并后 dev 落后 master 40 提交，下次同步方向反转）
- [x] 全量 3880/0/6 绿（跳过数 6 持平）
- [x] verifyPlugin 三目标 Compatible
- [x] buildPlugin 产出 zip
- [x] 特征符号 git grep 终审双侧在位（dev：EntrySourceMatcher×3 / renderExplainStatement×3 / feedback.FeedbackAction / SqlListDisplayMode×7 / SqlLogEntryBuffer×9；master：SqlAutoFoldPlanner / GenerateCrudSqlFromEntityAction×4 / CrudSqlGenerator×6 / SqlListTypeColor×8 / SqlListSelectionOutline×4 / syncTypePopupToggleLabel）
- [x] 渲染器四要素共存（组件池伸缩 × WEST 色条 × 动态描边 × matte 行分隔线）
- [x] CHANGELOG（Unreleased 仅剩 CrudColor 两条，dev 功能条目已随 v2026.9.11 等版本块归档，无需补行）/ docs/变更记录.md / docs/MEMORY.md 三件套同步

### 遗留

- runIde 真机走查（渲染器融合后的列表观感：三显示模式 × 色条/淡背景/描边组合）留人工。
- 不自动发版；dev 分支未回推（后续可 fast-forward dev 到 master 或反向合并保持同步）。
- 503 假失败的 build-cache 劫持值得记入回归假失败清单：clean 全量遇成片 NoSuchMethodError 时先 `--no-build-cache` 重跑再排逻辑。
