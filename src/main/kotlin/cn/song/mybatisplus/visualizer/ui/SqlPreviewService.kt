package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.sql.interceptor.DataPermissionPlaceholder
import cn.song.mybatisplus.visualizer.sql.interceptor.DynamicTableNameProcessor
import cn.song.mybatisplus.visualizer.sql.interceptor.TenantLineEnhancer
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryInfo
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.WrapperCache
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.TableConfigSignature
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * 职责：把编辑器中的任意 PSI 元素转换为可展示的 [PreviewResult]。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * M5 起读取 [PluginSettings] 配置：方言（分页子句）、格式化开关（多行/压缩）。
 * 轻服务（M16）：`@Service(Service.Level.PROJECT)` 注解声明，无需 plugin.xml `<projectService>` 注册
 * （IDE 自动发现；构造函数仅依赖 Project，满足轻服务条件），获取方式不变（project.getService）。
 */
@Service(Service.Level.PROJECT)
class SqlPreviewService(private val project: Project) {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private val cache = WrapperCache()

    // T-TenantLine 诊断日志：输出注入决策链路（开关值/列名/语句类型/跳过原因），
    // 供沙盒排查「开启租户但不拼接」问题（无日志 = 本方法未被调用）。

    /**
     * T-M19.4（用户实测：表名配置修改后预览不生效——缓存按文件戳失效，设置变更不触发）：
     * 影响解析结果的表相关设置签名，作为 [WrapperCache] 缓存维度。
     * 复用 [cn.song.mybatisplus.visualizer.util.TableConfigSignature.projectSignature] 统一来源，
     * 与 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 的解析缓存防漂移。
     *
     * O-5：项目级设置开启时追加项目级表配置签名——项目设置变更后缓存同样失效。
     */
    private fun tableConfigSignature(): String {
        val base = TableConfigSignature.projectSignature()
        val ps = projectSettings
        if (ps == null || !ps.isEnabled()) return base
        val s = ps.state
        // 项目级表配置（空 = 与全局一致，无需追加差异）
        val projectPart = listOf(
            s.tableNameInference.ifBlank { "" },
            s.ignoredFields.ifBlank { "" },
            s.tablePrefix.ifBlank { "" },
            s.tableSuffix.ifBlank { "" },
        ).joinToString("|")
        return if (projectPart.isBlank() || projectPart == "|||") base else "$base|proj:$projectPart"
    }

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    private val settings: PluginSettings? =
        runCatching { PluginSettings.getInstance() }.getOrNull()

    /**
     * O-5：项目级配置（project-level 轻服务；未启用时回退全局）。
     * 读取设置时优先查项目级（[ProjectPluginSettings]），开启时覆盖全局值。
     */
    private val projectSettings: ProjectPluginSettings? =
        runCatching { ProjectPluginSettings.getInstance(project) }.getOrNull()

    /**
     * O-5：获取有效的设置值（项目级优先，关闭/空值回退全局）。
     *
     * 项目级未启用或值为空/null 时使用全局 [PluginSettings] 值，保证单来源决策。
     */
    private fun <T> effective(global: () -> T, project: () -> T?): T {
        val ps = projectSettings
        return if (ps != null && ps.isEnabled()) {
            project() ?: global()
        } else {
            global()
        }
    }

    /**
     * O-5：获取有效的字符串设置值（项目级优先，空值回退全局）。
     * 专门用于 String 类型，避免泛型推断问题。
     */
    private fun effectiveString(global: String, project: String?): String {
        val ps = projectSettings
        return if (ps != null && ps.isEnabled() && !project.isNullOrBlank()) {
            project
        } else {
            global
        }
    }

    /**
     * 预览锚点元素处的 Wrapper 转换结果。
     *
     * 识别顺序：
     *  1. Wrapper 调用链（SELECT/UPDATE/DELETE/MPJ）
     *  2. 批量操作调用（F-15，`service.updateBatchById(list)` 等）
     *
     * @param anchor 光标处 / 悬停处的 PSI 元素
     * @return 预览结果；两者均未命中时返回 null
     */
    fun preview(anchor: PsiElement): PreviewResult? {
        // JDBC 模板 SQL 字符串预览（正向）：queryForList/update 等方法的字符串 SQL 实参 → 预览
        // 放在最前：JDBC 分支不写 Wrapper 缓存、不依赖实体解析，与既有路径完全隔离
        if (settings?.state?.jdbcSqlPreviewEnabled != false) {
            JdbcTemplateSqlLocator.locate(anchor)?.let { call ->
                return JdbcTemplateSqlPreviewService.preview(call, anchor)
            }
        }

        val file = anchor.containingFile ?: return null
        // 缓存键含锚点偏移：同文件多个 Wrapper（查询/删除/修改）各自独立缓存，避免串结果
        // M14.6：缓存键含「替换 ? 占位符」开关，开关切换后不命中旧结果
        // T-M19.4（用户实测：表名配置修改后预览不生效）：缓存键含**表配置签名**——设置变更
        // （文件戳不变的场景）不再命中旧解析结果
        val resolveVariables = settings?.state?.replaceParams ?: true
        val located = cache.getOrCompute(file, anchor.textRange.startOffset, resolveVariables, tableConfigSignature()) {
            WrapperLocator.locateWithTable(anchor, resolveVariables)
        }
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳失效，
        // 实体文件变化不触发）：命中缓存时校验实体文件戳（@TableName/@TableField 注解来源），
        // 变更则重算（重算结果由下一次 getOrCompute 写回缓存）
        val effectiveLocated = if (located != null && !located.entityFileValid()) {
            WrapperLocator.locateWithTable(anchor, resolveVariables)
        } else {
            located
        }

        // 1. Wrapper 转换（优先）
        effectiveLocated?.let { located ->
            val table = located.table ?: run {
                LOG.info("preview: RETURN_NULL table=null, wrapperType=${located.chain.wrapperType}, " +
                         "anchor=${anchor.textRange}")
                return null
            }
            val statement = when {
                // 计数消费：service.count(wrapper) / selectJoinCount(wrapper) / lambdaQuery()...count() → SELECT COUNT(*) FROM ...（保留联表与条件）
                located.chain.countConsumed && located.chain.wrapperType.isQueryLike() -> {
                    val base = parseQueryLike(located.chain, table)
                    SelectStatement(
                        table,
                        selectColumns = listOf(ColumnRef(columnName = "*", function = "COUNT")),
                        tableAlias = base.tableAlias,
                        joins = base.joins,
                        conditions = base.conditions,
                    )
                }
                // exists 链式终结点消费：service.lambdaQuery()...exists() → SELECT EXISTS (子查询)（Q14-③，T-M15-01）
                located.chain.existsConsumed && located.chain.wrapperType.isQueryLike() ->
                    parseQueryLike(located.chain, table)
                // 实体更新消费：service.update(entity, wrapper) 双参（U07-②，T-M15-04）——
                // wrapper 无 set 时用实体字段展开 SET 列（值静态不可求值 → 占位）；主键列排除（进 WHERE）
                // T-M21-08：UpdateJoin/DeleteJoin 类型的消费（`mapper.updateJoin(entity, JoinWrappers.update(...))`
                // / `updateJoinAndNull(entity, ...)`）不走此分支——联表实体 SET 列近似由
                // UpdateJoinConverter 处理（实体字段静态不可得 → 主表非主键全列 `t.col = ?` 占位），
                // 走下方 else → ConverterRegistry.convert
                located.chain.updateEntityConsumed &&
                    located.chain.wrapperType !in UPDATE_DELETE_JOIN_TYPES -> {
                    val parsed = QueryWrapperParser(table).parse(located.chain)
                    val sets = table.columns
                        .filter { !it.isId }
                        // T-M22-02：@Version 列排除——实体 SET 不含 version（对齐 MP
                        // OptimisticLockerInnerInterceptor：version 不进实体字段 SET，
                        // 由 [injectOptimisticLockCondition] 追加 `version = version + 1`）
                        .filter { it.columnName != table.versionColumn }
                        .map { SetClause(column = ColumnRef(columnName = it.columnName), value = SqlValue.Placeholder("?")) }
                    UpdateStatement(table, sets, parsed.conditions)
                }
                // MP 惯用删除写法：删除条件用 LambdaQueryWrapper 构造，`service.remove(wrapper)` / `mapper.delete(wrapper)` 消费；
                // 链式删除 `service.lambdaUpdate()...remove()`（U11-⑤，T-M15-01）同为删除。
                // T-M21-08：DeleteJoinWrapper 类型（`mapper.deleteJoin(JoinWrappers.delete(...))`）不走此分支——
                // 多表删除目标（delete(Class...)/deleteAll()）由 DeleteJoinConverter 处理（下方 else）
                located.chain.deleteConsumed &&
                    (located.chain.wrapperType.isQueryLike() || located.chain.wrapperType == WrapperType.LAMBDA_UPDATE) &&
                    located.chain.wrapperType !in UPDATE_DELETE_JOIN_TYPES ->
                    DeleteStatement(table, QueryWrapperParser(table).parse(located.chain).conditions)
                else -> ConverterRegistry.convert(located.chain, table) ?: return null
            }
            // T-M15-07：逻辑删除注入（@TableLogic 实体 + 配置开启）——查询/计数/exists/删除自动追加 `AND deleted = 0`
            // （对齐 MyBatis-Plus 全局 logic-delete-field 配置；UPDATE 不注入——更新可能修改 deleted 本身）
            // T-M20-05（P1-01 语义修正）：MPJ 六方法按源码顺序求值——主表/副表逻辑删除独立开关，
            // logicDelToOn/logicDelToWhere 仅控制**副表** deleted 条件位置（ON / WHERE 末尾），不再影响主表注入
            // （旧实现把二者当作全局开关是错误近似，见 [evaluateLogicDeleteState]）
            val logicDelete = evaluateLogicDeleteState(located.chain.calls)
            val baseStatement = if (settings?.state?.injectLogicDelete ?: true) {
                injectLogicDeleteCondition(table, statement, logicDelete)
            } else {
                statement
            }
            // T-M22-02：乐观锁注入（@Version 实体 UPDATE——SET version = version + 1 + WHERE 末尾
            // AND version = ?；不依赖配置开关，对齐 MP OptimisticLockerInnerInterceptor 默认行为；
            // 与逻辑删除注入串行调用——二者按语句类型互斥：逻辑删除仅 SELECT/DELETE、乐观锁仅 UPDATE）
            val finalStatement = injectOptimisticLockCondition(table, baseStatement)
            // T-TenantLine：多租户注入（TenantLineInnerInterceptor 模拟）——SELECT/UPDATE/DELETE 自动追加
            // `AND {tenantColumn} = ?`（语句已显式引用租户列时不重复注入）；@InterceptorIgnore(tenantLine=true)
            // 方法级注解命中时跳过（保守策略：检测失败→注入，宁可多不可少）
            val hasInterceptorIgnore = hasInterceptorIgnoreAnnotation(anchor, "tenantLine")
            val tenantWhereStatement = injectTenantLineCondition(table, finalStatement, hasInterceptorIgnore)
            // F-6 Phase A：UPDATE 追加租户列 SET（`tenantColumn = tenantDefault`）——对齐 MP 运行时行为
            val tenantStatement = if (settings?.state?.injectTenantLine == true && tenantWhereStatement is UpdateStatement) {
                val tenantColumn = settings.state.tenantColumnName.ifBlank { DEFAULT_TENANT_COLUMN }
                TenantLineEnhancer.enhanceUpdateSet(
                    tenantWhereStatement, tenantColumn, settings.state.tenantColumnDefault.ifBlank { "1" },
                )
            } else {
                tenantWhereStatement
            }
            // F-6 Phase B：动态表名替换（按用户配置的正则规则替换表名）
            val tableNameStatement = processDynamicTableName(table, tenantStatement)
            // F-6 Phase C：数据权限占位提示（追加警告 + 模拟条件）
            val dataPermissionStatement = injectDataPermissionCondition(table, tableNameStatement)
            // T-M29-02/03：分页消费（`service.page(Page, wrapper)` / `selectPage` / 链式 `page()`）→ LIMIT 子句
            // （覆盖 last("limit ...")——MP 运行时两者并存属错误用法，预览以分页为准，文档化边界；
            // count/exists 消费不追加分页）
            val pagedStatement = if (located.chain.countConsumed || located.chain.existsConsumed) {
                dataPermissionStatement
            } else {
                applyPageLimit(dataPermissionStatement, located.chain.pageLimit)
            }
            val result = buildPreviewResult(located.chain.wrapperType, table, pagedStatement)
            recordHistory(result, anchor)
            // exists 消费：外层包裹 SELECT EXISTS（对齐 MP 3.5.15 BaseMapper.exists 语义）
            return if (located.chain.existsConsumed && located.chain.wrapperType.isQueryLike()) {
                result.copy(
                    sql = "SELECT EXISTS (${result.sql})",
                    compactSql = "SELECT EXISTS (${result.compactSql})",
                )
            } else {
                result
            }
        }

        // 2. 批量操作识别（anchor 或其父链上的方法调用）
        val batchCall = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull()
        // T-M23-01/02：批量路径接线——逻辑删除注入开关（settings.injectLogicDelete）与变量求值开关
        // （settings.replaceParams，实参可静态求值时内联）对齐 Wrapper 路径；参数来源/回填提示透传面板
        val batch = batchCall?.let {
            BatchOperationConverter.convert(
                it,
                injectLogicDelete = settings?.state?.injectLogicDelete ?: true,
                resolveVariables = settings?.state?.replaceParams ?: true,
                injectTenantLine = settings?.state?.injectTenantLine ?: true,
            )
        } ?: return null
        val result = PreviewResult(
            sql = batch.sql,
            compactSql = batch.sql,
            unresolvedParams = batch.paramSources,
            paramTable = batch.paramGroups,
            wrapperType = null,
            tableName = batch.tableName,
            paramHints = batch.paramHints,
            batchModel = batch.batchModel,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * JPA 推导查询预览（F-14 阶段四）：解析 Repository 方法名 → PartTree → SqlStatement → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider] 点击调用。
     */
    fun previewDerivedQuery(method: PsiMethod): PreviewResult? {
        val methodInfo = DerivedMethodLocator.locate(method.containingFile ?: return null, method.textRange.startOffset)
            ?: return null
        val entityClass = methodInfo.entityClass ?: return null
        val table = JpaEntityResolver().resolve(entityClass) ?: return null
        val partTree = PartTreeParser.parse(methodInfo.methodName)
        var statement = DerivedQueryConverter.convert(partTree, table)
        // Pageable 参数 → 添加 LIMIT 占位符（运行时由 Spring Data JPA 注入实际分页值）
        val hasPageable = method.parameterList.parameters.any { param ->
            val typeName = param.type.canonicalText
            typeName == "org.springframework.data.domain.Pageable" || typeName.endsWith(".Pageable")
        }
        if (hasPageable) {
            statement = applyPageableLimit(statement, method)
        }
        // 使用实际方法参数名（而非通用的 param1/param2）
        // Pageable 方法：排除 Pageable 参数本身，添加 pageSize/pageOffset 占位
        val paramNames = if (hasPageable) {
            method.parameterList.parameters
                .filter { param -> param.type.canonicalText != "org.springframework.data.domain.Pageable" && !param.type.canonicalText.endsWith(".Pageable") }
                .map { it.name } + listOf("pageSize", "pageOffset")
        } else {
            method.parameterList.parameters.map { it.name }
        }
        // DerivedQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement)
        return buildJpaPreviewResult(statement, sql, table.tableName, paramNames, method)
    }

    /**
     * 如果方法含 Pageable 参数，为 SELECT 语句添加 LIMIT 占位符。
     * Spring Data JPA 在运行时根据 Pageable 注入实际分页值；静态分析阶段用 ? 占位。
     */
    private fun applyPageableLimit(statement: SqlStatement, method: PsiMethod): SqlStatement {
        val hasPageable = method.parameterList.parameters.any { param ->
            val typeName = param.type.canonicalText
            typeName == "org.springframework.data.domain.Pageable" ||
                typeName.endsWith(".Pageable")
        }
        if (!hasPageable) return statement
        // 只对 SELECT 语句添加 LIMIT（COUNT/EXISTS 不分页）
        return when (statement) {
            is SelectStatement -> statement.copy(
                limit = cn.song.mybatisplus.visualizer.model.LimitClause(
                    offset = cn.song.mybatisplus.visualizer.model.SqlValue.Placeholder("pageOffset"),
                    count = cn.song.mybatisplus.visualizer.model.SqlValue.Placeholder("pageSize"),
                ),
            )
            else -> statement
        }
    }

    /**
     * JPQL/@Query 预览（波次一）：解析 @Query 注解方法 → JPQL 字符串 → SQL → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpqlGutterLineMarkerProvider] 点击调用。
     */
    fun previewJpql(method: PsiMethod): PreviewResult? {
        val info = method.containingFile?.let { file ->
            JpqlQueryLocator.locate(file, method.textRange.startOffset)
        } ?: return null
        val jpql = info.jpql
        // 参数名提取：优先 @Param 注解值，否则用方法参数名
        val paramNames = extractJpqlParamNames(method)
        // 解析实体类 → TableInfo
        val entityClass = info.entityClass
        val table = if (entityClass != null) {
            JpaEntityResolver().resolve(entityClass)
        } else {
            null
        }
        // 原生 SQL：转换命名参数 :param → ? 后展示（保持可读性）
        if (info.isNative) {
            val convertedSql = convertNativeSqlParameters(jpql)
            // 检测 IN 子句参数（在命名参数替换前检测 :paramName）
            val inParams = JpqlConverter.findInClauseParams(jpql)
            val hints = if (inParams.isNotEmpty()) paramNames.map { ParamHint(quote = true, inList = it in inParams) } else emptyList()
            return PreviewResult(
                sql = convertedSql,
                compactSql = convertedSql,
                unresolvedParams = paramNames,
                paramTable = ParameterReplacer.buildParamTable(paramNames),
                paramHints = hints,
                wrapperType = null,
                tableName = table?.tableName ?: info.entityClass?.name?.let { JpaEntityResolver.inferDecoratedTableName(it) } ?: "",
                columns = table?.allColumnNames ?: emptyList(),
            )
        }
        // JPQL → JpqlConverter.convert → Result（转换后 SQL + 语句模型）
        // 关键：使用转换后的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
        // 若用 SqlGenerator 重新生成，条件树为空会丢失 WHERE 子句和 ? 占位符。
        val result = JpqlConverter.convert(jpql, table)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = method,
            inClauseParams = result.inClauseParams,
        )
    }

    /**
     * 将原生 SQL 中的命名参数 :paramName 转换为 ? 占位符。
     * 示例：`:keyword` → `?`，`CONCAT('%', :keyword, '%')` → `CONCAT('%', ?, '%')`
     */
    private fun convertNativeSqlParameters(nativeSql: String): String {
        return nativeSql.replace(Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)"), "?")
    }

    /**
     * 提取 JPQL/@Query 方法的参数名。
     * 优先 @Param 注解值（@Param("foo") → "foo"），无注解时用方法参数名。
     */
    private fun extractJpqlParamNames(method: PsiMethod): List<String> {
        val parameters = method.parameterList.parameters
        val paramAnnotationQName = "org.springframework.data.repository.query.Param"
        return parameters.map { param ->
            val annotation = param.modifierList?.findAnnotation(paramAnnotationQName)
                ?: param.modifierList?.annotations?.firstOrNull {
                    it.nameReferenceElement?.referenceName == "Param"
                }
            if (annotation != null) {
                val value = annotation.findAttributeValue("value") as? com.intellij.psi.PsiLiteralExpression
                value?.value as? String ?: param.name
            } else {
                param.name
            }
        }
    }

    /**
     * CriteriaQuery 预览（波次一）：定位 CriteriaQueryModel → CriteriaQueryConverter → SQL → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.CriteriaQueryGutterLineMarkerProvider] 点击调用。
     */
    fun previewCriteriaQuery(anchor: PsiElement): PreviewResult? {
        // 1. 用 CriteriaQueryLocator.locate(anchor) 获取 CriteriaQueryModel
        val model = CriteriaQueryLocator.locate(anchor) ?: return null
        // 2. CriteriaQueryConverter.convert(model) → SelectStatement
        val statement = CriteriaQueryConverter.convert(model)
        // 3. 参数名：从包含 CriteriaQuery 的宿主方法参数列表提取
        val paramNames = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java)
            ?.parameterList?.parameters?.map { it.name } ?: emptyList()
        // CriteriaQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement)
        val tableName = model.entityClassName?.let { JpaEntityResolver.inferDecoratedTableName(it) } ?: ""
        return buildJpaPreviewResult(statement, sql, tableName, paramNames, PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java))
    }

    /**
     * Hibernate HQL 预览（session.createQuery）。
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.HqlGutterLineMarkerProvider] 点击调用。
     */
    fun previewHql(anchor: PsiElement): PreviewResult? {
        val info = HqlQueryLocator.locate(anchor) ?: return null

        // 原生 SQL：不做实体→表名映射，仅做参数替换
        // 双重检测：isNativeQuery 标志 + 方法名 fallback（防止 IDE 环境中标志未正确设置）
        if (info.isNativeQuery || info.createQueryMethodName == "createNativeQuery") {
            return previewNativeSql(info)
        }

        // T-PreviewShortcut：createNamedQuery("name") 按名回查实体 @NamedQuery，解析真实 JPQL；
        // 跨文件 / 命名不规范解析不到时优雅降级为把名字当 HQL 处理（不抛、不挂起）。
        if (info.createQueryMethodName == "createNamedQuery") {
            val named = runCatching { NamedQueryLocator.findByQueryName(project, info.hql) }.getOrNull()
            if (named != null && named.query.isNotBlank()) {
                val entityClass = named.annotation?.let { PsiTreeUtil.getParentOfType(it, PsiClass::class.java, false) }
                val table = entityClass?.let { JpaEntityResolver().resolve(it) }
                val result = JpqlConverter.convert(named.query, table)
                val paramNames = extractParamNamesFromHql(named.query)
                return buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = result.sql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames,
                    method = info.method,
                    inClauseParams = result.inClauseParams,
                )
            }
        }

        val table = resolveHqlTable(info.entityClass, info.entityClassName, info.hql)
        val result = HqlConverter.convert(info.hql, table)
        // 参数名提取：
        // 1. 追踪 setParameter 结果（位置参数用 valueExpr 即变量名，命名参数用 paramName）
        // 2. 若为空（链式调用场景），直接从 HQL 提取
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        // 关键：使用 HqlConverter 转换后的 SQL 字符串（含 ? 占位符），而非从 statement 重新生成。
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = info.method,
            inClauseParams = result.inClauseParams,
        )
    }

    /**
     * 原生 SQL 预览（createNativeQuery）。
     * 不做实体→表名映射，仅替换 :param → ? 并解析实体表名。
     */
    private fun previewNativeSql(info: HqlQueryInfo): PreviewResult? {
        val sql = convertNativeSqlParameters(info.hql)
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        // 解析实体表名（用于显示）
        val tableName = resolveHqlEntityTable(info.entityClass) ?: info.entityClassName ?: ""
        return PreviewResult(
            sql = sql,
            compactSql = sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = paramNames,
            paramTable = ParameterReplacer.buildParamTable(paramNames),
            paramHints = paramNames.map { ParamHint(quote = !isNumericParam(info.method, it)) },
            wrapperType = null,
            tableName = tableName,
        )
    }

    /**
     * 解析原生 SQL 对应的实体表名。
     */
    private fun resolveHqlEntityTable(entityClass: PsiClass?): String? {
        if (entityClass == null) return null
        return JpaEntityResolver().resolve(entityClass).tableName
    }

    /**
     * 实体级 @NamedQuery/@NamedQueries 预览（T-PreviewShortcut）：
     * 从光标所在 @NamedQuery 注解反查实体类 → 定位对应命名查询 → JPQL 转 SQL。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.NamedQueryGutterLineMarkerProvider] 点击与快捷键调用。
     */
    fun previewNamedQuery(anchor: PsiElement): PreviewResult? {
        val annotation = PsiTreeUtil.getParentOfType(anchor, PsiAnnotation::class.java, false) ?: return null
        val cls = PsiTreeUtil.getParentOfType(annotation, PsiClass::class.java, false) ?: return null
        val infos = NamedQueryLocator.locate(cls)
        if (infos.isEmpty()) return null
        // 优先取光标所在那一条 @NamedQuery（注解引用匹配）；多选 fallback 取第一条
        val info = infos.firstOrNull { it.annotation === annotation } ?: infos.first()
        val jpql = info.query
        val table = JpaEntityResolver().resolve(cls)
        val result = JpqlConverter.convert(jpql, table)
        val paramNames = extractParamNamesFromHql(jpql)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = null,
            inClauseParams = result.inClauseParams,
        )
    }

    /**
     * 自定义 DAO HQL 预览（commonDao.findByQuery 等）。
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.CustomDaoHqlGutterLineMarkerProvider] 点击调用。
     */
    fun previewCustomDaoHql(anchor: PsiElement): PreviewResult? {
        val info = CustomDaoHqlLocator.locate(anchor) ?: return null
        val table = resolveHqlTable(null, info.entityClassName, info.hql)
        val result = HqlConverter.convert(info.hql, table)
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = result.sql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames,
            method = info.method,
            inClauseParams = result.inClauseParams,
        )
    }

    /**
     * 从 HQL 字符串中提取参数名。
     * 支持命名参数（:paramName）和位置参数（?1 → param1, ?2 → param2）。
     */
    private fun extractParamNamesFromHql(hql: String): List<String> {
        val namedParams = Regex("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)").findAll(hql)
            .map { it.groupValues[1] }.toList()
        val positionalParams = Regex("\\?(\\d+)").findAll(hql)
            .map { "param${it.groupValues[1]}" }.toList()
        return (namedParams + positionalParams).distinct()
    }

    /**
     * JPA 预览结果构建统一入口（派生查询 / JPQL / CriteriaQuery 共用）。
     *
     * 与 MP 路径的 [buildPreviewResult] 对齐：生成 SQL + 收集动态条件视图 + 构建参数对照表，
     * 使面板支持交互式参数输入与条件 toggle。
     */
    /**
     * JPA 预览结果构建统一入口。
     *
     * @param statement    语句模型（用于 previewModel / warning 判定）
     * @param convertedSql 转换后的 SQL 字符串（含 ? 占位符，直接用于面板显示和参数回填）
     * @param tableName    表名
     * @param paramNames   参数名列表（来自方法签名）
     * @param method       方法（用于提取实体列信息）
     */
    private fun buildJpaPreviewResult(
        statement: SqlStatement,
        convertedSql: String,
        tableName: String,
        paramNames: List<String>,
        method: PsiMethod?,
        inClauseParams: Set<String> = emptySet(),
    ): PreviewResult {
        val views = collectConditionViews(statement)
        // 参数回填提示：根据方法签名参数类型判定是否需要引号
        // - IN 子句参数：数值类型不加引号，字符串加引号
        // - 非 IN 参数：数值类型不加引号，字符串加引号（quote=true）
        // - 无法判定类型时默认加引号（保守策略）
        val paramHints = if (paramNames.isNotEmpty()) {
            paramNames.map { name ->
                val inList = name in inClauseParams
                val isNumeric = isNumericParam(method, name)
                // 数值类型不加引号，其他类型（String/Date 等）加引号
                ParamHint(quote = !isNumeric, inList = inList)
            }
        } else emptyList()
        return PreviewResult(
            sql = convertedSql,
            compactSql = convertedSql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = paramNames,
            paramTable = ParameterReplacer.buildParamTable(paramNames),
            paramHints = paramHints,
            wrapperType = null,
            tableName = tableName,
            warning = buildJpaWarning(statement),
            columns = (statement.table as? TableInfo)?.allColumnNames ?: emptyList(),
            previewModel = if (views.isEmpty()) null else SqlPreviewModel(statement, currentDialect(), formatSql(), views),
        )
    }

    /**
     * 判断方法参数是否为数值类型（Integer/Long/BigDecimal 等）。
     *
     * 用于 IN 子句参数回填时决定是否需要引号：数值类型不加引号，字符串加引号。
     * 支持 Collection<NumericType>、NumericType[] 等集合/数组形态。
     */
    private fun isNumericParam(method: PsiMethod?, paramName: String): Boolean {
        if (method == null) return false
        val param = method.parameterList.parameters.firstOrNull { it.name == paramName } ?: return false
        val type = param.type
        // 提取集合/数组元素类型
        val elementType = when {
            type is com.intellij.psi.PsiClassType -> {
                val className = type.canonicalText
                // 常见集合类型：Collection<X>, List<X>, Set<X>
                val genericPattern = Regex("""(?:Collection|List|Set)<(.+)>""")
                val match = genericPattern.find(className)
                if (match != null) match.groupValues[1] else className
            }
            type is com.intellij.psi.PsiArrayType -> type.componentType.canonicalText
            else -> type.canonicalText
        }
        return NUMERIC_TYPES.any { elementType.endsWith(it) }
    }

    private val NUMERIC_TYPES = setOf(
        "Integer", "int", "Long", "long", "Short", "short", "Byte", "byte",
        "Float", "float", "Double", "double", "BigDecimal", "BigInteger", "Number",
    )

    /** JPA 语句风险警示：DELETE 无 WHERE 时提示全表删除（BlockAttack 风格）。 */
    private fun buildJpaWarning(statement: SqlStatement): String? = when (statement) {
        is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" else null
        else -> null
    }

    /** 从 SQL 字符串提取 `?` 占位符参数名（简化：param1, param2, ...）。 */
    private fun extractPlaceholders(sql: String): List<String> {
        val count = sql.count { it == '?' }
        return (1..count).map { "param$it" }
    }

    /**
     * 由语句模型构建预览结果（应用方言与格式化配置）。
     */
    private fun buildPreviewResult(
        wrapperType: cn.song.mybatisplus.visualizer.model.WrapperType?,
        table: cn.song.mybatisplus.visualizer.model.TableInfo,
        statement: SqlStatement,
    ): PreviewResult {
        val dialect = currentDialect()
        val compact = !formatSql()
        val generated = SqlGenerator.generateWithParams(statement, compact = compact, dialect = dialect)
        // 动态条件视图（扁平化，含嵌套组内）；无动态条件时 previewModel 为 null → 面板走旧文本渲染路径
        val views = collectConditionViews(statement)
        return PreviewResult(
            sql = generated.sql,
            compactSql = SqlGenerator.generate(statement, compact = true, dialect = dialect),
            unresolvedParams = generated.unresolvedParams,
            paramTable = ParameterReplacer.buildParamTable(generated.unresolvedParams),
            wrapperType = wrapperType,
            tableName = table.tableName,
            warning = buildWarning(statement),
            columns = table.allColumnNames,
            // M16：列注释与表注释（补全 tailText / 关键词检索；来自 EntityResolver 的四级优先级提取）
            columnComments = table.columns.mapNotNull { col ->
                col.comment?.takeIf { it.isNotBlank() }?.let { col.columnName to it }
            }.toMap(),
            tableComment = table.comment,
            previewModel = if (views.isEmpty()) null else SqlPreviewModel(statement, dialect, formatSql(), views),
            paramHints = generated.paramHints,
            // F-2：SQL 性能分析（仅 SELECT）：委托 [SqlPerfAnalyzer]；UPDATE/DELETE 走既有 buildWarning 路径
            perfWarnings = analyzePerf(generated.sql, statement),
            // T-MpjFill：fill 附加查询子句（MPJ 1.5.1+）——透传至面板追加渲染
            fillClauses = (statement as? SelectStatement)?.fillClauses,
        )
    }

    /**
     * 性能分析（仅 SELECT）：委托 [SqlPerfAnalyzer]（F-2）。
     * UPDATE/DELETE 走既有 buildWarning 路径，不重复分析。
     */
    private fun analyzePerf(sql: String, statement: SqlStatement): List<PerfWarning>? {
        if (statement !is SelectStatement) return null
        val warnings = SqlPerfAnalyzer.analyze(sql)
        return warnings.ifEmpty { null }
    }

    /** 收集语句中的动态条件行（M12）：遍历 WHERE 条件树，扁平化 toggle 元数据（含嵌套 Group 内）。 */
    private fun collectConditionViews(statement: SqlStatement): List<ConditionView> = when (statement) {
        is SelectStatement -> statement.conditions.flatMap { it.toConditionViews() }
        is UpdateStatement -> statement.conditions.flatMap { it.toConditionViews() }
        is DeleteStatement -> statement.conditions.flatMap { it.toConditionViews() }
    }

    /**
     * 逻辑删除条件注入（T-M15-07 + T-M20-05 精确化）：实体含 @TableLogic 字段时，查询/删除自动追加 `deleted = 0`。
     * 仅 SELECT/DELETE（UPDATE 不注入——更新可能修改 deleted 本身，真实项目发票域显式 `.eq(getDeleted, 0)`）。
     *
     * 主表/副表注入按 [LogicDeleteState] 独立控制（对齐 MPJ 官方六方法语义）：
     *  - 主表：mainActive 时 WHERE 追加 `t.deleted = 0`（T-M23.10：MPJ 联表带主表别名 statement.tableAlias，
     *    单表 tableAlias=null 渲染裸列名，维持历史格式）；
     *  - 副表：对每个带 [JoinClause.logicDeleteColumn] 的 join，subActive 时——
     *    - subToWhere=false（默认 / `logicDelToOn`）→ ON 条件追加 `alias.deleted = 0`；
     *    - subToWhere=true（`logicDelToWhere`）→ WHERE 末尾追加带别名的 `alias.deleted = 0`（主表在前、副表在后）。
     *
     * 副表仅 SELECT 有 joins；DELETE 无 joins 维持主表注入（受 mainActive 控制）。
     */
    private fun injectLogicDeleteCondition(
        table: TableInfo,
        statement: SqlStatement,
        state: LogicDeleteState,
    ): SqlStatement {
        // T-M31.18：逻辑删除值可配置（默认 0，用户可调整——对齐 MP logic-delete-value）
        val logicDeleteValue = PluginSettings.getInstance().state.logicDeleteValue.ifBlank { "0" }
        return when (statement) {
            is SelectStatement -> {
                var conditions = statement.conditions
                if (state.mainActive) {
                    table.logicDeleteColumn?.let { column ->
                        conditions = conditions + Condition.Simple(
                            ColumnRef(tableAlias = statement.tableAlias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                        )
                    }
                }
                // 副表注入：ON 追加 / WHERE 末尾（子查询形态 tableName 以 '(' 开头无 logicDeleteColumn，天然跳过）
                val subWhere = mutableListOf<Condition>()
                val joins = statement.joins.map { join ->
                    val column = join.logicDeleteColumn ?: return@map join
                    if (!state.subActive) return@map join
                    val cond = Condition.Simple(
                        ColumnRef(tableAlias = join.alias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                    )
                    if (state.subToWhere) {
                        subWhere += cond
                        join
                    } else {
                        join.copy(onConditions = join.onConditions + cond)
                    }
                }
                if (subWhere.isNotEmpty()) conditions = conditions + subWhere
                statement.copy(conditions = conditions, joins = joins)
            }
            is DeleteStatement -> {
                val column = table.logicDeleteColumn ?: return statement
                if (!state.mainActive) return statement
                statement.copy(
                    conditions = statement.conditions + Condition.Simple(
                        ColumnRef(tableAlias = statement.tableAlias, columnName = column), "=", SqlValue.Literal(logicDeleteValue),
                    ),
                )
            }
            else -> statement
        }
    }

    /**
     * T-M22-02：乐观锁条件注入（@Version 实体 + MP OptimisticLockerInnerInterceptor 语义）——
     * UPDATE 语句自动追加 `SET version = version + 1` 与 WHERE 末尾 `AND version = ?`；
     * 语句已显式涉及 version 列（SET 赋值或 WHERE 条件）时不重复注入。
     * **不依赖配置开关**（对齐 MP 拦截器默认行为，无 @Version 字段的实体零影响）；
     * 仅 [UpdateStatement] 注入——SelectStatement/DeleteStatement 不注入。
     *
     * 边界（文档化，不实现）：
     *  - `@InterceptorIgnore`（方法级注解，静态代码不可映射调用方意图）不做抑制处理；
     *  - `@KeySequence`（序列取主键）不在主键解析范围内——主键恒以列名占位。
     */
    private fun injectOptimisticLockCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
        val update = statement as? UpdateStatement ?: return statement
        val versionColumn = table.versionColumn ?: return update
        // 已显式 SET version（如 set(Entity::getVersion, x) / setSql）→ 不重复注入
        if (update.sets.any { it.column?.columnName == versionColumn }) return update
        // 已显式条件 version（如 eq(Entity::getVersion, x)）→ 不重复注入
        if (conditionReferencesColumn(update.conditions, versionColumn)) return update
        // T-M23.10：联表更新（UpdateJoinWrapper）主表有别名——version 列与 SET 表达式带 tableAlias
        // （单表 tableAlias=null 渲染裸列名，维持历史格式）
        val alias = update.tableAlias
        val prefix = alias?.let { "$it." } ?: ""
        return update.copy(
            sets = update.sets + SetClause(
                column = ColumnRef(tableAlias = alias, columnName = versionColumn),
                rawSql = "$prefix$versionColumn = $prefix$versionColumn + 1",
            ),
            conditions = update.conditions + Condition.Simple(
                ColumnRef(tableAlias = alias, columnName = versionColumn), "=", SqlValue.Placeholder("?"),
            ),
        )
    }

    /**
     * T-TenantLine：多租户条件注入（TenantLineInnerInterceptor 模拟）——
     *  - SELECT/UPDATE/DELETE：WHERE 末尾追加 `AND {tenantColumn} = ?`
     *    （语句已显式引用租户列时不重复注入）；
     *  - INSERT：不在本方法处理（INSERT 无 WHERE，租户列追加见 [BatchOperationConverter]）。
     *
     * 仅 [PluginSettings.injectTenantLine] = true 时生效；调用链方法标注
     * `@InterceptorIgnore(tenantLine = true)` 时跳过。
     *
     * 与 injectLogicDeleteCondition / injectOptimisticLockCondition 三层串行——
     * 三者按语句类型互补：逻辑删除仅 SELECT/DELETE、乐观锁仅 UPDATE、租户 SELECT/UPDATE/DELETE。
     */
    private fun injectTenantLineCondition(
        table: TableInfo,
        statement: SqlStatement,
        hasInterceptorIgnore: Boolean,
    ): SqlStatement {
        val settings = PluginSettings.getInstance().state
        if (!settings.injectTenantLine) {
            LOG.info("injectTenantLine: SKIP switch=off, stmt=${statement::class.simpleName}, table=${table.tableName}")
            return statement
        }
        if (hasInterceptorIgnore) {
            LOG.info("injectTenantLine: SKIP @InterceptorIgnore(tenantLine=true), stmt=${statement::class.simpleName}")
            return statement
        }
        val tenantColumn = settings.tenantColumnName.ifBlank { DEFAULT_TENANT_COLUMN }
        return when (statement) {
            is SelectStatement -> {
                if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                    LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=SELECT")
                    return statement
                }
                LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=SELECT, table=${table.tableName}")
                statement.copy(
                    conditions = statement.conditions + Condition.Simple(
                        ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                        "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                    ),
                )
            }
            is UpdateStatement -> {
                if (statement.sets.any { it.column?.columnName == tenantColumn }) {
                    LOG.info("injectTenantLine: SKIP column in SET, col=$tenantColumn, stmt=UPDATE")
                    return statement
                }
                if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                    LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=UPDATE")
                    return statement
                }
                LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=UPDATE, table=${table.tableName}")
                statement.copy(
                    conditions = statement.conditions + Condition.Simple(
                        ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                        "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                    ),
                )
            }
            is DeleteStatement -> {
                if (conditionReferencesColumn(statement.conditions, tenantColumn)) {
                    LOG.info("injectTenantLine: SKIP column already in WHERE, col=$tenantColumn, stmt=DELETE")
                    return statement
                }
                LOG.info("injectTenantLine: INJECT col=$tenantColumn, stmt=DELETE, table=${table.tableName}")
                statement.copy(
                    conditions = statement.conditions + Condition.Simple(
                        ColumnRef(tableAlias = statement.tableAlias, columnName = tenantColumn),
                        "=", SqlValue.Placeholder("$tenantColumn (= ?)"),
                    ),
                )
            }
        }
    }

    /**
     * F-6 Phase B：动态表名替换——按用户配置的 [TableNameRule] 列表逐条匹配替换表名。
     *
     * 规则按列表顺序应用（首个匹配即停）；正则编译失败时跳过该规则并日志警告（不中断预览）。
     * 替换模板支持 `$1`/`$2` 引用捕获组、`$tableName` 引用完整原始表名。
     *
     * @param table     当前语句关联的实体表（用于 DynamicTableNameProcessor 的上下文）
     * @param statement 当前语句模型
     * @return 表名替换后的新语句（无规则命中时返回原实例）
     */
    private fun processDynamicTableName(table: TableInfo, statement: SqlStatement): SqlStatement {
        val rules = settings?.state?.dynamicTableNameRules?.filter { it.enabled && it.pattern.isNotBlank() }
            ?: return statement
        if (rules.isEmpty()) return statement
        return DynamicTableNameProcessor.process(table.tableName, rules)?.let { newTableName ->
            rewriteTableName(statement, newTableName)
        } ?: statement
    }

    /**
     * 将语句模型中的表名替换为新的表名（浅拷贝涉及表名的字段）。
     */
    private fun rewriteTableName(statement: SqlStatement, newTableName: String): SqlStatement = when (statement) {
        is SelectStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
        is UpdateStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
        is DeleteStatement -> statement.copy(table = statement.table.copy(tableName = newTableName))
    }

    /**
     * F-6 Phase C：数据权限模拟条件注入——开启 dataPermissionHint 后，追加用户填写的模拟 WHERE 片段。
     *
     * 运行时 DataPermissionInterceptor 根据用户角色动态追加条件，无法静态模拟；提供占位提示 +
     * 用户手动填写模拟条件（`dataPermissionMockCondition`），追加到 WHERE 末尾。
     *
     * 委托 [DataPermissionPlaceholder.injectMockCondition] 纯函数实现。
     */
    private fun injectDataPermissionCondition(table: TableInfo, statement: SqlStatement): SqlStatement {
        if (settings?.state?.dataPermissionHint != true) return statement
        return DataPermissionPlaceholder.injectMockCondition(statement, settings.state.dataPermissionMockCondition)
    }

    /**
     * T-TenantLine：检测调用链所在方法是否标注 @InterceptorIgnore(tenantLine=true)——
     * 从 anchor 向上查找包含它的 PsiMethod，检查 modifierList 注解。
     * 注解类不可解析时按短名 "InterceptorIgnore" 兜底（与 EntityResolver.findAnnotationByShortName 同模式）；
     * 属性名 = [attrName]（如 "tenantLine"），值 = true 时返回 true。
     *
     * 边界（文档化，保守策略）：
     *  - 注解在类级别 → 不做类级检测（仅方法级，与 MP 实际行为对齐）；
     *  - 注解类不可解析 / 属性缺失 → 兜底 false（不跳过，保持注入——宁可多不可少）；
     *  - 扫描抛异常 → catch 兜底返回 false。
     */
    private fun hasInterceptorIgnoreAnnotation(anchor: PsiElement, attrName: String): Boolean = runCatching {
        val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java) ?: return false
        method.modifierList.annotations?.any { annotation ->
            val simpleName = annotation.nameReferenceElement?.referenceName
            simpleName == "InterceptorIgnore" &&
                (annotation.findAttributeValue(attrName) as? PsiLiteralExpression)?.value as? Boolean == true
        } ?: false
    }.getOrDefault(false)

    /** T-M29-03：分页 LIMIT 注入——仅 SELECT 语句生效（UPDATE/DELETE 不追加，维持现状）。 */
    private fun applyPageLimit(statement: SqlStatement, pageLimit: LimitClause?): SqlStatement {
        val limit = pageLimit ?: return statement
        return when (statement) {
            is SelectStatement -> statement.copy(limit = limit)
            else -> statement
        }
    }

    /** T-M22-02：条件树是否已提及指定列（Simple/In/Between/Like/Unary/ColumnComparison 按列名匹配，Group 递归）。 */
    private fun conditionReferencesColumn(conditions: List<Condition>, columnName: String): Boolean =
        conditions.any { condition ->
            when (condition) {
                is Condition.Simple -> condition.column.columnName == columnName
                is Condition.Unary -> condition.column.columnName == columnName
                is Condition.In -> condition.column.columnName == columnName
                is Condition.Between -> condition.column.columnName == columnName
                is Condition.Like -> condition.column.columnName == columnName
                is Condition.ColumnComparison ->
                    condition.left.columnName == columnName || condition.right.columnName == columnName
                is Condition.Group -> conditionReferencesColumn(condition.children, columnName)
                is Condition.Raw -> false
            }
        }

    /**
     * 单个条件节点 → 条件视图。
     * 组带开关（布尔重载，T-M29.1 用户反馈：组行与子条件行重复展示）：子条件**并入组行**——
     * 组复选框 + 组内子条件值输入框（来源递归收集去重，按渲染顺序），子条件不再单独成行；
     * 组无开关（无布尔标志 `and(w -> ...)`）与非组条件：自身 toggle + 嵌套子条件扁平化（M19.6 存量）。
     */
    private fun Condition.toConditionViews(): List<ConditionView> {
        val ownToggle = toggle
        if (this is Condition.Group && ownToggle != null) {
            val sources = groupValueSources()
            return listOf(
                ConditionView(
                    ownToggle.id, ownToggle.label, ownToggle.flagSource, ownToggle.defaultEnabled,
                    sources.firstOrNull(), sources,
                ),
            )
        }
        val own = ownToggle?.let {
            // valueSources 为空时回退单值（旧路径仅设 valueSource，M19.5 兼容）
            val sources = it.valueSources.ifEmpty { listOfNotNull(it.valueSource) }
            ConditionView(it.id, it.label, it.flagSource, it.defaultEnabled, it.valueSource, sources)
        }
        val nested = when (this) {
            is Condition.Group -> children.flatMap { it.toConditionViews() }
            else -> emptyList()
        }
        return listOfNotNull(own) + nested
    }

    /** T-M29.1：组内子条件值来源递归收集（Simple/Like/Between/In 占位符，组递归；去重保持渲染顺序）。 */
    private fun Condition.groupValueSources(): List<String> = when (this) {
        is Condition.Simple -> listOfNotNull((value as? SqlValue.Placeholder)?.source)
        is Condition.Like -> listOfNotNull((pattern as? SqlValue.Placeholder)?.source)
        is Condition.Between -> listOfNotNull(
            (lo as? SqlValue.Placeholder)?.source,
            (hi as? SqlValue.Placeholder)?.source,
        )
        is Condition.In -> values.mapNotNull { (it as? SqlValue.Placeholder)?.source }
        is Condition.Group -> children.flatMap { it.groupValueSources() }
        else -> emptyList()
    }.distinct()

    /**
     * 当前方言（配置未加载时默认 MySQL）。
     */
    /**
     * O-5：当前方言（项目级优先，回退全局）。
     */
    fun currentDialect(): Dialect = DialectRegistry.byName(effectiveDialectName())

    /** O-5：有效方言名称（项目级优先，回退全局）。 */
    private fun effectiveDialectName(): String =
        effective({ settings?.state?.dialect ?: PluginSettings.DEFAULT_DIALECT },
                  { projectSettings?.effectiveDialect() })

    /**
     * 是否格式化输出（true=多行缩进，false=压缩；O-5：项目级优先）。
     */
    fun formatSql(): Boolean =
        effective({ settings?.state?.formatSql ?: true },
                  { projectSettings?.effectiveFormatSql() })

    /**
     * 风险警示（F-09/F-10 + F-6 Phase C BlockAttack 增强 + DataPermission 占位提示）。
     *
     * BlockAttackInnerInterceptor 阻止无 WHERE 的 UPDATE/DELETE——现有警示已覆盖核心功能；
     * 增强：标记为 BlockAttack 风格（拦截器名称标注）+ DataPermission 运行时差异提示。
     */
    private fun buildWarning(statement: SqlStatement): String? {
        val baseWarning = when (statement) {
            is UpdateStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据！" else null
            is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ [BlockAttack] 无 WHERE 条件，将删除全表数据！" else null
            else -> null
        }
        val dataPermissionWarning = DataPermissionPlaceholder.buildWarning(settings?.state?.dataPermissionHint ?: false)
        return listOfNotNull(baseWarning, dataPermissionWarning).joinToString("\n").ifBlank { null }
    }

    /**
     * 查询类 Wrapper 类型（可能被删除类消费方法 `remove(wrapper)`/`delete(wrapper)` 复用为删除条件，M10）。
     */
    private fun WrapperType.isQueryLike(): Boolean = this in setOf(
        WrapperType.LAMBDA_QUERY,
        WrapperType.QUERY,
        WrapperType.MPJ_LAMBDA,
        WrapperType.MPJ_QUERY,
        WrapperType.MPJ_LAMBDA_QUERY,
    )

    /**
     * T-M21-08：联表更新/删除 Wrapper 类型集合——`updateJoin(entity, wrapper)` / `updateJoinAndNull` /
     * `deleteJoin(wrapper)` 消费由 UpdateJoin/DeleteJoinConverter 处理（实体 SET 列近似、多表删除目标），
     * 用于 [preview] 的 updateEntityConsumed / deleteConsumed 早退分支类型守卫，新类型走
     * ConverterRegistry 分派（保留主表别名与 JOIN，不走单表实体展开 / 单表 DeleteStatement 路径）。
     */
    private val UPDATE_DELETE_JOIN_TYPES: Set<WrapperType> = setOf(
        WrapperType.UPDATE_JOIN,
        WrapperType.DELETE_JOIN,
    )

    /**
     * 查询类解析统一入口（T-M20-06 补强）：优先走 [ConverterRegistry] 注册的 converter——
     * MPJ 类型由此保留**主表别名与 JOIN**（`selectJoinCount`/`exists` 的联表过滤语义：
     * 单表 QueryWrapperParser 会静默丢弃 join 调用，INNER JOIN 的行数语义会丢失）；
     * 非 SelectStatement 结果（异常路径）回退单表解析。与 `else -> ConverterRegistry.convert` 分支同源。
     */
    private fun parseQueryLike(chain: cn.song.mybatisplus.visualizer.model.WrapperChain, table: TableInfo): SelectStatement =
        (ConverterRegistry.convert(chain, table) as? SelectStatement) ?: QueryWrapperParser(table).parse(chain)

    /**
     * 逻辑删除注入状态（T-M20-05）：MPJ 官方六方法按源码顺序求值的结果，驱动 [injectLogicDeleteCondition]。
     *
     * @param mainActive 主表逻辑删除开关（默认开）；false 时主表 deleted 不注入 WHERE
     * @param subActive  副表（join 表）逻辑删除开关（默认开）；false 时副表 deleted 不注入
     * @param subToWhere 副表 deleted 条件位置：true → WHERE 末尾（`logicDelToWhere`）；
     *                   false（默认 / `logicDelToOn`）→ join ON 条件
     */
    data class LogicDeleteState(
        val mainActive: Boolean = true,
        val subActive: Boolean = true,
        val subToWhere: Boolean = false,
    )

    /**
     * 逻辑删除控制求值（T-M20-05，对齐 MPJ 官方六方法语义）：
     * 按**源码顺序**扫描链调用维护三态状态（链上可先关后开 / 先开后关精确控制）：
     *  - `disableLogicDel` / `enableLogicDel` → 主表逻辑删除开关（默认开）；
     *  - `disableSubLogicDel` / `enableSubLogicDel` → 副表（join 表）逻辑删除开关（默认开）；
     *  - `logicDelToOn` / `logicDelToWhere` → 副表 deleted 条件位置（ON / WHERE 末尾），**不影响主表注入**
     *    （旧实现把二者当作全局开关是错误近似，T-M20-05 已修正）。
     *
     * 非 MPJ 链不出现上述方法 → 返回全默认状态（主表/副表均注入，行为与 T-M15-07 基线一致）。
     */
    private fun evaluateLogicDeleteState(calls: List<cn.song.mybatisplus.visualizer.model.WrapperCall>): LogicDeleteState {
        var state = LogicDeleteState()
        for (call in calls) {
            state = when (call.methodName) {
                METHOD_DISABLE_LOGIC_DEL -> state.copy(mainActive = false)
                METHOD_ENABLE_LOGIC_DEL -> state.copy(mainActive = true)
                METHOD_DISABLE_SUB_LOGIC_DEL -> state.copy(subActive = false)
                METHOD_ENABLE_SUB_LOGIC_DEL -> state.copy(subActive = true)
                METHOD_LOGIC_DEL_TO_ON -> state.copy(subToWhere = false)
                METHOD_LOGIC_DEL_TO_WHERE -> state.copy(subToWhere = true)
                else -> state
            }
        }
        return state
    }

    /**
     * 解析 HQL 对应的实体表信息。
     *
     * 优先使用 entityClass（PsiClass 直接解析）；
     * 否则按 entityClassName 查找 @Entity 类；
     * 最后从 HQL 的 FROM 子句提取类名查找。
     */
    private fun resolveHqlTable(entityClass: PsiClass?, entityClassName: String?, hql: String): TableInfo? {
        val resolver = JpaEntityResolver()
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull() ?: return null
        val className = entityClassName ?: extractEntityNameFromHql(hql)

        // 路径 1：有 PsiClass 直接解析（JPA 注解）
        if (entityClass != null) {
            val table = resolver.resolve(entityClass)
            if (table != null) return table
        }

        // 路径 2：按类名查找 @Entity 类（JPA 注解）
        if (className != null) {
            val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(className, scope)
                ?: findClassByShortName(project, className, scope)
            if (psiClass != null) {
                val table = resolver.resolve(psiClass)
                if (table != null) return table
            }
        }

        // 路径 3：Hibernate XML 映射文件（.hbm.xml）
        if (className != null) {
            val hbmMapping = HbmXmlEntityResolver.resolve(className, project)
            if (hbmMapping != null) {
                return convertHbmMappingToTableInfo(hbmMapping)
            }
        }

        return null
    }

    /**
     * 从 HQL 的 FROM 子句提取实体类名。
     * 适用于 createQuery(HQL) 单参数场景（无 Entity.class 二参）。
     */
    private fun extractEntityNameFromHql(hql: String): String? {
        val fromPattern = Regex("""\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
        return fromPattern.find(hql)?.groupValues?.get(1)
    }

    /**
     * 将 HBM XML 映射转换为 TableInfo（供 HqlConverter 使用）。
     */
    private fun convertHbmMappingToTableInfo(mapping: HbmXmlMapping.ClassMapping): TableInfo {
        val columns = mapping.properties.map { prop ->
            ColumnInfo(
                fieldName = prop.fieldName,
                columnName = prop.columnName,
                isId = prop.isId,
            )
        }
        val associations = mapping.associations.map { assoc ->
            // 从全限定名提取简单类名作为目标表名（实际应用中应解析目标类的映射）
            val targetSimpleName = assoc.targetClassName.substringAfterLast('.')
            AssociationInfo(
                fieldName = assoc.fieldName,
                joinColumn = assoc.joinColumn,
                targetTable = targetSimpleName,
                targetAlias = targetSimpleName.replaceFirstChar { it.lowercaseChar() },
            )
        }
        return TableInfo(
            entityClassName = mapping.entityClassName,
            tableName = mapping.tableName,
            columns = columns,
            associations = associations,
        )
    }

    /**
     * 按短类名查找 PsiClass（JavaPsiFacade.findClass 需要全限定名）。
     * 使用 PsiShortNamesManager 按短名搜索，返回第一个匹配的类。
     */
    private fun findClassByShortName(
        project: com.intellij.openapi.project.Project,
        shortName: String,
        scope: com.intellij.psi.search.GlobalSearchScope,
    ): PsiClass? {
        val shortNamesCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        val classes = shortNamesCache.getClassesByName(shortName, scope)
        return classes.firstOrNull()
    }

    /**
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    fun clearCache() = cache.clear()

    /** Wrapper 解析缓存条目数（设置页缓存统计，PluginCacheManager 调用）。 */
    fun wrapperCacheEntryCount(): Int = cache.size()

    /** Wrapper 解析缓存大小估算（设置页缓存统计，PluginCacheManager 调用）。 */
    fun wrapperCacheSizeBytes(): Long = cache.sizeBytes()

    /**
     * F-7：预览完成后自动记录历史。
     *
     * 从锚点元素提取来源文件名/行号，与预览结果一并写入 [SqlHistoryManager]。
     * 尝试从 [SqlLogManager] 最近日志条目中匹配相同 SQL 指纹补全执行耗时。
     * 服务不可用时静默跳过（不阻塞预览）。
     */
    private fun recordHistory(result: PreviewResult, anchor: PsiElement) {
        runCatching {
            val manager = SqlHistoryManager.getInstance()
            manager.maxSize = settings?.state?.historyMaxSize ?: SqlHistoryManager.DEFAULT_MAX_SIZE
            val file = anchor.containingFile
            val sourceFile = file?.name ?: ""
            val sourceLine = anchor.textRange.startOffset.let { offset ->
                file?.text?.let { text ->
                    text.substring(0, offset.coerceAtMost(text.length)).count { it == '\n' } + 1
                } ?: 0
            }
            // 尝试从最近日志条目补全耗时（SQL 指纹匹配）
            val logManager = anchor.project.getService(SqlLogManager::class.java)
            val executionTimeMs = logManager.entries.snapshot()
                .firstOrNull { it.restoredSql == result.sql }
                ?.executionTimeMs
            manager.add(
                SqlHistoryEntry(
                    sql = result.sql,
                    compactSql = result.compactSql,
                    timestamp = System.currentTimeMillis(),
                    sourceFile = sourceFile,
                    sourceLine = sourceLine,
                    tableName = result.tableName ?: "",
                    executionTimeMs = executionTimeMs,
                ),
            )
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SqlPreviewService::class.java)

        /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法；单一来源，评估逻辑 [evaluateLogicDeleteState] 使用）。 */
        private const val METHOD_DISABLE_LOGIC_DEL = "disableLogicDel"
        private const val METHOD_ENABLE_LOGIC_DEL = "enableLogicDel"
        private const val METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel"
        private const val METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel"
        private const val METHOD_LOGIC_DEL_TO_ON = "logicDelToOn"
        private const val METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere"

        /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
        private const val DEFAULT_TENANT_COLUMN = "tenant_id"

        /**
         * 统一展示入口（M10-03）：恒以弹窗（[SqlPreviewDialog]）展示预览结果，
         * 供 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 与 gutter 图标点击共用（M11 起收敛为弹窗）。
         *
         * @param result null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
         * @param sourceLocation 当前代码位置（文件名:行号），来源列默认值；null 表示未知
         */
        fun showResult(project: Project, result: PreviewResult?, sourceLocation: String? = null) {
            if (result == null) {
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("action.notFound.message"),
                    MpSqlVisualizerBundle.message("action.notFound.title"),
                )
                return
            }
            SqlPreviewDialog(project, result, sourceLocation).show()
        }

        /**
         * 后台线程 + 读操作执行转换，EDT 回调结果。
         *
         * PSI 定位/解析（含注解解析）在 IDEA 2025.3+ 属于"慢操作"，禁止在 EDT 上执行；
         * 按钮/快捷键/编辑器跟随等 UI 触发方统一走此入口，避免
         * "Slow operations are prohibited on EDT"。
         *
         * @param project  项目
         * @param compute  在 ReadAction 内执行转换（锚点元素可先在 EDT 捕获，块内读取）
         * @param onResult 在 EDT 上回调转换结果（未找到 Wrapper 时为 null）
         */
        @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
        fun previewAsync(project: Project, compute: () -> PreviewResult?, onResult: (PreviewResult?) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = try {
                    ReadAction.compute<PreviewResult?, Throwable> { compute() }
                } catch (e: Throwable) {
                    null
                }
                ApplicationManager.getApplication().invokeLater { onResult(result) }
            }
        }
    }
}
