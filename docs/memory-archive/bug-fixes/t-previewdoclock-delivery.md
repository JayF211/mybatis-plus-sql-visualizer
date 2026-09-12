---
name: t-previewdoclock-delivery
description: 预览打开时 EDT 内文档解析踩 262 读权限硬断言（T-PreviewDocLock，master e8b9d1d/1f9143b，2026-09-10）—— 243 只有 @RequiresReadLock 注解、262 才加 softAssertReadAccess；此类"平台加固"缺陷是 243 测试沙盒的固有盲区，且 softAssert* 名字骗人（生产不抛但刷 SEVERE+FUS）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd4866c9-e7fa-4972-b99e-6c7bd10c7719
---

**已交付**：master `e8b9d1d`（fix）+ `1f9143b`（docs），全量 3252/0/0/6 绿（263 套件，与基线一致零回归）。计划文档 `docs/开发计划/20260910-1745-T-PreviewDocLock-预览打开EDT内文档解析读权限断言刷屏-开发计划.md`。

**用户报的堆栈**：`RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`，栈顶 `SqlPreviewPanel.createEditor(SqlPreviewPanel.kt:1538)`。真机 `%APPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`/`idea.1.log` 实证**每次双击开预览固定刷 4 条 SEVERE**、均带 `Plugin to blame: SQL Visualizer`。

**根因**：243 `FileDocumentManagerBase.getDocument(VirtualFile)` 只有 `@RequiresReadLock` **注解**（官方 `ideaIU-2024.3.7.1-sources.jar` 核实，243 连 `EditorLockFreeTyping` 类都没有）；262 同方法首行改成 `ThreadingAssertions.softAssertReadAccess()`，`FileDocumentManager.getDocument(vf, project)` 顶部再加一层 `EditorLockFreeTyping.assertReadAccess(file)`，而 `PsiDocumentManagerBase.getDocument(psiFile)` 正走这条链。EDT 鼠标事件无读权限 → 单次调用踩 4 条：vf→Document 解析 2 条（`PsiDocumentManagerBase:298` 两条分支）+ Document/PSI 文本长度校验 2 条（`:301` → `PsiFileImpl.getTextLength` → view provider content → `getDocument`）。暴露时点 = T-PreviewFlash（7953aa2）把 createTempFile/refresh/findFile 移到后台预热后，文档解析留在 EDT 裸调（改造前该位置落在 write-intent 区间的既有文档缓存里）。修复 = 该处包 `ReadAction.compute<Document, Throwable>`（同文件 T-M42 同族先例），注释写明 lambda 内勿塞同步 refresh 边界。

**两条可推广结论**（候补全局 pitfall）：
1. **`softAssert*` 名字骗人**：262 的 `softAssertReadAccess` → `Logger.error` → `IdeaLogger`，强度 = SEVERE + 版本/JDK/OS 头 + `Plugin to blame` + `reportToFus`；只有 `Logger.shouldRethrow`（仅 `ControlFlowException`/`CancellationException`）才 rethrow，所以**生产不抛**——功能照常、execution 继续，但日志刷屏 + FUS 归因到插件名，UsefulTestCase 模式则抛 `AssertionError`（平台一抬版测试就红）。判定"是不是真崩"要看 `IdeaLogger.shouldRethrow`，别被栈的传播深度误导。
2. **243 测试沙盒对"平台加固"型缺陷是固有盲区**：最低兼容版本 243 只注解不检查，同一份代码在 262 上才违约，`./gradlew test` 全绿毫无意义。判定依据只能是「262 反编译 + 真机 idea.log grep」——本次用 `python zipfile` 从本机 262 抽类 + IDEA 捆绑 JBR 跑 fernflower（沿用 [[reference-fernflower-decompile]]），再用 243 sources jar 做对照。凡做「EDT 上碰 PSI/Document/VFS」的改动，验收要写一条 `grep -c "c.i.u.ThreadingAssertions" idea.log` 零增量。

**排雷结论**（未改，留档）：`DataSourceSelector` 四处 `getDocument` 在 `WriteAction.compute` 内（写权限隐含读，安全）、`WrapperLocator` 三处调用方在 ReadAction 内、`SqlInputEditor.kt:69`（light PSI，下方本就有 `?: createDocument` 内存兜底）与 `SqlPreviewPanel:1654 commitDocument`（只断言 write-safe environment）真机日志零命中。真机日志未命中的路径不动——这是本仓「全库排雷」的收敛线。

**后续项**（记在计划 §七，未做）：`awaitPrewarmedTempFile` 产物一次性 + 超时 2s → `IllegalStateException("无法创建 SQL 预览临时文件")`，`createEditor` 二次调用（releaseEditor 后未 clear）同样打不开预览；建议改 `Future` + 降级为纯内存文档编辑器。关联 [[runide-demo-verify-workflow]]（262 真机/沙盒验收路线）。
