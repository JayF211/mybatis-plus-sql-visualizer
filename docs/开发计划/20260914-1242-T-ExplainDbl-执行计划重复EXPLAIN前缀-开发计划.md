# T-ExplainDbl 执行计划重复 EXPLAIN 前缀 - 开发计划

## 背景与问题

用户真机反馈：SQL 预览窗口首次点击「执行计划」后，再次点击执行计划，提交 SQL 变为

```
EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...
```

MySQL 报 `[42000][1064] You have an error in your SQL syntax ... near 'EXPLAIN FORMAT=JSON SELECT'`，并伴随「SQL 已提交执行，但等待结果超时」提示。

## 根因

`SqlPreviewPanel.explainSql()` 每次点击都无条件调用 `dialect.renderExplainStatement(sql)` 加前缀。首次点击后执行链（`DataSourceSelector.reuseSharedConsole` 的 `editor.document.setText(sql)`）已把包装好的 EXPLAIN 语句写回原生控制台文档，`nativeEditorDoc` 随之指向该文档；二次点击读取的 SQL 文本已含 `EXPLAIN FORMAT=JSON` 前缀，再包一层即产生双前缀语法错误。

## 修复

- `Dialect.kt`：`Dialect` 接口新增默认方法 `renderExplainStatementIdempotent(sql)`——文本已以 `EXPLAIN` 关键字开头（忽略大小写 + 第 8 字符词边界校验，防 `EXPLAINING_TABLE` 类标识符误判）时去首尾空白/结尾分号后原样返回，否则委托 `renderExplainStatement`；配套顶层私有函数 `startsWithExplainKeyword`。
- `SqlPreviewPanel.explainSql()`：改调 `renderExplainStatementIdempotent`（trim 收进默认方法内，调用点原 `trim().trimEnd(';')` 移除）。

## 测试

`DialectExplainTest` 新增 5 例：已包装文本幂等、大小写/空白/分号容错、词边界不误判、未包装正常加前缀、不支持方言（Oracle）未包装降级 null 且已包装原样返回。worktree 分支全量 3980/0/0/6 绿（基线 3975+5）。

真机验证点：预览窗口连点两次「执行计划」，第二次提交 SQL 仍为单前缀 `EXPLAIN FORMAT=JSON SELECT ...`，正常出计划结果。
