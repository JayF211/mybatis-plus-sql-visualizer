---
name: t-loglistfreeze04-delivery
description: T-LogListFreeze-04 日志列表 HTML 逐条解析 EDT 冻结 16 秒根治已交付（master
  9223f44/f8ab6ef，全量 3102 绿）；dev 合并冲突预判与 runIde 真机验证待人工
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f672b82-f6dc-4586-b0ec-4ba706c06266
---

T-LogListFreeze-04（2026-09-08 交付，master `9223f44` fix + `f8ab6ef` docs；计划文档 `docs/开发计划/20260908-0931-T-LogListFreeze04-*`）。用户真机 Freeze 报告（采样 11.4s 全落 BasicHTML.createHTMLView ← LogEntryListCellRenderer ← WideSelectionListUI.updateLayoutState），三层根因三波修复：

- **Wave1 弃 HTML 通道**：新增 `SqlListFragmentRenderer`（SqlLexer 分词→SqlTextFragment；配色体系/主题跟随不变，关键字 fontType 加粗首次在列表保留）；渲染器改双 SimpleColoredComponent 两行（T-TwoLine 元信息行在上/SQL 行在下）；fixedCellHeight 样例实测固定（fallback 字体行高×2+13）；`cachedListHtml` 字段/全清循环/取缓存分支整体退役（字符串缓存只省拼接不省 BasicHTML 解析，无效）。
- **Wave2 移植 dev 9388cdb 管线全套（同源代码）**：T-Perf-03 差分增量刷新（VisibleDiff 五分支/applyIncremental/applyFullRebuild 代际守卫/buffer.drainEvictedCount）+ T-Perf-19 计数键 sqlFingerprint 指纹化 + T-Perf-24 搜索 200ms 防抖 + T-Perf-15 dispose 精确注销（manager 新增 removeCapturingListener）+ T-Perf-06 去重判定入临界区防 CME + T-Perf-12 onEvicted 移出锁 + notifyUi 置脏合并（风暴期单幂等 flush）。T-Perf-19 的 HTML 选择性失效块**不移植**（片段无缓存）。
- **Wave3**：HistorySqlLogPanel 两渲染器同型片段化（文件列表 tooltip 保留——条目少低频）。

行为变更：列表条目恒定两行，不再按 SQL 内容折行；全文走单击预览/双击弹窗。全量 **3102/0/0/6 绿**（基线 3094+净增 8）。

**dev 合并冲突预判（master→dev 下轮同步）**：渲染器冲突按「master 有意取代 HTML 通道、T-TwoLine 已在片段渲染器等价保留」一次性解决；T-Perf-03 移植同源自然合流；T-Perf-19 失效块取 master 删除态；kotlin-to-java 锚点同步自动携带。

**边界保持**：isDuplicate 仍 restoredSql 原文精确相等——T-LogDedupeMpMark（去重空白差异 + MP 分页计数误标）与本修复正交，其修复空间未被占用（该任务由其会话推进）。

**待人工**：runIde 真机大批量日志验证（打开/刷新/滚动/双击无 Freeze、两行着色视觉），走 [[runide-demo-verify-workflow]]。平台层可复用结论提炼见 [[pitfall-swing-html-list-renderer]]。
