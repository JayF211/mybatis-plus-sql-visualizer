# T-PrewarmWilDeadlock 开发计划：预览预热写意向锁自锁卡死修复

- 日期：2026-10-15
- 任务码：T-PrewarmWilDeadlock
- 工作树：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-prewarm-wil`
- 分支：`t-prewarm-wil-deadlock`（自 master@3588b04c）
- 状态：实施中

## 1. 现象与证据

用户真机（IU 2026.2.1，JBR 25）打开 SQL 预览弹窗后，右侧恒停「正在准备预览...」，稍后 IDE 弹
「IntelliJ IDEA is not responding」。用户提供 `threadDump-20261015-181620`，三段关键栈：

1. **EDT（AWT-EventQueue-0）**：`EditorImpl$MyMouseAdapter.mousePressed → EditorThreading.runWritable
   → WriteIntentReadAction.run → NestedLocksThreadingSupport.acquireWriteIntentPermit`——排队等**写意向锁**，
   进平台 Suvorov「等待写锁」覆盖层（即 not responding 横幅 + 本转储的触发者）。（转储 L1-61）
2. **池线程 756（ApplicationImpl pooled thread 756）**：
   `SqlPreviewService.previewAsync → DataSourceSelector.prewarmExecTempFile → prewarmExecTempFileInner
   → WriteIntentReadAction.compute { LocalFileSystem.refreshAndFindFileByIoFile }
   → RefreshSessionImpl.launch → RefreshQueueImpl.execute → RefreshSessionImpl.waitFor`，TIMED_WAITING 永久驻留。
   （转储 L1127-1200）
3. **池线程 733**：`SqlPreviewPanel.prewarmPreviewTempFile` 裸调同一 `refreshAndFindFileByIoFile`，
   同样卡在 `RefreshSessionImpl.waitFor`——它是受害者（无锁在身），EDT 被卡死后其刷新会话永远无人推进。
   （转储 L1201-1263）

## 2. 根因

`prewarmExecTempFileInner`（今 `DataSourceSelectorResults.kt`）在**后台线程**上以
`WriteIntentReadAction.compute` 包裹同步 `refreshAndFindFileByIoFile`：

- 该线程由此持有写意向锁（WIL），随后阻塞等待 VFS 刷新会话完成；
- 262 平台 `RefreshSessionImpl.launch` 从 BGT 恒走「异步会话 + waitFor」（转储中持锁者与裸调者两条栈
  都停在 waitFor，实证非调用线程同步处理）；
- 会话推进需要 WIL 空闲（及/或 EDT 推进），而 WIL 被等待者自己持有——自锁；用户此时在预览编辑器上点击，
  EDT 也排进等 WIL 队列，互等扩大为全 IDE 冻结。

**引入史**：T-ExecCopyFix F2b（27799c37）为修「262 平台 EDT + plain ReadAction 内同步 refresh 硬抛
write thread only」，给 **EDT 冷路径** `createTempPsiFile` 的 refresh 段包了 WIRA——该语境安全（EDT 持
WIL 时刷新会话在调用线程同步处理）。随后 T-ExecSlowOps（1198fee5）把预热链整体搬到 BGT，从冷路径
**同款复制**了 WIRA 包装（`git show 1198fee5` L3164-3170 实证预热链生而带 WIRA），自锁自此埋下。
243 定向测试跑不出 262 NestedLocksThreadingSupport 语义，故从未暴露。

## 3. 待验证前提

- 前提 A：262 平台 BGT **裸调** `refreshAndFindFileByIoFile` 不硬抛。——已被转储证实（733 线程裸调走
  waitFor 而非异常栈）；同函数 pre-refresh 段的裸调 `findFileByIoFile` 亦有「prewarm DONE 日志实证 BGT
  可调」先例。✅ 已验证。
- 前提 B：EDT 冷路径 `createTempPsiFile` 的 WIRA 包装必须保留——它修的是 262 EDT 读权限硬抛，
  且 EDT 语境无自锁。本轮不动它。✅（F2b 实机先例）
- 前提 C：用户点击不是死锁必要条件——756 持 WIL 等「需要 WIL/EDT 的会话」本身即环；点击只是让 EDT
  排队等锁、亮出 not responding。修复后即使无点击，预热链也应正常走完。留真机复验确认。

## 4. 实施方案

**W1（根因，单点最小修复）**

1. `execute/DataSourceSelectorResults.kt` `prewarmExecTempFileInner`：
   - refresh 段摘除 `WriteIntentReadAction.compute` 包装，改裸调 `LocalFileSystem.getInstance()
     .refreshAndFindFileByIoFile(ioFile)`（与同函数上方 pre-refresh 段裸调同形态）；
   - 原 `// T-ExecCopyFix F2b：refresh 段 WriteIntentReadAction（BGT 上 WIRA 合法…）` 注释改写为
     T-PrewarmWilDeadlock 死锁说明（引用本计划与转储证据），防止后人再「同款复制」回去；
   - 异常处理与 fatal 透传维持原状（裸调同样可抛，catch 链不变）。
2. 全仓 WIRA 站点复核后**明确不动**：`createTempPsiFile`（EDT 冷路径）、`DataSourceSelectorConsole`
   attachConsole（EDT、无 sync refresh）、`DatabaseConsoleLauncher.createTempFile`（EDT 裸调）、
   `SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile`（BGT 裸调，本事件中的陪葬者非根因）。

**W2（有界预热兜底，用户 2026-10-15 当日追加：超时自动终止）**

W1 摘环后本路径不可能再自锁，但「同步 refresh/readAction/EDT 派发等平台等待因其他原因挂死」这类
同族风险仍存在。预热是投机性增强（previewAsync 侧 fire-and-forget，弹窗冷路径本就能按需重建），
故给 exec 预热整链加 30 秒预算：

- `execute/DataSourceSelector.kt` `prewarmExecTempFile`：真实链 `prewarmExecTempFileInner` 改经
  `AppExecutorUtil` 独立执行 + `future.get(30s)` 预算等待；超时 `cancel(true)`（中断，
  `RefreshSessionImpl.waitFor` 内部的 runBlockingMaybeCancellable 通常响应中断）放弃本轮并 WARN，
  **在途标记在 finally 照常释放**——否则一次挂死会让 `execPrewarmInFlight` 恒 true，后续预热全部
  skip，预热能力静默报废；
- ExecutionException 拆包：fatal 原样上浮（T-FatalPassthrough 语义与直跑一致），非 fatal 仅 warn；
- 边界声明：cancel 若因平台内部不可中断而未真正唤醒滞留线程，业务也已脱困（不再等待、标记已释放），
  滞留线程无 WIL 在身、无 EDT 阻塞面，仅占一条池线程；
- 面板侧预热（`prewarmPreviewTempFile`）不加同款预算：其消费侧已有 `PrewarmAwait` 预算超时降级，
  生产者滞留仅占池线程不可见面。

## 5. 守卫测试

新增 `convention/SyncRefreshWriteIntentGuardTest`（复用 BannedApiScanSupport 的 maskNonCode 等基建）：

- 规则一（负向）：`prewarmExecTempFileInner` 函数体（涂白注释/字符串后）不得含 `WriteIntentReadAction`；
- 规则二（正向）：`createTempPsiFile` 函数体须同时含 `WriteIntentReadAction` 与
  `refreshAndFindFileByIoFile`——防未来「清理」把 262 EDT 冷路径修复也顺手摘了；
- 反向验证：临时把 WIRA 包回预热链 → 守卫必须红（贴读数）；还原 → 绿。空绿不算数。

## 6. 验证阶梯

1. 定向：`ExecDocSyncTest` + `SyncRefreshWriteIntentGuardTest`（跑前 `parallel_tasks.py check` + register）。
2. 守卫自证用例随测试类提交。
3. 真机复验（用户侧，不在本轮门禁）：冷启动 IDE 后首开预览弹窗（必走真实预热链），「正在准备预览...」
   应正常出 SQL；IDE 不再弹 not responding。

## 7. 非目标

- 不改预览面板路径的裸调 refresh（平台合法形态）；
- 不做「findFileByIoFile 命中即跳过 sync refresh」之类的优化（本任务的锅是 WIL 自锁，不是 refresh 慢）；
- 不给面板侧预热生产者加预算（消费侧 PrewarmAwait 已有界）；
- K2J 端口分支按既有 T-SyncK2JMaster 机制在下一轮同步，不在本任务内移植。

## 实施记录

2026-10-15 当轮完成，W1+W2+守卫全部落地（工作树 mybatis-plus-sql-visualizer-prewarm-wil）：

- W1：`DataSourceSelectorResults.kt` `prewarmExecTempFileInner` refresh 段摘除 WIRA 包装改裸调，
  首部注释改写为死锁说明（引用 threadDump-20261015-181620）。
- W2：`DataSourceSelector.kt` `prewarmExecTempFile` 改独立执行 + 30s 预算（`EXEC_PREWARM_BUDGET_MS`），
  超时 cancel+放弃+照常释放在途标记；ExecutionException 拆包 fatal 透传/非 fatal warn；
  新增常量紧邻 `execPrewarmInFlight`。中途一次编译红：`AppExecutorUtil` 包名误写
  `com.intellij.util.`（正解 `com.intellij.util.concurrency.`，与 IdeErrorReportSubmitter 既有用法对齐后过）。
- 守卫：新增 `convention/SyncRefreshWriteIntentGuardTest`（7 用例：规则一/二/三 + 4 条合成样本自证），
  判定内核 `syncRefreshInsideWiraOffenders` 已登记元守卫 GUARD_CLASSES。
- 验证读数（`test --tests` 定向，`--no-configuration-cache`）：
  - 修复态：SyncRefreshWriteIntentGuardTest **7/0/0**、GuardSelfTestCoverageMetaGuardTest **8/0/0**、
    ExecDocSyncTest **14/0/0**；
  - 反向验证（WIRA 装回预热链）：同 7 用例 **2 failures**——恰规则一 + 规则三（规则三精确报
    DataSourceSelectorResults.kt:1218），其余 5 用例（含规则二与全部自证）保持绿，判别力精确；
  - 还原后复跑：7/0/0 + 8/0/0 绿（读数落在将要提交的确切树上）。
- 临时沙盒（MPSV-PrewarmWil，build.gradle.kts 临时行）已按协议还原，未入库。
- 待真机复验（用户侧，262）：冷启动 IDE 首开预览弹窗应正常出 SQL、不再 not responding；
  极端挂死场景 30s 后 WARN 放弃（日志关键字 `[M36] exec prewarm exceeded`）。
- 未做（本计划 §7 非目标）：面板预热生产者预算、K2J 移植（下一轮 T-SyncK2JMaster 同步）。
