# T-ExecSlowOps：预览/执行临时文件链路线程化双 SEVERE 修复

- 立项日期：2026-09-14
- 任务性质：真机 IDE 异常报告修复（线程语义双缺陷，一 EDT 一 BGT 对向）
- 反馈来源：用户 2026-09-14 粘贴两条真机 SEVERE 堆栈——①gutter 预览 → prepareConsole → `DataSourceSelector.createTempPsiFile`（DataSourceSelector.kt:2954）EDT 上 `PsiManager.findFile` 触发 `Slow operations are prohibited on EDT`；②预览预热线程 `SqlPreviewPanel.mapPreviewDialectBeforePsi`（SqlPreviewPanel.kt:501）BGT 调 `setMapping` 触发 `Access is allowed from Event Dispatch Thread only`（VcsVFSListener.beforeContentsChange 断言），同会话 ×3
- 目标：稳态（非项目首见）预览/执行链 IDE 日志零 SEVERE——慢操作（write/refresh/findFile）全部移出 EDT，`setMapping` 全部回到 EDT；`allowSlowOperations` 逃生舱路线正式作废（243 实测 + 262 字节码双重证伪）；全量测试绿

## 一、需求拆解与任务清单

| # | 任务号 | 内容 | 级别 | 根因/现状状态 |
|---|--------|------|------|--------------|
| 1 | T-ExecSlowOps-01 | 预览预热 `setMapping` 线程语义修复：稳态查已有映射直接跳过（BGT 读），仅项目首见经 invokeLater 到 EDT 执行 | P1 | 实锤（堆栈 ②，§2.2） |
| 2 | T-ExecSlowOps-02 | exec 临时文件后台预热：预览面板构造时 BGT 写盘 + refresh + findFile 填 `cachedTempPsiFile`，EDT 冷路径退化为兜底 | P1 | 实锤（堆栈 ①，§2.1） |
| 3 | T-ExecSlowOps-03 | 缓存槽跨线程可见性：`cachedTempPsiFile`/`cachedTempPsiProjectName`/`lastTempFileFailure` 加 volatile（BGT 写方引入的前提） | P1 | 隐患（§3.2 约束 3） |
| 4 | T-ExecSlowOps-04 | （可选 P2）预览预热项目级化：`previewTempFile` 实例字段导致每次开新面板重复 refresh+映射检查，改项目级复用 | P2 | 冗余非缺陷（§2.2 末段） |

## 二、现状与根因分析

### 2.1 堆栈 ①：exec 冷路径 findFile 在 EDT，慢操作断言按线程判定

触发链：gutter 点击 → `previewAsync` 池线程转换（正确）→ `invokeLater` 回 EDT → `SqlPreviewDialog.<init>` → `SqlPreviewPanel.showResult` → `prepareDefaultConsole`（SqlPreviewPanel.kt:794）→ `chooseAndPrepare` → `consumeCachedTempPsiFile` 未命中 → `createTempPsiFile` 冷路径（DataSourceSelector.kt:2953-2955）EDT 上 `ReadAction.compute { PsiManager.findFile(vf) }` → `FileManagerImpl.findDirectory` → `isUnderIgnored` → `WorkspaceFileIndexDataImpl.ensureIsUpToDate` → SEVERE。

`ReadAction.compute` 不豁免——本机 262 `intellij.platform.core.jar` 反编译（javap，2026-09-14）：

```
assertSlowOperationsAreAllowed:
  0: EDT.isCurrentThreadEdt(); if false → return   // 断言只在 EDT 生效，BGT 天然豁免
  7: isAlwaysAllowed(); return 判定 ...
  : isSlowOperationAllowed()——ourStack 空且 registry other 未开 → 允许；
    ourStack 有 section 且 registry "ide.slow.operations.assertion.<section>" 默认 true → 不允许 → logError
```

两类冷路径命中场景：IDE 启动后首次预览（缓存空）、跨项目切换（T-PreviewExecDs F1 拒绝清缓存）、vf 失效后。新覆盖写入的文件在 WorkspaceFileIndex 无缓存 FileInfo，`isUnderIgnored` 必须 `ensureIsUpToDate` 现算——平台标慢的正是这一步。

### 2.2 堆栈 ②：预览预热在 BGT 调 setMapping，平台内部要求 EDT

`prewarmPreviewTempFile`（SqlPreviewPanel.kt:390）池线程内 `mapPreviewDialectBeforePsi`（:426 调用，:501 反射 invoke `setMapping`）→ 平台 `PerFileMappingsBase.setMapping:208 → handleMappingChange:226 → PsiDocumentManagerImpl.reparseFiles → runWriteAction → FileContentUtilCore.reparseFiles → beforeVfsChange` 消息总线 → `VcsVFSListener.beforeContentsChange` `assertEventDispatchThread` **硬抛** `RuntimeExceptionWithAttachments`。

- 后果链：异常沿 setMapping 抛回，被 `runCatching` 捕获只留 warn——映射本身已在 :208 写入（抛点在 handleMappingChange），新建文件无旧 PSI，reparse 中断无实质损害；真实代价是 SEVERE 上报 ×N + VcsVFSListener 的 before/after 事件对不平衡（未跟踪文件 git 状态刷新延迟一拍）。
- ×3 的成因：`previewTempFile` 是面板**实例字段**，每次开预览弹窗构造新面板都从零预热（写 + refresh + setMapping 全链重跑），进程级幂等短路 `previewDialectMappedVf` 只在同 vf 命中；重启 IDE 后 vf 换新实例即失效，而 workspace.xml 里映射其实还在——每次都白撞断言。
- 平台原生 `ChangeDialectAction` 全程 EDT 调 setMapping，证实「setMapping 须 EDT」不是猜测是硬约束。M36.5.3 的顺序约束（映射须在 findFile 前，否则 PSI 建为 GenericSQL、dumb mode 下 pusher 推迟 → 执行静默失败）仍然成立，两个约束叠加 = **先 EDT 映射、后 BGT findFile** 的两段式。

### 2.3 逃生舱路线作废（防后人再试）

T-PreviewFlash 计划 §154（243 沙盒反编译）：`startSection` 仅非 EDT 压栈、沙盒 `isAlwaysAllowed` 恒 false，EDT 上 `allowSlowOperations(GENERIC)` 空壳。本次 262 字节码复核：`startSection` 反转成仅 EDT 压栈，但压栈后 section 的 registry 键默认 true → 照样不允许。**结论殊途同归：插件进程内无法在 EDT 上压制该断言；非 EDT 则根本不触发。唯一路线是线程归位。** 本计划落 KDoc 时删除 DataSourceSelector KDoc 中「SEVERE 噪音容忍」旧表述。

## 三、修复设计

### 3.1 F1（T-ExecSlowOps-01）：方言映射两段式

`SqlPreviewPanel` 与 `DataSourceSelector` 各有一份同构逻辑（`mapPreviewDialectBeforePsi` :485 / `mapDialectBeforePsi` :3070），本任务先只改预览侧，exec 侧的映射天然在 EDT（createTempPsiFile 现跑 EDT）暂不动，公共抽取列 §五 备注不做（避免与预热编排纠缠写集）。

预览预热线程改为：

```kotlin
// BGT 段（prewarmPreviewTempFile 内，替换现 mapPreviewDialectBeforePsi 调用）
if (previewDialectAlreadyMapped(proj, vf)) {
    // 稳态：workspace.xml 映射还在（或本进程已设过）→ 直接 findFile，零断言
} else {
    // 项目首见：EDT 原生语义执行（invokeLater 有界等待，见下）
    ApplicationManager.getApplication().invokeLater {
        runCatching { /* 现 setMapping 反射段 + previewDialectMappedVf 置位 */ }
        latch.countDown()
    }
    latch.await(2, TimeUnit.SECONDS)   // BGT 等，不阻塞 EDT
}
val psi = ReadAction.compute { PsiManager.getInstance(proj).findFile(vf) }
```

- **映射存在性检查**抽 internal 纯查询 `previewDialectAlreadyMapped(proj, vf)`：反射 `PerFileMappingsBase.getMapping(VirtualFile)` 返回值与 `getAvailableValues()` 中 `Language.id == "MySQL"` 实例比对；反射段包 `ReadAction.compute`（BGT 读合法）。**实施前 javap 本地 database/平台 jar 确认 `getMapping` 签名**（家规：方法名以 jar 反编译为唯一权威），拿不到则降级 `hasMapping(vf)` 判存在。
- **invokeLater + latch 而非 invokeAndWait**：`awaitPrewarmedTempFile` 的 60ms 预算（`PREWARM_WAIT_MS`，DataSourceSelector companion :3114）内 EDT 在 sleep 不泵事件，invokeAndWait 排队到 await 超时才执行——不死锁但白等；统一 invokeLater，BGT 侧 latch 有界等。首见预览必然超 60ms 预算 → `createEditor` 走既有三级降级链（内存 PSI → 裸文档）一次，映射在 latch 前已完成、产物照常填缓存，**第二次预览起全程命中**。「首次降级一次」换取稳态零 SEVERE，符合 T-PreviewDocLock2 F3「功能降级优于硬失败」先例。
- 超时（2s，EDT 冻结病态场景）→ 照样 findFile（GenericSQL PSI）+ warn，行为不差于现状。

### 3.2 F2（T-ExecSlowOps-02）：exec 临时文件后台预热

在 `SqlPreviewPanel` 构造尾部（现 `prewarmPreviewTempFile()` 调用点旁）追加 `prewarmExecTempFile()`：

- **BGT 链**：`projectExecFile(project, sql)` 写盘（复用现有 VFS 安全写入三态，M36.5.13）→ `refreshAndFindFileByIoFile`（BGT 合法，T-GifRerecord 三次退避重试同款）→ 方言映射检查（F1 同两段式，exec 文件独立 vf）→ `ReadAction.compute { findFile }` → 调 DataSourceSelector 新增 internal `markCachedTempPsiFile(project, vf, psi)` 填缓存。
- **EDT 侧零改动语义**：`prepareDefaultConsole` → `chooseAndPrepare` → `consumeCachedTempPsiFile` 命中即返回（现成路径，DataSourceSelector.kt:2941）；未命中（面板构造后毫秒级就触发执行等病态时序）→ 走现 EDT 冷路径兜底——冷路径代码保留不删，KDoc 把「每会话仅首次」旧表述改「仅项目首见/异常时序兜底」。
- **约束 1（SQL 一致性）**：预热与点击执行之间用户可能改了编辑器文本——不改任何东西就不存在：`consumeCachedTempPsiFile` 命中后 exec 文件内容是预热时写入的；而现链路本来也仅在缓存未命中时重写文件。为杜绝旧 SQL 上控制台，**预热写盘前读取 `editor.document.text` 与实际 SQL 复核，不一致则放弃预热**（面板构造即预热，此刻文本必等于渲染结果，复核是防御）。执行换入共享控制台时 `reuseSharedConsole` 已有同步 SQL 语义（M36.5.5），不另加。
- **约束 2（防重入）**：`AtomicBoolean execPrewarmInFlight` 面板级防重（同 preview 侧 `previewDisposed` 守卫风格）；面板 dispose 时置 `previewDisposed` 复用现有标志，预热尾段检查失效即弃。
- **约束 3（跨线程可见性，T-ExecSlowOps-03）**：BGT 成为 `cachedTempPsiFile`/`cachedTempPsiProjectName`/`dialectMappedVf`/`lastTempFileFailure` 的第二个写方后，companion 字段全部加 `@Volatile`；竞态收敛依赖 `isCacheUsable` 有效性/项目前缀守卫（现成），最坏收敛为重建缓存，无正确性风险。`markCachedTempPsiFile` 与既有测试缝 `markCachedTempPsiFileForTest` 合并为 internal 正函数 + 测试缝改薄委托。

### 3.3 T-ExecSlowOps-04（P2 可选）：预览预热项目级复用

`prewarmPreviewTempFile` 的产物与 `previewTempFile` 挂点改挂到以项目为键的缓存槽（vf 有效即复用，跳过写盘/refresh/映射检查，直接重取 PSI）。风险：同一 vf 同时挂两个面板编辑器（预览弹窗单例化后同项目至多一个存活，F2 已保证）——实施时若与 F1/F2 测试周期冲突可单独砍掉，不阻塞交付。

### 3.4 测试

- 纯函数桶（新建 `ExecSlowOpsRoutingTest`）：映射两段式路由决策抽纯（`decideMappingRoute(mappedOnDisk, inProcessMapped) → Skip | EdtSet | ContinueBgt` 表驱动）；`markCachedTempPsiFile` 项目绑定断言（复用 isCacheUsable 既有表驱动）；防回归断言「BGT 段不出现 setMapping 反射直调」并入 `BannedApiSourceScanTest` 源扫描（扫描 `prewarmExecTempFile`/`prewarmPreviewTempFile` 方法体内不得含 `setMapping` 字面量，EDT 段用独立函数承载）。
- 既有回归：`ExecTempFileFixTest`、`DataSourceSelectorIntegrationTest`、`DbConsoleIntegrationTest`、`SqlPreviewDialogTest` 全绿。
- 沙盒日志守卫（真机走查项，不做自动化）：demo 项目连开 3 次预览 + ▶ 执行后 grep `idea.log`，`Slow operations are prohibited` 与 `Access is allowed from Event Dispatch Thread` 计 0（项目首见那一次除外）。

## 四、实施顺序与预估

1. T-ExecSlowOps-01 F1 预览映射两段式 + `previewDialectAlreadyMapped` 抽取（javap 定签名，~1h）
2. T-ExecSlowOps-02/03 exec 预热链 + volatile + `markCachedTempPsiFile` 收编（~1.5h）
3. T-ExecSlowOps-04（视 1/2 用时决定，~0.5h）
4. 测试新增/源扫描守卫、全量 `test` 绿（~0.5h）
5. 三处同步：本计划实施记录回填 + `docs/MEMORY.md` 计划索引行 + 变更记录总册行（~0.2h）

## 五、验收清单

- [ ] 预览弹窗（非项目首见）打开：IDE 日志无 `Access is allowed from Event Dispatch Thread`（堆栈 ② 场景消除），且预热日志显示 `dialect already mapped, skip` 类命中
- [ ] gutter 预览 → 控制台默认展示（非首见）：日志无 `Slow operations are prohibited on EDT`（堆栈 ① 场景消除）；exec 缓存命中日志出现于 prepareConsole 前
- [ ] 项目首见（删 `.idea/sql-visualizer/` 与 workspace.xml 方言映射节点后首开）：允许一次既有形态降级（内存 PSI 回退），第二次预览起完整命中、日志干净
- [ ] 执行链不回归：预览 ▶ 执行、跨项目两开（T-PreviewExecDs 场景）、切换数据源、M36.5.3 场景（索引期执行不静默——MySQL 方言 PSI 生效）
- [x] 源扫描守卫绿：`setMapping` 反射字面量 src/main 收口 DialectPreMapping.kt 单入口（守卫口径较原计划收紧）；全量测试 3979/0/0/6 绿；verifyFull 按既定口径仅发版前跑
- [x] DataSourceSelector `createTempPsiFile` KDoc「容忍 SEVERE 噪音」旧表述更新为本计划结论

## 六、真机走查清单（runIde，留人工）

1. demo 项目删映射节点冷启动 → 首次 gutter 预览：记录一次降级现象（编辑器可用、无 VFS 文件挂载提示），二次预览完整展示原生控制台
2. 连开 3 次预览 + 每次 ▶ 执行：idea.log 双断言字样计 0；git 变更视图 mpsql-preview.sql/mpsql-exec.sql 状态刷新正常（VcsVFSListener 事件对不再失衡）
3. 双项目同开各自预览执行：exec 缓存项目绑定不串（T-PreviewExecDs 场景复查）
4. 索引进行中（大项目冷开）预览 + 立即执行：方言映射路径生效，执行有结果 Tab
5. Community Edition（无 Database 插件）：静默回退不回归

## 七、实施记录（回填）

**2026-09-14 实施完成**（worktree `wt-t-execslowops`，分支 `t-execslowops`）：

1. **F1 方言映射两段式收编**：新建 `execute/DialectPreMapping.kt`（internal object）——
   `shouldSkip` 纯函数路由 + `configuredAsMySql` BGT 读探测（getConfiguredMapping，243/262 双版
   javap 实锤 public 无 EDT 限制）+ `setMySqlOnEdt`（EDT 硬校验，非 EDT 拒绝并 warn 防御网）+
   `ensureMapped` 编排（EDT 直调走同步语义；BGT 读探测稳态跳过 / 首见 `invokeLater` 派 EDT +
   `CountDownLatch.await(2000ms)` 有界等待，绝不 invokeAndWait）。两处调用点收编为薄委托：
   `SqlPreviewPanel.mapPreviewDialectBeforePsi`（面板级 `previewDialectMappedVf` 短路 + 回写）与
   `DataSourceSelector.mapDialectBeforePsi`（exec 级 `dialectMappedVf` 同款）。真机堆栈②
   （BGT setMapping → VcsVFSListener 断言，SEVERE ×4）自此消除。
2. **F2 exec 临时文件 BGT 预热**：新增 `DataSourceSelector.prewarmExecTempFile(project, sql)`
   （BGT 专属入口，EDT 直调拒绝）——EDT 守卫 → 缓存命中改「仅刷新文件内容」（projectExecFile
   doc 驱动三方同步，连续预览 SQL 变化时保 attachConsole 初始内容一致）→ AtomicBoolean
   `execPrewarmInFlight` 防重 → 写盘 → WIRA refresh → F1 两段式映射 → ReadAction findFile →
   `markCachedTempPsiFile`（新 internal 正函数，测试缝 `markCachedTempPsiFileForTest` 改薄委托）。
   挂点 = `SqlPreviewService.previewAsync` compute 之后、`invokeLater` 派发弹窗之前，独立池线程
   fire-and-forget（不推迟弹窗）；守卫 = 确定性 SQL（previewModel/batchModel/xmlModel/fillClauses
   全空）+ `ExecuteSqlSupport.isAvailable()`。**挂点与原计划 §3.2 面板构造器方案偏差**：
   previewAsync 是 gutter + 全部工具栏/快捷键入口的单一池线程收口点，一处接线全覆盖且天然
   拿到确定性 result.sql，故改挂此处。`createTempPsiFile` KDoc 删除「容忍 SEVERE 噪音/后台化
   经评估否决」旧表述，改注「预热 + 冷路径兜底」新形态（验收项 ⑥）。
3. **F3 跨线程可见性**：`cachedTempPsiFile`/`cachedTempPsiProjectName`/`dialectMappedVf`/
   `lastTempFileFailure` 全部 `@Volatile`（BGT 成为第二写方）。
4. **F4** 按预案砍掉（F1/F2 已消双 SEVERE，预览面板级项目缓存不阻塞交付）。
5. **测试**：新增 `ExecSlowOpsRoutingTest` 3 例（shouldSkip 表驱动）；
   `BannedApiSourceScanTest` 新增守卫「`"setMapping"` 字面量 src/main 只允许
   DialectPreMapping.kt 单入口」（等价承载原计划『预热方法体内无 setMapping 直调』意图，
   收口更强——任何新文件反射直调都红）。全量裸命令 **3979/0/0/6 绿**（基线 3975+4）。
6. **合入 master**（merge 80e7d9c，docs 两表按时间序并集解冲突）：合并后 detached worktree
   独立沙盒全量 **4028/0/0/6 绿**（中途 XmlPreviewPanelTest 剪贴板 1 例=并行会话抢系统
   剪贴板环境型 flake，单类重跑绿）；分支与 worktree 用后即删。

F2 SQL 时效性说明：预热写 `result.sql` 原文与面板渲染文本差异由既有三级同步链吸收
（reuseSharedConsole setText / prepareAfterEditorResolved console-doc→vf 同步 + smart
updateScriptModel / executeOnConsole 执行前同步），与 T-PreviewFlash 缓存命中路径同款语义，
未新增同步面。真机走查清单（§六）留人工。
