---
name: t-twoline-list-meta-line-delivery
description: T-TwoLine SQL日志/SQL历史列表元信息换行已交付（dev e77b696，3547 绿）——「SQL 历史」tab 实为
  HistorySqlLogPanel（SqlHistoryPanel 死代码）、空元信息守卫、与 master 渲染器重写的多行方向分歧
metadata:
  node_type: memory
  type: project
  originSessionId: sess_641685b5-bf98-4d00-8194-42fd5f406bed
---

T-TwoLine 已交付（2026-09-08，dev：5d3de80 计划 + e77b696 实现 + 42f283b docs 同步；计划文档 docs/开发计划/20260908-0840）。SQL 日志与 SQL 历史列表条目改两行——元信息行在上（时间戳/耗时/来源；日志 tab 去重 ⨉N 徽章从 SQL 尾随位置随迁至元信息行尾），SQL 高亮行在下。全量 3547/0/6 绿；runIde 真机两 tab 核验待人工（[[runide-demo-verify-workflow]]）。

**渲染架构事实（易改错对象）**：
- 工具窗口的「SQL 历史」tab 由 `log/HistorySqlLogPanel.kt` 承接（一级 HistoryFileCellRenderer 文件列表 / 二级 HistoryCellRenderer SQL 列表）；`history/SqlHistoryPanel.kt` 是 T-Perf-24 标注的死代码（未注册任何工具窗口/弹窗）——勿误改。
- 日志 tab：`SqlLogPanel.LogEntryListCellRenderer.computeRowHtml`——四段元信息 trim 后 `joinToString(" ")`，非空时插 `<br>` 接 SQL；**空守卫**：logTimestampEnabled 关 + showDurationInLogList 关 + 无 sourceFile + 无徽章 → 不插 `<br>` 防空首行。
- 既有能力无需重造：两列表 `fixedCellHeight=-1` 可变行高、`entry.cachedListHtml` 惰性缓存、Ctrl+C 经 `SqlEntryList.itemToText` 复制纯 restoredSql、tooltip=restoredSql（显示层改造不碰复制链）。
- 既有渲染断言全是 contains 风格，插 `<br>` 天然兼容；LogListFreezeTest 只锁缓存机制不锁 HTML 内容。

**⚠️ 与 master T-LogListFreeze-04 渲染器重写的方向性分歧**：master 04 把 HTML 渲染器根治为 SimpleColoredComponent + 固定行高（恒单行，见 [[pitfall-swing-html-list-renderer]]，仅落 master 53ecd9d/190e80f）；dev 侧用户明确要求列表多行展示（本交付两行 + T-ListMode 三模式计划，见 [[project-keyword-wrap-format]]），仍走 HTML+缓存路线。未来 master→dev 合并此处会语义冲突——以用户多行诉求为准，可变行高 O(N) 布局成本为已知接受项（cachedListHtml 只省拼接不省解析的教训仍适用）。

**瞬时全量失败模式**：首次全量 test FAILED 但 XML 报告 0 失败用例、同命令重跑即绿——疑与并行会话同时段跑 gradle 相互干扰（另见 [[pitfall-regression-false-failure-flakes]]、[[parallel-session-index-commit-split]]）。
