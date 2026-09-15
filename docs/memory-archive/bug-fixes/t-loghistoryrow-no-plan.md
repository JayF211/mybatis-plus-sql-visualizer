---
name: t-loghistoryrow-no-plan
description: SQL历史列表行号已交付（master dee44ae，4020 绿）——文件内条目顺序号、parseContent 读取层注入 sequenceNumber、HistoryCellRenderer 复用 #N 片段
metadata:
  node_type: memory
  type: project
  originSessionId: sess_144fcf04-5442-4147-8a7d-9707c2e9de7f
---

**T-LogHistoryRowNo（2026-09-14 立项并交付，master dee44ae+e78b151+773d227，4020/0/0/6 绿）**：用户需求「sql历史中的sql列表也需要显示行号」。计划文件 `docs/开发计划/20260914-1455-T-LogHistoryRowNo-SQL历史列表行号-开发计划.md`（含 §实施记录）。

**核心决策**：
- 历史行号语义 = **文件内条目顺序号**（1 起），非会话内捕获序号——历史条目读回 `sequenceNumber` 恒 0、跨项目多次运行累积无「本次启动第几条」概念、历史面板无排序仅搜索过滤。
- 分配点：`SqlLogFileReader.parseContent` 返回前 `mapIndexed` 注入 `sequenceNumber = index+1`（防御已有序号不覆盖）——一处覆盖 readFromFile/readAll/后台 keepReload 全部读取路径，旧文件零迁移。
- 渲染：`HistoryCellRenderer` 元信息行最前 `#N ` HISTORY_META_GRAY 片段，`sequenceNumber>0` 才显示（与主列表 `LogEntryListCellRenderer` 守卫一致）。
- 不持久化序号（文件格式 `-- source|time|timestamp --` 不动）；一级文件列表不加行号；不加设置开关。

**实施新学（防复踩）**：
- 并行会话 T-IntrUpg 同改 `HistorySqlLogPanel.kt`（显示模式行高逻辑 updateUI），与本次渲染片段改动不同段无冲突——提交前确认对方已落（a7bb545）再干净提交。
- `readAll` 仅测试使用（生产只用 readFromFile），多文件序号各自独立 1..N 不跨文件连续——历史面板按单文件展示。

**关键区别（防踩）**：历史列表不能复刻「项目启动清零」语义——序号是静态文件内顺序，与主列表捕获序号是两个概念。

相关：[[t-logrownumber-plan]]、[[project-test-conventions]]
