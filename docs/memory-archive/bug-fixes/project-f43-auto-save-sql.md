---
name: project-f43-auto-save-sql
description: F-43 已完成（2026-08-25）——自动保存历史 SQL：onEvicted 回调 +
  SqlLogAutoSaver，自定义文件名模板 + 按日分文件 + 100MB 滚动
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e386e7f2-eac0-4476-bc8f-1442ea2f7dda
---

F-43 已完成（2026-08-25），提交 `0df8844`（功能）+ `1178e31`（跨平台修复），已合并到 master（merge commit `4ccc770`），2497+ 测试全绿。

**核心设计**：
- `SqlLogEntryBuffer` 新增 `onEvicted: ((LogSqlEntry) -> Unit)?` 回调——淘汰最早条目时触发，null 时零开销
- 新建 `SqlLogAutoSaver`——文件名模板解析（`$yyyy$` `$MM$` `$dd$` `$HH$` `$mm$` `$ss$`）+ 按日分文件 + 100MB 滚动
- `SqlLogManager.applySettings()` 装配/清除回调，`dispose()` 关闭文件句柄
- 设置面板新增：复选框 + 目录浏览 + 文件名模板 + 重置 + 实时预览标签
- 不纳入 SettingsIO 导入/导出

**文件规则**：
- 默认目录：`.idea/mybatis-plus-sql-visualizer/sql-history/`
- 默认模板：`sql-history-$yyyy-MM-dd$.sql`
- 滚动：单文件 ≥100MB → `.2.sql`、`.3.sql`（扩展名前插序号）
- F-44 后格式扩展为：`--- source: xxx \| time: xxx \| timestamp ---\nSQL\n`（仍兼容旧版 `-- timestamp` 格式）

**跨平台修复**：
- `SimpleDateFormat` 指定 `Locale.ENGLISH`（避免中文环境 AM/PM）
- 文件名中 `:` 自动替换为 `-`（Windows 不允许冒号）

**设置项（3 个新字段）**：
- `logAutoSaveEnabled: Boolean = false`（默认关，零回归）
- `logAutoSavePath: String = ""`（空=默认 .idea 路径）
- `logAutoSaveFileNamePattern: String = "sql-history-$yyyy-MM-dd$.sql"`

**Why**: 用户希望重要调试 SQL 超出上限时不被丢弃。按日分文件 + 大小滚动 + 可自定义文件名模板是用户明确的存储策略偏好。

**How to apply**: 实施已完成。F-44 在此基础上扩展了保存格式（附加源码位置和耗时），并通过 SqlLogFileReader 反解析。测试覆盖：onEvicted 回调触发、文件写入格式、模板解析、日期切换、100MB 滚动、目录切换、无效路径容错、dispose 释放。