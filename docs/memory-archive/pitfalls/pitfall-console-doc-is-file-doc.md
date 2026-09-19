---
name: pitfall-console-doc-is-file-doc
description: 预览控制台/编辑器文档就是 exec 文件（mpsql-exec.sql）的文档——任何写这个文件的内容都会直接改掉用户眼前的编辑器文本
metadata:
  node_type: memory
  type: project
  originSessionId: sess_598c1639-3398-416c-ac31-3c129bbc5bca
---

预览链里 `attachConsole(project, session, vf)` 把 Database 控制台挂在 **exec 文件本体**上：控制台编辑器文档 = `<project>/.idea/sql-visualizer/exec/mpsql-exec.sql` 的 document（真机日志实证 `editor=EditorImpl[file://.../exec/mpsql-exec.sql]`）。所以「写这个文件」≡「改用户眼前的控制台文本」，这一条同时解释了两类反复出现的 bug：

- **File Cache Conflict（修了五轮）**：任何绕过 document 的写盘（裸写兜底）都会在文件文档被标脏时命中 MemoryDiskConflictResolver 弹窗；终局策略 = **VFS 已有该文件即绝不裸写**（T-ExecDocSync3，裸写仅留首次创建）。
- **预览控制台显示裸单行（T-PreviewConsoleFmt）**：预览链预热按 T-ExecSlowOps 既定设计把 `PreviewResult.sql` **原文**写进该文件，而面板渲染的是格式化文本（只落在面板自建编辑器里）——挂载后不重渲染，用户看到的就是文件里的裸 SQL。

**How to apply**：
- 改任何「写 exec 文件 / 写控制台文档」的代码前先问一句：这份文本与面板当前渲染文本是否一致？不一致就是显示回归。
- 控制台显示以**面板渲染文本**为准：`SqlPreviewPanel.showSql` 的 nativeDoc 分支负责回填，但其前置条件是 `consoleAttachedToMe()`（= `embeddedConsole` 已进 `consoleHost`）——回填触发点必须排在 `embedConsole` **之后**（`prepareDefaultConsole` 的 `onConsole` 回调内），放 `onNativeEditorReady` 会走 recoverConsole 分支而失效（T-PreviewConsoleFmt 首版即栽在此，被新增用例当场抓住）。
- 预热是「内容刷新」的第二个写方：**该文件正被编辑器打开时须跳过刷新**，否则会把面板刚渲染的文本盖掉。判据用 `isExecDocOpenInEditor`（文档已加载 **且** `EditorFactory.getEditors(doc)` 非空）；T-PreviewConsoleFmt 首版用的 `isConsoleAttachedToVf`（依赖 `console.getVirtualFile() === vf`）实机**从未命中**（日志 `content refresh SKIPPED` 计数 0 / `refreshed` 10），T-PreviewFast P3 已修正。
- **这条命同时决定「库上下文」**：控制台挂在哪个文件、以及该文件在 `.idea/data_source_mapping.xml` 里被映射到哪个数据源，一并决定会话的当前库——映射与插件按数据源名选的 runner 可能错位，表现为跨库同名表引发的 `42S22/1054 Unknown column`。**已修（T-ConsoleDbContext）**：控制台就绪即把 `SearchPath` 对齐到「数据源 URL 里的库」（一致则 no-op，不覆盖用户手选），错误提示附 `[当前库/数据源/数据源默认库]`。见 [[pitfall-dbn-perfile-datasource-mapping]]。
- 性能面：写该文件的成本直接落在用户眼前（真机实测「点击预览 → 控制台 SQL 可见」曾固定 +2054ms = 一次 2000ms 的 EDT 派发有界等待），故写入路径必须有**差量快路径**（内容一致即不派发不落盘）与**程序性写入闸门**（不触发 1.5s 防抖自动落盘）——T-PreviewFast P1/P4。
- 相关：[[project-pending-plans]] 的 T-ExecDocSync2/3、T-PreviewConsoleFmt、T-PreviewFast 条目；[[pitfall-log-first-not-code-reasoning]]（三次都是先看真机日志才定性）。
