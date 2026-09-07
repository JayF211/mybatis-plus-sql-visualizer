---
name: project-string-sql-method-anchor
description: T-DaoSql 自研DAO字符串SQL预览与方法定义锚点贯通（2026-09-06 交付）；已知边界=运行时构造SQL静态不可还原；runIde真机截图待补
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c28a5273-0669-4174-bea5-6daca1601c75
---

# T-DaoSql：自研 DAO 字符串 SQL 预览与方法定义锚点（2026-09-06 交付，master 已提交）

用户反馈 `beanCruder.selectList(ScheduleVO.class, sql拼接变量, "CUR_USER", userId)` 调用行无图标、`selectListPagination` 方法定义处快捷键失效。四处根因与修复（开发计划 `docs/开发计划/20260906-1030-*`）：

1. `JdbcTemplateMethodDetector` 新增 `DAO_STRING_SQL_METHODS`（selectList/selectOne/selectCount/selectPage/selectMaps/selectObjs/selectListPagination），统一入口 `isStringSqlMethodCandidate`。
2. `JdbcTemplateSqlLocator.resolveSqlArgument`：实参列表扫描取第一个字符串形态实参（兼容 `(Class, sql, ...)` 形态）；`parameterExpression` = SQL 实参后一位。`PreviewReceiverResolver.hasStringLikeArg`（含 `toString()` 形态）。
3. `JdbcTemplateSqlGutterLineMarkerProvider` 家族守卫改**排除制**（UNKNOWN 家族兜底；CUSTOM_DAO/HIBERNATE_TEMPLATE/JPA_SESSION 拒绝）；`WrapperAnchorDetector` 场景 D 对字符串 SQL 形态让位（守卫置于 `initializerHeadIsWrapper` 之后，O(1) 优先）。
4. 新增 `StringSqlMethodGutterLineMarkerProvider`（方法定义锚点：方法体内白名单调用 → 方法名行图标；daemon 限单方法体结构遍历 + 200K 文本护栏）；`JdbcTemplateSqlPreviewService.collectMethodCandidates` 单一来源；`ConvertToSqlAction.tryMethodBodyStringSqlFallback` 接在 JPA fallback 后、文件级注解候选前。

**已知边界**：SQL 经对象字段/方法返回值运行时构造（`query.getQuery()` 形态）静态不可还原——图标入口有、预览文本无；跨方法参数流追踪未做（后续里程碑候选）。

**待办**：runIde 真机截图验收未做；demo 仓库 `mybatis-plus-demo` 已补三形态示例（BeanCruder/BeanQuery/StringSqlDaoDemoServiceImpl）。

关联：[[project-architecture]]、[[project-m54-perf-redline-audit]]（daemon 性能红线口径）。
