---
name: reference-sql-quick-templates-actions
description: 「SQL 快捷模板」三动作（EXPLAIN / COUNT / Sample 10 Rows）的定位、执行链路与代码层局限，以及「控制台结果在哪显示」这一未证实的观察点
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_b5cde80a-ac48-46e7-884d-8f11955f33d5
---

2026-09-21 用户连问「Sample 10 Rows 是干什么的」。三个动作都在 `actions/SelectedTableSqlTemplateActions.kt`（抽象基类 + 三子类），右键菜单入口是 `plugin.xml:381-410` 的 `SqlTemplateGroup`（挂 `EditorPopupMenu`，与「生成 CRUD SQL」入口并存不合并）。

- **语义**：把编辑器里**选中的文本** trim 后当表名代入模板，送原生 Database 控制台执行。Sample 10 Rows = `SELECT * FROM <选中> LIMIT 10`；EXPLAIN = `EXPLAIN SELECT * FROM <选中>`；COUNT = `SELECT COUNT(*) FROM <选中>`。无选中即禁用（`update` 里 `selectedText(e) != null`），三产物皆查询类故免 DML 二次确认。
- **执行链**：`SqlExecutor.execute(project, sql, null, isQuery=true, onError=…)` → `DataSourceSelector`：多数据源弹选择框（单数据源可自动选中；命中共享控制台且数据源相同则复用不弹，`DataSourceSelector.kt:294-297`/`:340-375`）→ 建临时 PSI 文件 → `attachConsole` 挂原生 `JdbcConsole` → `executeQueries` → 结果落原生控制台结果网格。Community / 无 database 插件时给「未检测到 Database 插件」提示。**「静默复用上次数据源」只对准备路径生效**（`if (!executeAfterPrepare)`），显式执行不走 → 多数据源项目第一次按下去必弹选择框。
- **代码层局限**（回答「这功能好不好用」时可直接引用）：`LIMIT 10` 硬编码、未走仓库既有的 `Dialect.renderLimit`（`sql/Dialect.kt:10`，Oracle 用 FETCH FIRST）→ 非 MySQL 方言会被数据库直接拒绝；无 `ORDER BY` → 取的是返回顺序不是「最新 10 行」；`SELECT *` 大宽表会拉大字段；选中文本不做标识符校验或引用，选错片段要到执行那步才报错。
- **未证实、建议真机点一次确认**：这三个动作（以及 log 转 SQL 的「执行」`LogToSqlPanel.kt:275`）调 `SqlExecutor` 时都**不传 UI 宿主**（`onConsole=null`）。插件侧只建控制台并执行，代码里没有任何「把控制台显示出来」的动作——显示只发生在宿主回调 `onConsole?.invoke`（`:1731`）或预览面板正托管这条共享控制台时。平台侧 `JdbcConsoleProvider` 常量池里无 `FileEditorManager`/`openFile`（即它自己不开编辑器），但不能据此断言平台别处不会。**若当时没有预览面板托管，结果网格到底出现在哪里，代码上定不下来**。
- **无真机走查记录**：T-PreviewUx-Pack 计划里写着「真机走查留主会话统一执行（暗色主题横幅视觉 / XML 编辑器选中表名三动作 / 多语句日志还原勾选流）」，验收文档里找不到对应产物；`build/*.log` 里也搜不到 `LIMIT 10` / `EXPLAIN SELECT * FROM` 痕迹。

相关：[[pitfall-keymap-conflicts-never-resolve]]（这三动作的默认键位冲突）、[[project-architecture]]（Action 清单与启动活动）、[[reference-real-ide-debug-entry]]（真机取证入口）。
