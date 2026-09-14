---
name: t-previewlistreload-plan
description: 预览点击致SQL日志列表重载根因链与重放守卫方案——平台全文档重跑filter反编译实锤（2026-09-08，计划定稿未实施）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_88b97a19-15aa-49cd-b269-f14fb48fcbce
---

**T-PreviewListReload（2026-09-08 立项，计划定稿未实施；2026-09-09 已被 T-PreviewFlash 接续收编）**：用户反馈「点击任何地方的预览 sql，侧边栏 sql 列表都会重新加载」。计划文件 `docs/开发计划/20260908-1355-T-PreviewListReload-预览点击致SQL日志列表重载-开发计划.md`。**2026-09-09 更新**：沙盒 idea.log 实证了触发源（每次预览点击制造独立 dumb 窗口，`Push all on SqlDialectPusher` + `Push on VFS changes`），新计划 `20260909-1328-T-PreviewFlash-预览点击侧边栏闪烁-开发计划.md` 收编本方案并新增同链缺陷（EDT 慢操作 SEVERE 风暴、方言映射每击重设），实施随新计划推进。

**根因三层**：
1. 列表唯一数据通道 = 控制台 filter 回调（SqlLogConsoleBridge.kt:317-319 是 buffer.addEntry 全仓唯一调用点）——列表重载 ⇔ filter 被对历史行重新调了一轮。
2. 平台契约（ideaIU-2024.3.7.1 app-client.jar 反编译实锤）：`ConsoleViewImpl.rehighlightHyperlinksAndFoldings()` 从第 0 行全文档重跑全部 message filter；触发点含 exitDumbMode（dumb 窗口内控制台文档变更）、配色方案变更、插件动态加载/卸载；`flushDeferredText` 的 startLine 在 RangeMarker 失效时也回退 0。**filter 不保证只跑一次——插件必须幂等，当前不幂等即根因。**
3. 触发源推断：M36.5 起每次预览无条件走 prepareDefaultConsole → mpsql-exec.sql 覆盖写+VFS refresh+SqlDialectMappings.setMapping/reparse → 索引/dumb 窗口 → exitDumbMode 分支命中。

**症状机制**：重放产出新 LogSqlEntry 实例追加尾部 → 60s 去重窗口外历史行全部重新入库（列表成倍增长）；窗口内重复走 dedupeCounts.merge（⨉N 徽章膨胀）；排序激活时 diff 判 FullRebuild 全量重建（滚动/选区丢失）；T-JdbcMultiline pendingSource 状态机被重放污染。

**修复方案**：filter 层重放守卫——`applyFilter(line, entireLength)` 的 entireLength 反编译确认为「行在整篇文档中的行尾偏移」（AsyncFilterRunner$HighlighterJob.analyzeLine），正常流式单调递增、重放从 0 起步必远小于水位线。新增 ConsoleReplayGuard 纯逻辑类（watermark + docStamp + 行 0 文本四态判定：流式处理/重放丢弃/重置/混合态），LogFilterProxyHandler 透传 args[1]，BridgeLineContext 挂状态，attachFilter 反射缓存 console.getEditor()（取不到则守卫降级=现状语义）。可疑分支才读文档，流式热路径零开销。

**验证锚点**：守卫重放丢弃计数 debug 日志——沙盒点击预览后 idea.log 出现计数即同时闭环触发源推断；基线 3112 绿。

相关：[[reference-fernflower-decompile]]、[[t-jdbcmultiline-plan]]、[[t-loglistfreeze04-delivery]]
