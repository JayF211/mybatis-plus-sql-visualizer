---
name: t-previewslow-delivery
description: 双击日志预览弹窗加载卡顿根治（T-PreviewSlow，2026-09-10 已实施未提交，全量
  3254/0/0/6）——两个根因：awaitPrewarmedTempFile 在 EDT 上 Thread.sleep 自旋烧 3s +
  prepareInConsole 用 ReadAction 包 attachConsole 触发 WIRA-in-ReadAction
  硬抛致原生控制台从未挂载
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d6f248c9-c63b-40a5-aed9-d8a232e60dba
---

双击 SQL 日志预览弹窗「加载时间较长」——2026-09-10 立项并实施（工作树未提交），全量 3254/0/0/6 绿，真机走查留人工。计划文档 `docs/开发计划/20260910-2215-T-PreviewSlow-预览弹窗加载卡顿根因分析与修复-开发计划.md`。

**两个独立根因**（真机 idea.log 时间轴钉死）：

1. **耗时主因**：`SqlPreviewPanel.awaitPrewarmedTempFile` 在 EDT 上 `Thread.sleep(20ms)` 自旋轮询预热线程产物（预热线程做同步 VFS refresh + PsiManager.findFile，冷态几百 ms~数 s），把「后台预热」用「前台死等」兑现——双击→createEditor 起点达 **3.07s**、→临时文件降级又 2.06s。修复 F1=`PREWARM_WAIT_MS` 2000→60ms：热态/二次打开立即命中走 PSI 主路径，冷启动首拍直接降级纯内存编辑器不烧 EDT，预热后下次自愈。

2. **伴随缺陷**：`DataSourceSelector.prepareInConsole` 用 `ReadAction.compute` 包 attachConsole，但 DBN 底层 `new EditorImpl` 构造里 `setHighlighter` 走 `WriteIntentReadAction`，平台锁层明文禁止「read-action 内再开 write-intent-read-action」（真机 Caused by `IllegalStateException: WriteIntentReadAction can not be called from ReadAction` ← EditorImpl.setHighlighter:1832 ← ConsoleExecutionEditor.<init>），被 `runCatching` 吞成 `prepareInConsole FAILED: InvocationTargetException: null` → **原生 DBN 控制台从未挂载成功、每次静默回退自研编辑器**。修复 F2=去掉 ReadAction 包裹还原直接插桩（attachConsole 是平台第三方链入口，自带读权限与 invokeLaterIfNeeded 切 EDT 语义），`console.getDocument` 同去；若真机复刷读权限 SEVERE 再按需对纯读段单独包 ReadAction。

**关键教训**：这个 WIRA-in-ReadAction 是 [[t-previewdoclock2-delivery]] 的 F1 修复**引入**的新缺陷——当时靠「读权限 SEVERE 零增量」验收，连 `write-intent-read-action` 这类**另一类锁冲突**也被 grep 漏掉（`InvocationTargetException: null` 不带「Read access」关键词），且 attachConsole 只在 262 + Database 插件真链跑，243 沙盒测不出。印证「平台加固型缺陷是 243 沙盒固有盲区，判定只能靠 262 反编译 + 真机日志」。关联 [[t-previewdoclock-delivery]]、[[t-execcopyfix-plan]]（同类 runCatching 吞异常前例）。