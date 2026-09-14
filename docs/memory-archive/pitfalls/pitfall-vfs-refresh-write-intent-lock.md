---
name: pitfall-vfs-refresh-write-intent-lock
description: 262 平台 EDT ReadAction 包同步 VFS refresh 硬抛 write thread
  only——ReadAction 会 check out EDT 隐式 write-intent lock，同步 refresh 须
  WriteIntentReadAction/裸调；T-PreviewWiLock 已实施（27ccfcf，3087 绿），
  runIde 真机双击验证待人工（2026-09-08）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5a4de9e4-1369-4a15-a351-322dea96cebc
---

2026-09-08 用户报：双击 SQL 日志预览抛 `RuntimeExceptionWithAttachments: Access is allowed from write thread only`。**已修复交付**：master `27ccfcf`（T-PreviewWiLock，全量 3087/0/0/6 绿，fix+docs 两提交；计划文档 `docs/开发计划/20260908-0915-T-PreviewWiLock-*-开发计划.md` §六 实施记录）。剩余：runIde 真机双击验证待人工（统一走 [[runide-demo-verify-workflow]]）；可提炼全局 pitfall。

**根因**：`ui/SqlPreviewPanel.kt` createEditor 回退分支（1251→1256）把 `LocalFileSystem.refreshAndFindFileByIoFile`（同步 VFS refresh）包在 `ReadAction.compute` 里。EDT 事件分发隐式持有 write-intent lock，但进入 ReadAction.compute 时平台把 WI lock check out（让后台线程能推进），lambda 内只剩 read lock；262 平台 `RefreshQueueImpl.processEvents → assertWriteSafeEnvironment` 对同步 refresh 硬断言，必抛。

**回退分支是事实主路径**：`SqlPreviewDialog` 构造函数内同步 `panel.showResult → renderSql → showSql → createEditor`，而预热线程在面板构造尾部才发起（pooled，几十 ms）——EDT 几 ms 就到 createEditor，`prewarmedTempFile`/`previewTempFile` 必为 null。凡走 `SqlPreviewService.Companion.showResult` 直弹弹窗的入口（日志双击、gutter 图标）预热竞态必输，T-M36.6-03 的预热救不了这条链。

**为什么现在才炸**：535e8ed（M34）为消 EDT SlowOperations 硬抛加的 ReadAction 包装；243 平台 refresh 的 write-safe 检查宽松（仅 SlowOperations SEVERE 噪音），沙盒切 262（IDE 2026.2.1）后变硬断言——平台版本迁移的审计点。

**修复（已实施，27ccfcf）**：SqlPreviewPanel.kt createEditor 回退分支 `ReadAction.compute<Pair<...>, Throwable>` → `WriteIntentReadAction.compute<...>`——javap 243 基线 `ideaIU-2024.3.7.1/lib/util-8.jar` 核实签名同形（`compute(ThrowableComputable<T,E>): T throws E`），仅换类名。WI lock 隐含读权限，lambda 内 `PsiManager.findFile` 不受影响；外层 allowSlowOperations 逃生舱照旧；后台预热线程（306 行）在 pooled 上 refresh 不走 EDT 断言，无需改。同库正确范式对照：`execute/DataSourceSelector.kt:2604` createTempPsiFile 刻意把 refresh 放 ReadAction 外、只把 findFile 包进 ReadAction。全库排雷：其余 3 处 `refreshAndFindFileByIoFile`（306 预热 pooled / DataSourceSelector EDT 裸调 / DatabaseConsoleLauncher EDT 裸调）均不触发断言。

**可推广结论**（候补全局 pitfall- promoting）：同步 VFS refresh（`refreshAndFindFileByIoFile`/`refresh`）必须在 write-safe context——EDT 上要么裸调（事件分发隐式 WI lock，参照 DataSourceSelector 写法），要么 `WriteIntentReadAction.compute` 包裹；**绝不能**包 `ReadAction.compute`。与全局已有 pitfall-slowoperations-edt-readaction 同族（都是 EDT ReadAction 的副作用），修复合入后本条可归档并提炼进全局。关联 [[runide-demo-verify-workflow]]（262 沙盒切换背景）。
