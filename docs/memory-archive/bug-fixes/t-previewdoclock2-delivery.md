---
name: t-previewdoclock2-delivery
description: EDT 读权限断言全库排雷与原生控制台挂载修复（T-PreviewDocLock2，master 3cba9f6/8335dcd，2026-09-10）——真机日志业务帧归一化定位三个热点（createEditor/prepareInConsole 两处），FileTypeManager.getStdFileType 用 java ReadWriteLock 永远安全，JavaPsiFacade.findClass 是 assertReadAccess 硬抛易被 catch 吞
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd4866c9-e7fa-4972-b99e-6c7bd10c7719
---

**已交付**：master `3cba9f6`（fix）+ `8335dcd`（docs），全量 3252/0/0/6 绿（263 套件零回归）。计划文档 `docs/开发计划/20260910-2110-T-PreviewDocLock2-EDT读权限断言全库排雷与原生控制台挂载修复-开发计划.md`。上文 [[t-previewdoclock-delivery]]。

**方法**：真机 `%APPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`~`.idea.5.log` 全量「Read access from inside read-action only」块，用 python 取每块**第一个 `cn.song.*` 业务帧**归一化计数（不是 grep 计数——grep 会把相邻 INFO 日志误并成假阳性，idea.2.log 的 54 条 ReadAccess 里业务帧其实是 INFO 级 SqlCompletionContributor 日志）。实证三热点：`createEditor:1538`=32（已修）、`DataSourceSelector.prepareInConsole:1109`=16、同函数 `:1116`=4。**其余断言类型（write thread only/write action only/EDT only）用脚本核实插件帧零命中**——idea.2.log 的 33 条 EDTOnly 栈内无任何 cn.song 帧，纯平台噪音。

**根因（F1）**：`prepareDefaultConsole`（预览准备链，EDT）反射调 `JdbcConsoleProvider.attachConsole`，DBN 的 `MessageBusSession.attach` 用 `ModalityUiUtil.invokeLaterIfNeeded` 把视图初始化切回 EDT，内部 `LanguageConsoleImpl$Helper.getDocument(kt:406)` 与随后我们反射调的 `JdbcConsole.getDocument(Java:371)` 都走到 262 加了 `softAssertReadAccess` 的 `FileDocumentManager.getDocument` → 又刷 4 条/次。修复=两处反射各包 `ReadAction.compute`（纯读模型、无同步 refresh，refresh 已在 createTempPsiFile 由 WriteIntentReadAction 处理）。

**两条新反编译结论**（补充/修正前文）：
1. **`FileTypeManager.getStdFileType` 永远安全**：262 `FileTypeManagerImpl.getStdFileType` 用 `withReadLock = ConcurrencyUtil.withLock(myPendingInitializationLock.readLock())`，是 **java 并发 ReadWriteLock，不是平台 read-action**，全程无 softAssertReadAccess。所以前面 T-PreviewDocLock 修复里顺带担心的所有 `getStdFileType("SQL")` EDT 调用（SqlPreviewPanel/SqlInputEditor/LogToSqlPanel）都不需要动。
2. **`JavaPsiFacade.findClass` 是 `assertReadAccess` 硬抛**（不是软断言）：违约时抛 `RuntimeException` 而非记日志，极易被 `catch(Exception)` 吞成静默失败。所以「真机日志零命中」≠「安全」——`ProjectTypeDetector.detect`（设置页源码捕获自动注入）在 EDT 上裸调 findClass/AnnotatedElementsSearch/findDirectory，硬抛被它的 try/catch 吞掉，表现为注入静默失败。**已记 F2 后续项**（未修，涉及 F-44-7 功能线程模型）。

**排雷表**（62 处）：DataSourceSelector 五处 findFile 均在 ReadAction 内、四处 getDocument 在 WriteAction 内、WrapperLocator/SqlToJavaPanel/ShareDialog（getFile 族无断言）/NativeDatabaseDiagramSupport/DatabaseSchemaResolver/全系列 previewAsync（pooled+ReadAction）均安全。

**后续项**（记计划 §六/§八，未做）：F2=`SqlSourceInterceptorAutoInjector`/`ProjectTypeDetector` EDT PSI 探测线程模型（三选案：detect 包 ReadAction / 整 inject 走 previewAsync 后台线程）；F3=预览临时文件降级（原 T-PreviewDocLock §七 收编——`awaitPrewarmedTempFile` 超时/二次 createEditor 时 `IllegalStateException` 硬失败，改纯内存文档编辑器降级，伪码见计划 §四）。护栏三口诀见计划 §五。关联 [[pitfall-vfs-refresh-write-intent-lock]]。