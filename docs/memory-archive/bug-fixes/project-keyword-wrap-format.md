---
name: project-keyword-wrap-format
description: 关键字换行格式化——预览面板按钮（4d2703d）+ 延伸为 SQL 日志/历史列表三种显示模式（T-ListMode，dev bbcc300 已交付）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_18a84d55-1260-420b-904c-70487acbce87
---

**需求（2026-08-21）**：SQL 预览面板右上角工具栏新增一个格式化按钮，行为是「关键字换行」模式——仅在主子句关键字（SELECT/FROM/WHERE/ORDER BY 等）处换行，列清单与 WHERE 条件等内容压缩到同行。

与现有功能区分：
- 现有「格式化」= 全展开（列换行、AND/OR 缩进）
- 现有「单行/多行」切换 = 全部挤一行 / 原始多行
- 新按钮 = **第三种模式**：子句顶格换行 + 同行内容压一行

示例（格式化后）：
```sql
SELECT id,password,age,email,role_id,dept_id,status,username,create_time,update_time,deleted,version
FROM `user`
WHERE username = ?  AND status = ?  AND age >= ?  AND deleted = 0
ORDER BY id DESC
```

**实现方案**：
1. `SqlFormatter.kt` 新增 `formatClauseLine()`：复用 `lex()` 分词，只在 `NEW_LINE_KEYWORDS` 处换行，其余内容同行逗号/空格连接
2. `SqlPreviewPanel.kt` 工具栏 `toolbar()` 方法加按钮（Format 右侧），调用新方法
3. Bundle 双语言加 `panel.keywordWrap` + tooltip 键

**按钮命名**：选定 **Keyword Wrap / 关键字换行**（方案 A）——与「格式化」语义互补。

**代码位置**：
- 工具栏：`SqlPreviewPanel.kt` — `add(smallButton(MpSqlVisualizerBundle.message("panel.keywordWrap")) { formatClauseLine() })`
- 格式化器：`SqlFormatter.kt` — `fun formatClauseLine(sql: String): String`
- 面板方法：`SqlPreviewPanel.kt` — `private fun formatClauseLine()`
- i18n：`panel.keywordWrap` + `panel.keywordWrap.tooltip`（中英文）
- 测试：`SqlFormatterTest.kt` — 11 条 `testClauseLine*` 用例

**实现要点**：
- 复用 `SqlLexer` 分词器（`lex()`）
- 维护 `depth` 计数器追踪括号嵌套——括号内不换行（子查询同行）
- AND/OR 前导双空格（手动追加两个空格，不能依赖 `flushSpace()` 的单空格）
- SELECT/SET 列清单逗号无空格；其余顶层逗号+空格；括号内逗号无空格（`pendingComma = ... || depth > 0`）
- 右括号不调用 `flushSpace()`（避免 `1 )` 前多余空格），改为 `pendingSpace = false`
- 括号内 NEW_LINE_KEYWORD 不换行但调用 `flushSpace()` 保留前导空格

**迭代踩坑**（4 轮修复）：
1. AND/OR 双空格：`flushSpace()` 只追加单空格 → 改为手动 `append(' '); append(' ')`
2. 子查询展开：括号内 SELECT/FROM/WHERE 仍触发换行 → 增加 `depth` 计数器
3. 括号内关键字缺前导空格：`pendingSpace = false` 吃掉空格 → depth>0 分支改用 `flushSpace()`
4. 括号内逗号有空格：`pendingComma` 条件扩展为 `depth > 0`
5. 右括号前多余空格：`flushSpace()` 在 `)` 前加空格 → 改为 `pendingSpace = false`

**提交**：`4d2703d` fix: XML/MP/MPJ IN 子句参数标记 inList=true（并行会话将本功能与其 IN-clause 工作一并提交）。

状态：✅ 已实施、已测试、已提交。详见 [[pitfall-sql-formatter-clause-line]]。

**2026-09-08 延伸（T-ListMode，✅ 已交付 dev bbcc300）**：`formatClauseLine` 与 `format` 已作为 SQL 日志/SQL 历史列表「三种显示模式」（单行/关键字换行/格式化，默认关键字换行）的变换器复用——枚举 `SqlListDisplayMode` + `prepareSqlForList` 在 `SqlListHtmlRenderer.kt`，模式存 `PluginSettings.logListSqlDisplayMode`，两列表工具栏下拉广播 SETTINGS_CHANGE_TOPIC 统一清缓存刷新；计划文档 docs/开发计划/20260908-1135，全量 3558/0/6 绿。关键平台陷阱：Swing BasicHTML 折叠连续空格，缩进须 `&nbsp;` 保真（[[pitfall-swing-basichtml-whitespace-collapse]]）；State 加字段触发的旧签名 NoSuchMethodError 见 [[pitfall-kotlin-dataclass-field-add-stale-copy]]。
