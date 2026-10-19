# T-PreviewWiLock：日志双击预览 EDT 内 VFS 刷新断言崩溃修复 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `f4a818d`）
> **优先级**：P0（SQL 日志双击预览主路径 100% 崩溃，弹窗打不开）
> **预估工时**：0.5~1h（含全量回归）
> **来源**：用户真机（IDE 2026.2.1 沙盒）双击 SQL 日志条目，预览弹窗抛 `RuntimeExceptionWithAttachments: Access is allowed from write thread only`

---

## 一、问题与根因

### 1.1 现象

双击 SQL 日志面板中的 SQL 条目，预览弹窗未打开，IDE 抛错弹（`idea.log` 同步记录）：

```
com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments: Access is allowed from write thread only;
If you access or modify model on EDT consider wrapping your code in WriteIntentReadAction or ReadAction
Current thread: Thread[#58,AWT-EventQueue-0,10,main] (EventQueue.isDispatchThread()=true)
  at ThreadingAssertions.assertWriteIntentReadAccess
  at TransactionGuardImpl.assertWriteSafeEnvironment
  at RefreshQueueImpl.execute$intellij_platform_ide_impl
  at RefreshSessionImpl.launch / RefreshQueueImpl.processEvents
  at VirtualDirectoryImpl.refreshAndFindChild（VFS 同步 refresh）
  at LocalFileSystem.refreshAndFindFileByIoFile
  at SqlPreviewPanel.createEditor$lambda$1$0(SqlPreviewPanel.kt:1256)   ← 业务帧
  at ReadAction.compute(SqlPreviewPanel.kt:1251)                        ← 业务帧
  at SqlPreviewPanel.showSql → renderSql → showResult
  at SqlPreviewDialog.<init>(SqlPreviewDialog.kt:33)
  at SqlPreviewService$Companion.showResult(SqlPreviewService.kt:1334)
  at SqlLogPanel.openPreview(SqlLogPanel.kt:1034)（mouseClicked EDT 事件）
```

### 1.2 根因：同步 VFS refresh 被包在 EDT 的 ReadAction 里

三层成因：

1. **直接根因**：`SqlPreviewPanel.createEditor` 回退分支把 `LocalFileSystem.refreshAndFindFileByIoFile`（同步 VFS refresh）包在 `ReadAction.compute` 内（SqlPreviewPanel.kt:1251→1256）。2024.2+ 平台线程模型要求同步 VFS refresh 必须在 write-safe context（持有 **write-intent lock**）执行。EDT 事件分发本身隐式持有 write-intent lock，本不会出事；但一旦进入 `ReadAction.compute`，平台把 write-intent lock **check out**（让后台线程的 WriteIntentReadAction 可推进），lambda 内只剩 read lock → `RefreshSessionImpl.launch` → `assertWriteSafeEnvironment` → `assertWriteIntentLockAcquired` 硬抛。
2. **触发条件**：日志双击入口（`SqlLogPanel.openPreview` → `SqlPreviewService.showResult` → `new SqlPreviewDialog`）在**弹窗构造函数内同步**执行 `panel.showResult → renderSql → showSql → createEditor`（首帧 editor 为 null）。而 T-M36.6-03 的预热线程是面板构造尾部才发起的（pooled 线程，建文件 + VFS refresh + PSI 解析需数十 ms），EDT 几 ms 内就跑到 `createEditor`——预热**竞态必输**，`prewarmedTempFile`/`previewTempFile` 恒为 null，每次双击都走 EDT 同步回退分支。即回退路径是这条入口的事实主路径，预热救不了场。
3. **为什么现在才炸**：`e6cae69`（M34）引入该 `ReadAction.compute` 包装时目标是消 EDT SlowOperations 硬抛，当时 243 平台对 VFS refresh 的 write-safe 检查不硬抛（只产生 SlowOperations SEVERE 日志噪音，M36.6.1/M54 治理记录可证），代码"带病存活"。沙盒切 262（IDE 2026.2.1）后 `RefreshQueueImpl` 的 write-safe 断言变硬抛，回退路径一踩即崩。旁证：262 平台已将 `ReadAction.compute(ThrowableComputable)` 标 deprecated（DataSourceSelector.kt T-M52 注释），方向上本就该迁离裸 ReadAction。

### 1.3 全库排雷：同类隐患仅此一处

`refreshAndFindFileByIoFile` 全库 3 处调用：

| 调用点 | 线程上下文 | 是否踩断言 |
|--------|-----------|:---:|
| SqlPreviewPanel.kt:1256（EDT 回退分支） | **ReadAction.compute 内** | 是（本次修复点） |
| SqlPreviewPanel.kt:306（后台预热） | pooled 线程裸调，后台同步 refresh 不走 EDT write-safe 断言 | 否 |
| DataSourceSelector.kt:2604（EDT） | EDT 裸调（allowSlowOperations 内、ReadAction **外**），事件分发隐式 write-intent | 否 |
| DatabaseConsoleLauncher.kt:52（EDT，反射入口） | EDT 裸调，同上 | 否 |

---

## 二、技术方案

单点替换：`SqlPreviewPanel.kt:1251` 的 `com.intellij.openapi.application.ReadAction.compute<Pair<VirtualFile, PsiFile>, Throwable>` → `com.intellij.openapi.application.WriteIntentReadAction.compute<Pair<VirtualFile, PsiFile>, Throwable>`。

- **API 权威核实**（243 编译基准 `ideaIU-2024.3.7.1` 的 `lib/util-8.jar` javap）：
  ```
  public static <T, E extends java.lang.Throwable> T compute(com.intellij.openapi.util.ThrowableComputable<T, E>) throws E
  ```
  与现有 `ReadAction.compute` 泛型形态完全同形，仅换类名。
- **write-intent lock 隐含读权限**：lambda 内的 `PsiManager.findFile`、`FileTypeManager` 访问不受影响；EDT 上 `WriteIntentReadAction.compute` 对已隐式持有的 write-intent lock 是可重入获取，无死锁风险。
- **SlowOperations 包装保持不动**：外层 `SlowOperations.allowSlowOperations(GENERIC)` 逃生舱与锁语义正交，`findFile`（→ WorkspaceFileIndex ensureIsUpToDate）的慢操作豁免依旧生效。
- **后台预热线程（306 行）不动**：pooled 线程同步 refresh 无 EDT write-safe 断言。
- **不重构预热竞态**（回退分支实为主路径这一事实）：本次只消崩溃；预热对日志双击入口的"竞态必输"不影响正确性（回退修复后路径合法），留给后续性能治理统一评估。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 改 `SqlPreviewPanel.createEditor` | 1251 行 `ReadAction.compute` → `WriteIntentReadAction.compute`，注释同步（记录 262 write-safe 断言根因） |
| 2 | 全量回归 | gradlew 前 `python parallel_tasks.py check`；`./gradlew test --no-build-cache --no-configuration-cache`，基线绿 |
| 3 | runIde 真机验证（人工跟进项） | 沙盒双击 SQL 日志条目：弹窗正常打开、无报错；统一走 runide-demo-verify-workflow |
| 4 | 文档三处同步 | 变更记录.md 追加（日期倒序）+ 本计划 §实施记录 + docs/MEMORY.md 变更记录 |
| 5 | 分开提交 | fix 与 docs 两个 commit，四段式提交信息 |

---

## 四、测试计划

本缺陷为 EDT 线程断言，headless 测试环境（`BasePlatformTestCase`）无真实 `IdeEventQueue` 事务分发上下文，`ThreadingAssertions.assertWriteIntentReadAccess` 不走同路径，**无法单测复现**。验证策略：

| 层级 | 做法 | 判定 |
|------|------|------|
| API 存在性 | 243 distro `lib/util-8.jar` javap（已做，见 §二） | 签名同形，编译期即校验 |
| 编译回归 | 全量 `./gradlew test`（243 编译目标） | 全绿即通过 |
| 真机回归 | runIde 沙盒：双击 SQL 日志条目 → 预览弹窗；另验 gutter 图标点击预览（同 `SqlPreviewService.showResult` 直弹入口） | 弹窗正常打开，`idea.log` 无 RuntimeExceptionWithAttachments |

---

## 五、风险与回滚

- **风险低**：单行 API 换名，锁语义从 read 升级为 write-intent（隐含读）。EDT 持 write-intent 期间会短暂阻塞后台 `WriteIntentReadAction`，但预热线程只做后台 refresh 不持 write-intent，实际无可感知影响。
- **行为差异**：无用户可见行为变化，仅崩溃消除。
- **回滚**：revert 单个 commit 即可。

---

## 六、实施记录（2026-09-08，已实施）

1. 单点替换落地：`SqlPreviewPanel.createEditor` 回退分支 `ReadAction.compute` → `WriteIntentReadAction.compute`（全限定名引用，与文件既有风格一致），行内注释记录 262 平台 write-safe 断言根因与"write-intent 隐含读权限"约束（T-PreviewWiLock）。
2. API 权威核实：javap 243 编译基准 `ideaIU-2024.3.7.1/lib/util-8.jar`，`WriteIntentReadAction.compute(ThrowableComputable<T,E>): T throws E` 与原 `ReadAction.compute` 调用形态同形，仅换类名即编译通过，无其他适配。
3. 全库排雷结论与 §1.3 一致：本次为唯一隐患点，其余三处调用点（后台预热 pooled / EDT 裸调 ×2）均不触发该断言，未改动。
4. 测试：headless 无法复现 EDT 线程断言（§四）——全量 `./gradlew test --no-build-cache --no-configuration-cache` **3087/0/0/6 全绿**（251 结果文件，exit 0），与 T-DaoSql 基线（3086/0/0）同水位。
5. 同步情况：CHANGELOG.md `[Unreleased]` 新增 `### Fixed` 分区条目；`docs/变更记录.md` + `docs/MEMORY.md` 变更记录两行（docs 提交）。runIde 真机双击验证为人工跟进项（统一走 runide-demo-verify-workflow）。
