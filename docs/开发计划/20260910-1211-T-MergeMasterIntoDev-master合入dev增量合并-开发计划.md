# master 合入 dev 增量合并开发计划

- 日期：2026-09-10 12:11
- 分支方向：`git merge master`（在 dev worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev` 执行，master 合入 dev）
- merge-base：`29fbf556`（Master33，Kotlin→Java 移植 .port-sync 锚点）
- 分叉规模：master 独有 22 提交 / dev 独有 98 提交
- 前置侦察：`git merge-tree --write-tree dev master` 预演完成，10 文件 11 冲突块已逐块人工过目
- 关联：上次同类合并 `docs/开发计划/20260908-2315-master合并到dev-开发计划.md`（a6570ce）

## 一、背景与两侧增量的真实分布

自 29fbf56 起两侧再次分叉，各自走了长线。

**master 独有（22 提交）**：T-VStack 三笔（INSERT 配对竖排 / 扩面覆盖 SELECT·UPDATE·DELETE 横向组 / IN 阈值 <5 同行 ≥5 逐行）、T-LogRowNumber 两笔（会话内捕获序号 + 非主进程启动不清零的 `isModuleBasedLaunch` 门控）、T-RefreshDbSteal（多弹窗僵尸 rehost 监听抢共享控制台）、T-ExecCopyFix（临时文件失败可见化 / WriteIntentReadAction / 缓存存续 / 复制对号闪变）、T-DepApi（三处弃用 API 换现代等价物）、T-PreviewFlash（filter 重放守卫 + 预热的有限等待）、T-AutoShowMaster（自动打开日志窗口假开关复活）、T-AutoShowRecover（启动清抑制 + `logStopOnToolWindowClose` 按基点恢复）、T-PortLossBatch1 A~D 四组（dev 已修语义缺陷批量移植 + 假开关处置）、v2026.9.9 发行。

**dev 独有（98 提交）**：M54 性能红线治理全量 24 任务（T-Perf-01~24）、M39 JPA 查询深化 Wave1~3、M44 其他 ORM 第一批、M37/M38 竞品融合、T-ListMode（三种显示模式）、T-TwoLine、T-ErrReport（错误一键上报需求池）、T-DaoSql（自研 DAO 字符串 SQL 与方法定义锚点）、T-JdbcMultiline、hbm 命名查询链路、自动监听四轮、演示 GIF 录制脚本管线。

两侧在日志包与预览包正面相遇：master 的 T-VStack、T-LogRowNumber、T-PreviewFlash、T-ExecCopyFix 都改在 dev 同时大改的文件上。

目标：合并后**双侧功能全保全**、全量测试 0 失败、verifyPlugin 三目标 Compatible、buildPlugin 出正常产物。

## 二、冲突预判（merge-tree 预演实测）

内容冲突 9 文件 + add/add 1 文件，共 11 冲突块：

| 文件 | 块数 | 性质 |
|---|---|---|
| CHANGELOG.md | 1 | 跨整文件（行尾符差异伪冲突，见第三节） |
| docs/变更记录.md | 1 | 双侧顶插表格行，需按时序并集去重 |
| config/PluginSettings.kt | 1 | dev 独有新增字段（collapsedGroups/reportSubmitter*）vs master 侧空 |
| execute/DataSourceSelector.kt | 1 | master T-ExecCopyFix/T-PreviewFlash 重写 `createTempPsiFile` vs dev T-Perf-13 旧实现 |
| log/SqlLogPanel.kt | 1 | dev 片段管线 `lineFragments` vs master 行号片段插入 |
| psi/jpa/HbmXmlEntityResolver.kt | 1 | dev `findHbmXmlFiles`+多映射解析 vs master T-DepApi 换 API 的旧结构 |
| ui/SqlPreviewPanel.kt | 1 | 双侧各自新增常量（纯并集） |
| ui/gutter/JpaGutterLineMarkerProvider.kt | 2 | master 99 行旧结构 vs dev 165 行三锚点结构 |
| test/.../SqlLogManagerAutoShowTest.kt | 1 | add/add，master 侧新增 2 用例 |
| test/.../SqlLogPanelRendererUiTest.kt | 1 | dev T-ListMode 用例 vs master T-LogRowNumber 用例，两侧断言并集 |

## 三、根因发现：CHANGELOG.md 的行尾符异常（必须先处置）

预演中 CHANGELOG.md 报「整文件 1..1433 一块冲突」，但 `git diff dev:CHANGELOG.md master:CHANGELOG.md` 只有 39 行内容差异——这不是真实内容冲突。

实证（`git cat-file blob` 原始字节统计）：

| 引用 | 行尾 |
|---|---|
| master:CHANGELOG.md | 全 LF（722 行） |
| merge-base 29fbf556:CHANGELOG.md | 全 LF（689 行） |
| dev:CHANGELOG.md | **全 CRLF（707 行）** |

全库扫描结论：除了四分支共有的 `LICENSE`（CRLF，历史遗留无害），**只有 `CHANGELOG.md` 在 dev 与 `feature/r2-competition-response` 上是 CRLF，master 与 `feature/kotlin-to-java` 是 LF**；其余所有文本文件（含 Kotlin 源码、bundle、其他 docs）四分支一致为 LF。仓库无 `.gitattributes`，`core.autocrlf=true`（约定：库内 LF、工作树 CRLF）。

即 dev 的 CHANGELOG.md 某次被以 CRLF 写盘并提交，成为孤立异常。三方合并时 dev 侧相对基点的每一行都「变了」，master 侧也改了文件头，git 只能给出整文件冲突。

**处置**：本次合并把 dev 的 CHANGELOG.md 归一到 LF（与 master、基点、全库其余文件一致），内容按第六节做并集。这样本次冲突从「整文件」退回「Unreleased 段几十行」，并消除此后每一次 master↔dev 合并的同类伪冲突。

**任务二的连带影响**：`feature/r2-competition-response` 的 CHANGELOG.md 与 dev 当前版本是同一个 blob（`27f3640`，CRLF）。任务一归一 dev 后，任务二（dev→r2）仍会遇到同款整文件冲突，处置同法：r2 侧一并归一 LF。

## 四、解决原则

1. **架构基准按「谁更新」判**，不按分支名判。同一文件两侧都改时，取时间上更晚、且已被该分支全量测试锁定的那一版为实现基准，再把另一侧的特征补进去。
2. **dev 的性能红线不可回退**：M54 治理（`synchronized(entries)` 临界区、指纹键去重、增量刷新、行高按模式分流、EDT 慢操作消除）是 dev 的核心资产，master 侧同期修改一律向 dev 结构靠拢，只搬运语义不搬运结构。
3. **master 的修复以 master 版为准**，当 dev 侧是「修复前」形态时（T-ExecCopyFix 明确拆掉了 dev 侧的 `runCatching` 吞异常结构）。
4. **纯新增取并集**，无争议。
5. **注释差异**：同一句话两侧措辞不同时，取信息更全的一侧（通常带任务号与结论）。
6. 疑难块先做**语义实证**再动手（读两侧 helper 实现、`javap`/源码核实），不以「哪边行数多」定胜负。

## 五、逐块解决方案

### 5.1 CHANGELOG.md —— 并集 + 行尾归一到 LF

两侧 [Unreleased] 段各自演化，合并为一段，保持 Keep a Changelog 的 Added/Changed/Removed/Fixed/Test 顺序：

- Added = dev 的 4 条（T-ErrReport、T-ListMode、hbm 命名查询、T-DaoSql）+ master 的 1 条（T-LogRowNumber）。
- Changed = dev 的 2 条（T-TwoLine、EXPLAIN 汉化）。
- Removed = dev 的 1 条（生成测试下线）。
- Fixed = dev 的 1 条（`commonDao.execute` 双图标）+ master 的 8 条（T-PreviewFlash、T-DepApi、T-ExecCopyFix、T-RefreshDbSteal、T-AutoShowMaster、T-PortLossBatch1 A~C / D、T-AutoShowRecover）。
- Test = master 的测试段。
- 版本区：master 的 `[2026.9.9]` 段（master 独有）置于 dev 的 `[2026.9.8]` 段之前；两段 `[2026.9.8]` 内容以 master 发行版为准核对重复。

落盘时统一 LF，不留 CRLF。

### 5.2 docs/变更记录.md —— 表格行时序并集去重

两侧都是往表格顶部插行：master 侧含 2026-09-10（T-VStack·InThreshold、IDEA 性能影响分析复查）、2026-09-09（T-RefreshDbSteal、T-PreviewFlash、T-DepApi、T-ExecCopyFix、T-LogRowNumber 非主进程修复）、2026-09-10 T-LogRowNumber、T-AutoShowRecover、T-PortLossBatch1 D/A~C、T-AutoShowMaster；dev 侧含 2026-09-08 的 T-ErrReport、T-ListMode、T-TwoLine 等。

注意：**2026-09-08 的自动监听第四轮与 RunConfigurationProcessListener 订阅修复两行，两侧都有**（同内容经 cherry-pick 落在两边），并集时去重，不留重复行。最终按日期倒序。

### 5.3 config/PluginSettings.kt —— 取 dev

master 侧该块为空（master 从未有这三行），dev 侧为 `collapsedGroups` / `reportSubmitterName` / `reportSubmitterEmail` 三个字段及其 KDoc。取 dev，无争议。

附注：`logStopOnToolWindowClose` 两侧**都在**（master 第 169 行 / dev 第 169 行），T-AutoShowRecover 的恢复面与 dev 现状一致，不构成缺口；`collapsedGroups` 只在 dev 有，保留（T-PortLossBatch1 D3 在 master 侧的删除是「两侧零读者」判断，与 dev 侧保留其持久化读写不矛盾）。

### 5.4 execute/DataSourceSelector.kt —— 取 master 版 `createTempPsiFile`

这是本次最有技术含量的块。两侧函数体是同一段代码的两代形态：

- dev 侧（旧）：`runCatching { … }.getOrNull()` 整段吞异常 + `SlowOperations.allowSlowOperations(GENERIC)` 包 refresh/findFile，注释记录 T-Perf-13「EDT 同步 refresh 是作者有意识权衡」。
- master 侧（新）：拆掉 `runCatching`，改为 `fail(stage, detail)` 分级记录失败阶段到 `lastTempFileFailure`（T-ExecCopyFix F2a，onError 文案携带）；`projectExecFile` 自接 IOException；**T-PreviewFlash 的 `cachedTempPsiFile` 配对缓存提前返回**；refresh 段换 `WriteIntentReadAction.compute`（T-ExecCopyFix F2b / T-PreviewWiLock 先例）；findFile 保持 `ReadAction.compute`；成功后写缓存。

**定案：取 master 侧全文。** 理由是 dev 侧属于「修复前」形态，而 master 侧是同一问题的正确解法：

- dev 的 `allowSlowOperations(GENERIC)` 逃生舱在 EDT 上是**空壳**——T-PreviewFlash 反编译结论已记录在案（`SlowOperations.startSection` 首行 `if (!EDT.isCurrentThreadEdt()) return EMPTY` 直接返回不压栈），EDT 上压不住慢操作断言；master 换的 `WriteIntentReadAction` 才是 262 平台的正解。dev 侧此处保留的只是「未被证伪前的权衡注释」，其技术前提已被后续实证推翻。
- dev 的 `runCatching` 吞异常正是 T-ExecCopyFix 要根治的「失败零日志无法归因」。
- 缓存提前返回（`cachedTempPsiFile`）把 `WorkspaceFileIndex.ensureIsUpToDate` 断言移出每次预览热路径，是 dev 侧完全没有的净收益；冷路径每会话仅首次执行，T-Perf-13 担心的 EDT 耗时面被缓存与后台预热共同覆盖。

配套成员（`cachedTempPsiFile` 字段、`lastTempFileFailure`、`cachedTempPsiFileForTest()` / `lastTempFileFailureForTest()` 测试缝、`clearPrepared` 的 F2c 不清缓存）在自动合并区已按 master 侧落入，无需手工搬运。调用点 `val temp = createTempPsiFile(...)` 与 `onError?.invoke(...)` 亦为 master 侧自动合并结果。

### 5.5 log/SqlLogPanel.kt —— dev 结构 + master 行号片段

dev 侧把元信息行渲染重构为 `val lineFragments = if (entry != null) { … }`（T-ListMode 片段管线的入口，下方 1557/1561 行依赖它做 `ensureSqlLineCount` 与逐行填充）；master 侧在同一位置插入了行号片段（T-LogRowNumber）：

```kotlin
if (entry.sequenceNumber > 0) {
    metaLine.append("#${entry.sequenceNumber} ", LIST_META_GRAY)
}
```

**定案**：保留 dev 的 `val lineFragments = if (entry != null) {` 结构，把 master 的行号片段插入该分支体首行（行号是最高频引用锚点，须在时间戳等片段之前）。两侧语义正交，纯并集。

### 5.6 psi/jpa/HbmXmlEntityResolver.kt —— dev 结构 + master 的弃用 API 修正

dev 侧已重构为 `for (file in findHbmXmlFiles(project)) { for (mapping in parseHbmFile(file)) { … } }`（多文件、多映射解析，hbm 命名查询链路需要）；master 侧仍是旧结构，只是把 `FilenameIndex.getFilesByName` 换成 `getVirtualFilesByName(name, scope)`（T-DepApi，三平台 javap 实证无弃用标记）。

**定案**：取 dev 结构；**核对 dev 的 `findHbmXmlFiles` 实现内部是否已用无弃用重载**——若 dev 用的仍是弃用 API，则把 master 的 T-DepApi 修正搬进 dev 的 helper；若 dev 已用 `getVirtualFilesByName`，则 master 侧无净增量，直接取 dev 并删除冲突另一端。实现阶段以 grep `getFilesByName|getVirtualFilesByName` 定夺，verifyPlugin 弃用计数是最终验收（合并后 243/253/262 弃用数不得比合并前回升）。

### 5.7 ui/SqlPreviewPanel.kt —— 常量并集

dev 侧 `RENDER_DEBOUNCE_MS = 150`（T-Perf-04 参数击键防抖）与 master 侧 `PREWARM_POLL_MS = 20L` / `PREWARM_WAIT_MS = 2000L`（T-PreviewFlash 有限等待）互不相干，三者全留。

附核实：该文件 `createEditor` 已在自动合并区解析为 master 的「`awaitPrewarmedTempFile()` 有界等待」版本，dev 侧的 EDT 同步兜底分支（`allowSlowOperations` + `createTempFile`）随之退役——这是 T-PreviewFlash 的既定方向（预热竞态必输的回退路径本就该删），`prewarmPreviewTempFile()` 本体两侧一致，`prewarmedTempFile` 字段与 `awaitPrewarmedTempFile()` 均已在位，无悬空引用。

### 5.8 ui/gutter/JpaGutterLineMarkerProvider.kt —— 取 dev 全文，弃 master 前置守卫

块一：master 在 `if (!gutterEnabled()) return null` 之后插入了两条全锚点前置守卫

```kotlin
if (!isInRepositoryInterface(method)) return null
if (!DerivedMethodLocator.isRepositoryMethod(method)) return null
```

块二：master 的 `isInRepositoryInterface(method)` 私有 helper 与其 KDoc，与 dev 侧同一处 KDoc 的旧措辞相撞。

**定案：取 dev 全文**（165 行三锚点结构：@Procedure / 派生方法 / executor·QBE 调用点），理由有二，均已实证：

1. **语义已被 dev 结构吸收**。dev 的派生方法锚点由 `DerivedMethodLocator.isRepositoryMethod(method)` 门控，而该方法实现（`DerivedMethodLocator.kt:153-157`）为 `isDerivedQuery(method) && isRepositoryInterface(method.containingClass)`——master 的 `isInRepositoryInterface` 判定已被包含，重复前置是冗余。这与上一次 dev 合并的既定结论一致（「JpaGutter 保 dev 三锚点结构，弃 m37 前置守卫」）。
2. **前置守卫会误杀 dev 锚点**。master 的守卫作用于**所有**锚点；而 dev 的 T-J5/T-J20 executor/QBE 锚点锚在 **service 层调用处**（`userRepository.findAll(spec)` 的方法名），其所在类不是 Repository 接口，被前置守卫一律打成 null。这正是记忆里记下的「防杀 T-J5/J20 调用处锚点」。

master 侧维持 99 行旧版式的原因是其 JPA 深化（M39 Wave3）只在 dev 一条线上；master 对同一问题的贡献（M38.1「PartTree 只认 Repository 接口」）语义在 dev 侧完整保留。

### 5.9 test/.../SqlLogManagerAutoShowTest.kt —— 取 master 的两条新用例

add/add 冲突，冲突块内 dev 侧为空、master 侧为两条新用例：

- `testProjectProcessStartClearsSuppression`（项目进程启动清自动展开抑制 + 首条 SQL 恢复展开）
- `testProjectProcessStartSkippedWhenAutoStartOff`（autoStart 关闭时启动不清抑制）

**定案**：保留 master 侧两条用例（T-AutoShowRecover 的防回归锁），dev 侧该位置无内容，文件其余部分为自动合并的共同体。

### 5.10 test/.../SqlLogPanelRendererUiTest.kt —— 两侧断言并集

dev 侧 5 条 T-ListMode 用例（单行恒单行走不走片段缓存 / 关键字换行分行且缓存生效 / 格式化缩进保真 / 模式切换缓存按键失效 / 元信息行不受显示模式影响），master 侧 3 条 T-LogRowNumber 用例（行号片段在元信息行最前 / 无行号条目不渲染 `#` / 行号先于时间戳）。

**定案**：两侧全留。需核对两侧使用的测试辅助方法都在位——dev 用例走 `renderInMode(mode, entry)`，master 用例走 `render(entry)`，两个 helper 必须同时存在（实现阶段核实，缺则补）。

## 六、自动合并区的语义风险清扫

以下区域无文本冲突，但有语义耦合，合并后须逐项核对：

1. **日志条目状态模型**：`LogSqlModels.kt` 的 `sequenceNumber`（master）、`dedupeKey` 指纹键（dev T-Perf-19）、`cachedListLineFragments` / `cachedListLineFragmentsMode`（dev T-ListMode）三者共存；`SqlLogEntryBuffer` 的 `synchronized(entries)` 临界区（dev T-Perf-06）、`addNumbered` 入队分配序号（master）、同一性 `removeEntry`（master）、`sequenceCounter` 的 `clear()` 归零（含暂停/清空两处）三者的交叉——已预检为干净的并集（序号分配在临界区内、删除按同一性、计数器两处归零齐全），仍需以全量测试锁定。
2. **`RunConfigurationProcessListener`**：master 的 `isModuleBasedLaunch(args)` 主配置门控 + dev 的编译期 `MessageBusConnection.subscribe(Topic, L)` 直调 + `onProjectProcessStarted` 回调链，预检为并集；核对行号清零只在主配置启动时发生。
3. **设置项读写对称**：`collapsedGroups`（dev 有字段）是否有写者/读者；`logStopOnToolWindowClose`（两侧都有）的读者是否只在 dev 侧存在——避免出现「有字段无读者」的新假开关（这正是 T-PortLossBatch1 的教训）。
4. **bundle 键对称**：dev 的 T-ErrReport / T-ListMode / 导入导出 / T-LogRowNumber 相关键与 master 的新增键，zh/en 两个 properties 文件键集合必须对称，无重复键。
5. **`SqlLogPanel` 行号片段的着色原子**：master 的 `LIST_META_GRAY` 与 dev 的片段着色体系同源，核对 `#N` 片段在选中态下的可读性（master 用的常量不受选中态影响，与 T-Perf-24「选中态实时计算不进缓存」的既有约定不冲突）。

## 七、实施步骤

### Wave 0 前置（本计划）
- merge-tree 预演 + 11 冲突块逐块分析；本计划文档提交入库。
- 备份：dev 当前位置（`c423986`）打本地分支 `dev-backup-pre-master-merge-20260910`，合并失误可整树复位。
- 并行任务登记表 check（已确认无活跃任务）。

### Wave 1 合并与冲突解决
- dev worktree 执行 `git merge master`，按第五节逐文件解决（10 文件 11 块）。
- CHANGELOG.md 落盘为 LF（第三节），用 `git cat-file blob` 复核新 blob 无 `\r`。

### Wave 2 语义风险清扫
- 按第六节五项逐条核对（grep 悬空引用、bundle 键对称、设置项读写、事件链门控）。

### Wave 3 全量测试与修复
- dev worktree `./gradlew test` 全量（先 parallel_tasks check + 登记；独立 sandbox 名）。
- 红线：0 失败、0 错误。基线预期：dev 侧 3547 量级 + master 侧新增用例（T-LogRowNumber、T-RefreshDbSteal、T-ExecCopyFix、T-PreviewFlash、T-AutoShowRecover、T-PortLossBatch1 等）。
- 已知假失败两类预案（先证伪再排逻辑）：Kotlin 增量编译陈旧类（`build/classes/java/main` 残留旧类劫持编译，javap 鉴别后清目录）、clean 首跑 worker 崩溃。

### Wave 4 构建校验
- `./gradlew verifyPlugin`：243/253/262 三目标 Build Successful 且均 Compatible（发版前硬门禁）；弃用/experimental 计数与合并前对比不得回升。
- `./gradlew buildPlugin` 出 zip 产物。

### Wave 5 定稿与同步
- merge 提交：`merge: master into dev——T-VStack三笔/T-LogRowNumber/T-RefreshDbSteal/T-ExecCopyFix/T-DepApi/T-PreviewFlash/T-AutoShowMaster/T-AutoShowRecover/T-PortLossBatch1四组/v2026.9.9合入，11冲突块解决明细 + CHANGELOG行尾归一修正`。
- 文档同步三处：`docs/变更记录.md` 顶插交付行、`CHANGELOG.md`（已含于冲突解决）、`docs/MEMORY.md` 变更记录行。
- 不主动 push（是否推送由用户决定）。
- runIde 真机验证项留人工：行号显示与启动清零、预览刷新后 DBN 工具栏、单击预览闪烁、临时文件失败文案、JPA gutter 三锚点。

## 八、风险与回滚

- 回滚锚点：`dev-backup-pre-master-merge-20260910`（= `c423986`）；中途废弃 `git merge --abort`，定稿后回退 `git reset --hard dev-backup-pre-master-merge-20260910`。
- 最大技术风险：JpaGutter 与 DataSourceSelector 两块都是「结构性取边」，若判断有误会丢功能。两块均已在第五节给出实证依据（helper 实现、反编译结论），并以全量测试 + 定向 gutter/执行测试双重把关。
- 行尾归一风险：CHANGELOG.md 从 CRLF 变 LF 会使该文件「全文件变更」，提交后 diff 体积大，但这是回归全库约定的必要动作，不改变内容语义。
- 并行会话风险：跑 gradle 前 check 登记表并用独立 sandbox 名；dev worktree 当前 clean（已核实）。

## 九、实施记录（回填）

（实施完成后回填）
