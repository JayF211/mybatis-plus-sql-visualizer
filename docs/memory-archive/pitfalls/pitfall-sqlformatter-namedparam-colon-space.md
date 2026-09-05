---
name: pitfall-sqlformatter-namedparam-colon-space
description: SqlFormatter 会把命名参数冒号当运算符空格化为“: name”，按名回填正则须容忍冒号后可选空白 [:]\s*
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_2b694b14-73b8-4570-ae2e-f9e6fb449455
---

# 命名参数冒号被 SqlFormatter 空格化（JdbcTemplate 预览）

**现象**（用户实测截图）：预览 SQL 填右侧参数后**右侧 SQL 不回填**——OCR 显示右侧 SQL 是 `AND username LIKE : username`、`AND status = : status`。

**根因**：SQL 预览文本经 `SqlFormatter.format()` 后，命名参数 `:name` 的冒号被当作**运算符**空格化为 `: name`（冒号后多一个空格）。而 `ParameterReplacer.replaceNamedParams` 的正则 `(?<![::])[:]([A-Za-z_]...)` **要求冒号后紧邻字母**，遇到 `: `（冒号+空格）整个匹配失败 → 填参数值不替换、SQL 不更新。

**修复**（提交 50eddbc，feature/jdbc-template-sql-preview）：`ParameterReplacer.NAMED_PARAM_PATTERN` 冒号后容忍可选空白 `(?<![::])[:]\s*([A-Za-z_][A-Za-z0-9_]*)`，同时覆盖 `:name` 与 `: name`；双冒号守卫（`1 :: integer`）保持不误标。新增 3 个空格化冒号回归用例。

**Why**：SQL 格式化器会把 `:` 当作二元运算符在两侧加空白；命名参数解析必须先格式化后的文本也能命中，正则不能假设冒号紧邻字母。

**How to apply**：凡是"格式化后文本 + 命名参数按名替换"的场景，命名参数正则务必容忍冒号后空白。相关：[[project-jdbctemplate-sql-string-preview]]、[[pitfall-sqlformatter-atlinestart-space]]（同为 SqlFormatter 空格语义缺陷）。