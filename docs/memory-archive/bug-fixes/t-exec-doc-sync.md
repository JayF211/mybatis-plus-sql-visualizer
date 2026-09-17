---
name: t-exec-doc-sync
description: T-ExecDocSync 执行文件 File Cache Conflict 根治已交付（master a7ca191）+ 两条
  262 平台 API 硬发现（Document.isModified 移除 / 文件 document setText BGT 硬抛 EDT）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1c98fa96-e1b0-40ee-8d32-d399cc7558b8
---

T-ExecDocSync 已交付：master a7ca191（2026-09-16），testShards 全量 4180/0/6 绿，ExecDocSyncTest 7 用例。**待办**：① CHANGELOG [Unreleased] Fixed 条目 + docs/变更记录 行未落（实施时两文件被并行发行会话 20260916-0900 未提交改动占用，避吞并回避；合流/发行时补）；② 真机走查三条留人工（计划文档 §五：预览→执行→console 手改→重预览无弹窗 / 关自动保存复跑 / git 触碰 .idea 场景）。计划与实施全录：docs/开发计划/20260916-0945-T-ExecDocSync-执行文件缓存冲突根治-开发计划.md §八。

**两条 262 平台 API 硬发现**（javap 反汇编+沙盒堆栈双实证，跨任务通用）：
1. **`Document.isModified()` 已从接口移除**（262 intellij.platform.core.jar 实证，仅余 `isLineModified(int)`/`getModificationStamp()`）——「已修改未保存」态正确查询 = `FileDocumentManager.isDocumentUnsaved(doc)`；Kotlin 对 is-前缀访问器即使显式调用也不解析（成员不存在，非语法坑）。
2. **文件 document 的 `setText` 在 BGT 硬抛 EDT-only**：非 FREE_THREADED document 的 setText 包 `CommandProcessor.executeCommand`（`DocumentImpl.myAssertThreading=true`，仅 `AbstractFileViewProvider.FREE_THREADED` 豁免），BGT 直调 RuntimeExceptionWithAttachments；连 WriteAction 持有也救不了。BGT 改文档唯一路线 = 派 EDT（invokeLater + 有界 latch，DialectPreMapping.ensureMapped 同款）。**此坑的历史代价**：DataSourceSelector BGT 预热的 document 分支曾每次必炸、runCatching 吞掉后静默掉进裸写兜底——正是用户真机 File Cache Conflict 弹窗链本体。修复架构见 [[project-m56-agent-bytecode]] 无关，见计划文档；同族线程归位先例 = [[project-architecture]] 中 DialectPreMapping。
