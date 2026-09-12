---
name: t-previewflash-plan
description: 预览点击侧边栏闪烁根因链（2026-09-09 沙盒日志实证）与三路修复方案——重放守卫收编 + EDT 慢操作热点 + 方言映射短路（计划定稿未实施）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d8cd81c6-8fb0-4171-a828-30750cfc23c7
---

**T-PreviewFlash（2026-09-09 立项，计划定稿未实施）**：用户反馈「点击 sql 预览图标，插件侧边栏闪烁一次像刷新」。计划文件 `docs/开发计划/20260909-1328-T-PreviewFlash-预览点击侧边栏闪烁-开发计划.md`，接续收编 [[t-previewlistreload-plan]]（重放守卫方案原样并入并加状态标注）。

**沙盒 idea.log 实证（2026-09-09 12:54 会话，4 次点击 gutter 预览）**：
1. 每次点击制造一个独立 dumb 窗口（300~600ms），成对 dumb 任务 `Push all on SqlDialectPusher` + `Push on VFS changes`——20260908 计划的触发源推断自此有日志实锤。
2. 每次点击 `mapDialectBeforePsi`（DataSourceSelector.kt:2690）无条件 `setMapping` 重设方言——同值映射也触发 push all dumb 任务。
3. 每次点击 4~5 条 EDT 慢操作 SEVERE（18 条/会话，全 Plugin to blame: SQL Visualizer），两热点：SqlPreviewPanel.kt:1267 `PsiManager.findFile` 主路径（即使消费预热产物仍执行，预热线程新建临时文件的 VFS 事件弄脏 WorkspaceFileIndex，EDT findFile 撞脏索引即断言）×8/击 + DataSourceSelector.kt:2621-2622 `createTempPsiFile`（已包 `allowSlowOperations(GENERIC)` + ReadAction 仍触发——T-M36.6-03 的逃生舱对 `WorkspaceFileIndexDataImpl.ensureIsUpToDate`(:153) 断言无效，疑断言 section 非 GENERIC，待反编译定论）。

**三路修复**：① filter 层重放守卫（ConsoleReplayGuard 四态：watermark + docStamp + 行 0 文本）；② createEditor 消费预热 psi 不重复 findFile + createTempPsiFile 跨点击缓存 vf+psi（`vf.isValid()` 失效回退）；③ mapDialectBeforePsi 插件侧内存缓存短路（不读平台映射——`PerFileMappingsBase.getNotInHierarchy` 读映射本身就是同源慢操作）。

**验证锚点**：真机点击预览后 idea.log 出现重放丢弃计数 + 零新增 Slow operations SEVERE + `dialect pre-mapped` 仅首次。基线 3153/0/6（master 4340ade）。

**已实施（2026-09-09）**：三路修复全部落码，全量 3173 绿。反编译补充实现：`SlowOperations.startSection` 首行 `if(!EDT.isCurrentThreadEdt()) return EMPTY` 不压栈 + `isAlwaysAllowed()` 沙盒恒 false——故 EDT 上 allowSlowOperations(GENERIC) 是空壳，逃生舱只对非 EDT 线程生效，创建/refresh/findFile 必须移出 EDT（预热线程+有界等待+跨点击缓存），不能靠逃生舱压制。建设细节：ConsoleReplayGuard 四态 + BridgeLineContext 挂守卫（docStampReader!=null 才走守卫否则降级直连，测试桩无 getEditor 不误丢）；与并行会话 T-ExecCopyFix 共享 DataSourceSelector.kt（其冷路径后台化已吸收 cachedTempPsiFile 字段）。runIde 真机复验留人工。
