---
name: project-jdbctemplate-sql-string-preview
description: JdbcTemplate/NamedParameterJdbcTemplate SQL 字符串正向预览（2026-09-03 已完成，feature/jdbc-template-sql-preview 分支）——命名参数 :name 按名回填 + StringBuilder 分片聚合 + 独立 psource 包
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b694b14-73b8-4570-ae2e-f9e6fb449455
---

# JdbcTemplate SQL 字符串正向预览（已完成，feature/jdbc-template-sql-preview 分支）

**完成日期**：2026-09-03，工作树 `mybatis-plus-sql-visualizer-jdbc-preview`，master 分支 e83c549 切出。**编译 + 全量 2958 测试全绿**（含新增 psource 单测）。

**交付收尾**：`270b8f2` 三项修复（DbHelper 自研 DAO delete/insert 识别 + SQL 形态校验 + JPA gutter 误判修复），全量回归通过。

**需求**：插件原对 JdbcTemplate 只有**日志捕获**（M47 `JdbcTemplateLogParser`，v2026.9.2），缺**正向预览**——源码里 `queryForList`/`update` 等 SQL 字符串实参无水印、Alt+Shift+Q 不识别。用户两段真实形态：字面量 `+` 拼接（UNION ALL/CASE WHEN/`:CUR_USER`）+ StringBuilder 分段拼接（`querySelect`/`queryWhere` 条件组合）。

## 实施清单（已交付）

新包 `cn.song.mybatisplus.visualizer.psource`（零 IDE 依赖纯函数）：`NamedParamParser`（`:name` 收集去重保序 + 引号提示）、`JdbcTemplateMethodDetector`（方法名集合 O(1)）、`SqlStringConcatenator`（字面量 `+`/StringBuilder 两路）、`JdbcTemplateSqlLocator`（实参→SQL 调用信息）、`JdbcTemplateSqlPreviewService`（组装 PreviewResult）。gutter `JdbcTemplateSqlGutterLineMarkerProvider` 复刻 AnnotationSqlGutterLineMarkerProvider 74 行模式。`ParameterReplacer.replaceNamedParams` 按名回填 + `PreviewResult.namedParamHints: Map<String,ParamHint>`。`SqlPreviewService.preview` 最前加 JDBC 分支。设置 `jdbcSqlPreviewEnabled`（默认开）+ 中/英 i18n。5 个单测文件（NamedParamParserTest/JdbcTemplateMethodDetectorTest/ParameterReplacerNamedTest/SqlStringConcatenatorTest/JdbcTemplateSqlLocatorTest/JdbcTemplateSqlPreviewServiceTest）。

## 关键踩坑（实施中发现修复）

1. **链式 append 运行时值丢失**（`8461d2e` 修复）：`SqlStringConcatenator.collectAppendCalls` 原过滤只接受 qualifier 为 `PsiReferenceExpression` 的直接 `append`；链式 `sql.append("A").append(username).append("B")` 中外层 qualifier 是 `PsiMethodCallExpression`，被全部过滤——只收集到每段链首字符串字面量，运行时值整段丢失，前后字符串粘连。修复：加 `resolveRootVariable` 沿 qualifier 链回溯根变量 + 排序改用 `compareBy({ startOffset }, { textLength })`（链式多层共享同一 startOffset，按文本长度升序保证内层先于外层）。详见 [[pitfall-psource-chained-append-ordering]]
1b. **命名参数冒号被 SqlFormatter 空格化**（`50eddbc` 修复，用户实测）：SQL 经 `SqlFormatter.format()` 后 `:name` 冒号被当运算符空格化为 `: name`，`replaceNamedParams` 正则要求冒号紧邻字母 → 填参数右侧不回填。修复：正则改 `(?<![::])[:]\s*([A-Za-z_]...)` 容忍冒号后可选空白，双冒号守卫保持。详见 [[pitfall-sqlformatter-namedparam-colon-space]]
1c. **团队自研 DAO（DbHelper）delete/insert 识别 + 误报门**（`270b8f2`）：DbHelper 封装 NamedParameterJdbcTemplate，方法集与 JDBC_TEMPLATE_METHODS 对齐但**缺 delete/insert**（Spring JdbcTemplate 本身无这两个方法）→ 调用行无水印。修复①：方法集补 `delete`/`insert`；修复②：加 `isLikelySql` SQL 形态校验（以 select/insert/update/delete/with... 开头 或 含 from/into/set/where/join 等结构词才当 SQL）——避免 `list.insert("x")` 等通用字符串方法误触发（对齐 T-M20-08 防误伤，该既有测试在加入 insert 后立刻暴露回归）。**教训：给通用方法名（update/delete/insert/execute）加 SQL 预览必配 SQL 形态门，否则污染普通字符串方法。**
1d. **JPA gutter 误判普通类方法 → 图标错位 + "找不到 Wrapper"**（`270b8f2`，用户实测）：`deleteByDbHelper` 前缀 `delete` + 含 `By` 被 `DerivedMethodLocator.isDerivedQuery` 认作 JPA 派生查询，但该快速路径**不校验 containing class 是否 Repository 接口** → 普通类手写删除法在方法签名行画 JPA 图标；点击走 `previewDerivedQuery` 因非 Repository 返回 null → 提示"未找到 Wrapper"。修复：新增 `DerivedMethodLocator.isRepositoryMethod`（`isDerivedQuery && isRepositoryInterface(containingClass)`），`JpaGutterLineMarkerProvider` 改用它；不能直接删 `isDerivedQuery` 原语义——被 JpqlGutter（用它做排除）、ConvertToSqlAction 多处引用。**教训：JPA 派生查询 gutter 快速路径必须校验类为 Repository 接口，否则 `deleteBy`/`findBy` 手写方法全军误判。**
2. **`trimEndEnd()` 不存在**：Kotlin 标准库只有 `trimEnd()`，首版误写 `trimEndEnd()` 编译报错
3. **`companion object` 不能用于 standalone object**：`ParameterReplacer` 是 `object`，`companion object` 非法——正则常量直接作为私有属性
4. **`BasePlatformTestCase` 走 JUnit3**：测试方法必须以 `test` 开头，backtick 命名不被识别；`@Test` 注解无需
5. **PSI `findChildrenOfType` 返回 `Collection<T>` 不是 `List<T>`**：需 `toList()` 或声明为 `Collection`
6. **mock `PsiLocalVariable` 测试**：字段声明不在 `PsiCodeBlock` 内，`findChildrenOfType(PsiCodeBlock)` 返回空

**Why**：命名参数按名回填与 `?` 顺序回填两套语义，面板参数行 source 复用（命名参数名直接作 source）零侵入接入既有 paramValues/历史召回/JSON 导入；psource 纯函数化保证单测质量（呼应 [[project-test-conventions]]）。

**How to apply**：沙盒真机验证（runIde）待补——水印图标出现/命名参数回填/开关切换/与既有水印共存。相关既有知识：[[project-sqltogjava-classpicker-edt-psi-read]]（EDT 读 PSI 收口铁律）、[[feedback-grep-first-reading]]（大文件先 grep 再 Read）。