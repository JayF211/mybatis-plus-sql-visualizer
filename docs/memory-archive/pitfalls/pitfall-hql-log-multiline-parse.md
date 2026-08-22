---
name: pitfall-hql-log-multiline-parse
description: HQL 日志 multiline 解析全链路改造——状态机设计 + 双框架并发干扰 + Hibernate 前缀误收 bug
metadata:
  node_type: memory
  type: project
  originSessionId: sess_8267effc-eac3-40ba-87c1-e33e4ee16dd3
---

**HQL 日志未捕获修复（2026-08-22）**：Spring Boot 项目 Hibernate show-sql 输出 multiline SQL，插件 0 条捕获。经三阶段修复完成，2125 测试全绿。

## 根因（5 缺陷叠加）

Hibernate 输出 SQL 是 multiline 结构（HQL 注释跨 6 行 + 实际 SQL 跨 10+ 行），`HibernateLogParser` 按单行设计：

1. **`Hibernate:` 独占一行不识别**——`extractAfterKeyword` 空文本返回 null
2. **`/* ... */` 跨 6 行无法匹配**——`COMMENT_PREFIX_PATTERN` 正则要求同行闭合
3. **正则要求 `/*` 在行首**——`*/ select` 在行尾无法匹配
4. **SQL 续行无累积机制**——逐行独立判断
5. **参数行被忽略**——`pendingSql == null`

## 修复方案（已实施）

### 状态机改造（HibernateLogParser.kt）

三态：`IDLE` → `COLLECTING` → emit。新增 `collecting`、`inComment`、`multilineBuffer`。

关键方法：`feedIdle()` / `feedCollecting()` / `handleHibernatePrefix()` / `isPrefixOnly()` / `hasUnclosedComment()`。

### 双框架并发干扰修复

Maven 多模块同时跑 JPA + MyBatis-Plus 时，三干扰路径：

1. **原生 `Preparing:`/`Parameters:` 打入 Hibernate COLLECTING** → 污染 SQL buffer
   - 修复：`isMybatisLogLine()` 覆盖 MP 前缀（`==>`）+ 原生格式 + `isLogFrameworkLine()` 覆盖日志框架前缀
2. **日志框架前缀行（时间戳+级别+类名）打入 COLLECTING** → 被当续行
   - 修复：`isLogFrameworkLine()` 检测 `^\d{4}-...\s+(DEBUG|TRACE|...)` 模式
3. **Hibernate 行打入 MyBatis `prefixMatched`** → 被当 MyBatis SQL
   - 修复：`looksLikeHibernateLine()` 检测 `Hibernate:` / `/*` / `binding parameter`

### Hibernate 前缀误收 bug（`Hibernate: ` 出现在 COLLECTING 态）

**现象**：`WHERE userentity0_.username = ? Hibernate : ;`——`Hibernate:` 被追加到 SQL buffer。

**根因**：`feedCollecting` 中 `handleHibernatePrefix` 处理 `Hibernate:` 独占一行时，`extractAfterKeyword` 空文本返回 null → false。不命中任何守卫 → fall through 到续行追加。

**修复**：`feedCollecting` 中新增 `isPrefixOnly(line, sqlPrefix)` 检测，命中后闭合当前条目、开启新收集。

### 面板适配

- `SqlListHtmlRenderer.escapeHtml()` 增加 `\n` → `<br>`
- `LogSqlModels.summary` 智能截断（第一行末尾优先）
- `LogToSqlPanel` 零代码改动（multiline editor + 改造后 parser 自动兼容）

## 最终状态

- **2125 测试，0 失败**
- 改动文件：`HibernateLogParser.kt` + `MyBatisLogParser.kt` + `SqlListHtmlRenderer.kt` + `LogSqlModels.kt` + 测试文件
- 开发计划：`docs/开发计划/20260822-0100-HQL日志未捕获修复-Multiline-SQL解析器改造-开发计划.md`

**教训**：`feedCollecting` 的续行兜底分支必须严格——只接受"看起来像 SQL 续行"的文本，已知日志框架前缀/MyBatis 前缀/Hibernate 前缀全部要守卫。**参见**：[[pitfall-hql-jpa-preview-debugging]]