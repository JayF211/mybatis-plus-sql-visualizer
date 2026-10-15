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

## 4. 实施方案（单点最小修复）

1. `execute/DataSourceSelectorResults.kt` `prewarmExecTempFileInner`：
   - refresh 段摘除 `WriteIntentReadAction.compute` 包装，改裸调 `LocalFileSystem.getInstance()
     .refreshAndFindFileByIoFile(ioFile)`（与同函数上方 pre-refresh 段裸调同形态）；
   - 原 `// T-ExecCopyFix F2b：refresh 段 WriteIntentReadAction（BGT 上 WIRA 合法…）` 注释改写为
     T-PrewarmWilDeadlock 死锁说明（引用本计划与转储证据），防止后人再「同款复制」回去；
   - 异常处理与 fatal 透传维持原状（裸调同样可抛，catch 链不变）。
2. 全仓 WIRA 站点复核后**明确不动**：`createTempPsiFile`（EDT 冷路径）、`DataSourceSelectorConsole`
   attachConsole（EDT、无 sync refresh）、`DatabaseConsoleLauncher.createTempFile`（EDT 裸调）、
   `SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile`（BGT 裸调，本事件中的陪葬者非根因）。

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
- K2J 端口分支按既有 T-SyncK2JMaster 机制在下一轮同步，不在本任务内移植。

## 实施记录

（回填本工作树副本）
