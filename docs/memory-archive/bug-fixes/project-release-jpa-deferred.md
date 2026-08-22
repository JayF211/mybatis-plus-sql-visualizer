---
name: project-release-jpa-deferred
description: JPA/Hibernate 内容已从 v2026.8.20 排除，2026-08-21 随 v2026.8.21 成功发行（预览是核心亮点）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d962fe4d-7a72-4783-b189-1c7f8d634b00
---

**决策（2026-08-21 更新）**：JPA/Hibernate 内容已成功随 v2026.8.21 发行。原延期决策已完成。

**历史决策（2026-08-21）**：v2026.8.20 发行说明不包含 JPA/Hibernate 相关内容，留待 v2026.8.21 版本发行时写入。

**v2026.8.21 用户明确：JPA/Hibernate SQL 预览是本版最核心亮点**（非日志捕获）——用户两版反馈纠正主次："geng zhongyao de shi zhichi JPA/Hibernate yulan SQL"。发行说明/CHANGELOG/changeNotes 皆将预览排在日志捕获之前。

**v2026.8.21 已发行的 JPA/Hibernate 内容（预览优先）**：
- **JPA/Hibernate SQL 预览支持**（三种查询风格全覆盖，gutter 图标点击弹窗看 SQL）——Derived 派生查询（含嵌套对象 JOIN、Pageable LIMIT、重复列去重）/ CriteriaQuery 强类型（过滤 CriteriaBuilder 误标）/ HQL 自定义 DAO（参数名推断 + 类型感知引号 + JOIN FETCH）；@Query 路由优先 + 多行拼接提取
- Hibernate/JPA SQL 日志捕获支持（双解析器并列架构——MyBatis + Hibernate 同时解析互不干扰）
- JPA 嵌套对象 JOIN 查询支持（T0017）
- Pageable 生成分页 LIMIT + 重复列去重
- @Query 路由优先 + 拼接字符串提取
- JPQL IN 子句三层修复（括号补全 + 数值引号 + inList 逗号分割多值）
- JPA 测试体系三层加固（T0031）+ 端到端预览测试

**v2026.8.21 同步发行的非 JPA 内容**：
- SQL→Java 反向子查询 JOIN 解析 + MConsumer 形态 + ext 条件 lambda 化增强
- SQL 预览表名按短类名查找实体 + 复制时剥离注释
- MPJ 工厂链 selectAll 双图标回归修复
- ReadAction.compute 废弃 API 修复（T-M52 补充）

**v2026.8.21 发行文档最终状态**：
- `docs/发行说明/20260821-1700-HQL-JPA-SQL日志捕获支持-发行说明.md`（新建，预览为头条）
- `CHANGELOG.md`（顶部插入 [2026.8.21] Added/Fixed/Test，预览第一条）
- `build.gradle.kts` changeNotes（v2026.8.21 标题 + 5 条精简 li 标题，只保留标题不展开详细说明）
- `gradle.properties` version=2026.8.21
- `update/update.json` + `update/preview.update.json`（version/downloadUrl/publishDate/changeNotes 四字段同步升至 2026.8.21）

**build.gradle.kts changeNotes 风格约定**：用户明确要求「只保留标题」——每条 `<li>` 是精简标题（一行），不展开括号详细说明。与 v2026.8.19 / v2026.8.18 旧条目风格一致。

详见 [[project-hibernate-jpa-support]]、[[project-jpa-hibernate-fix-20260820]]。