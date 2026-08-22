---
name: pitfall-sql-formatter-clause-line
description: SqlFormatter.formatClauseLine() 实现中的 5 个迭代陷阱——AND/OR 双空格、子查询 depth
  追踪、括号内前导空格、括号内逗号、右括号多余空格
metadata:
  node_type: memory
  type: project
  originSessionId: sess_18a84d55-1260-420b-904c-70487acbce87
---

**M-KeywordWrap（2026-08-21）**：SQL 预览面板「关键字换行」格式化按钮的实现迭代中暴露 5 个 SqlFormatter 分词器陷阱：

1. **AND/OR 双空格**：`flushSpace()` 只追加单空格（`pendingSpace` 标志位单次消费）→ 需手动 `append(' '); append(' ')` 追加双空格。
2. **子查询展开**：括号内 SELECT/FROM/WHERE 仍触发 `newline()` → 必须维护 `depth` 计数器，`depth > 0` 时跳过换行。
3. **括号内关键字缺前导空格**：NEW_LINE_KEYWORD 分支的 `pendingSpace = false` 吃掉了前导空格 → `depth > 0` 分支改用 `flushSpace()` 保留。
4. **括号内逗号有空格**：`pendingComma` 条件仅判断 `currentClause == "SELECT" || currentClause == "SET"` → 需扩展为 `|| depth > 0`（函数参数/IN 列表/子查询内逗号均应无空格）。
5. **右括号前多余空格**：`)` 分支调用 `flushSpace()` 在 `)` 前追加空格（`1 )`）→ 改为 `pendingSpace = false`。

**Why**: SqlFormatter 的 `pendingSpace` 单次消费模型无法表达「双空格」「括号内不换行」等复合语义，需额外状态（`depth`、手动追加）补充。

**How to apply**: 扩展 `format()/formatClauseLine()` 时，任何「需要控制空格数量」或「括号内特殊行为」的需求，均应评估是否引入 `depth` 计数器 + 手动空格追加，而非依赖 `flushSpace()` 默认行为。

详见 [[project-keyword-wrap-format]]。