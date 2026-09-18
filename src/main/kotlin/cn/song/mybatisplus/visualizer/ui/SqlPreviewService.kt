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
import cn.song.mybatisplus.visualizer.converter.EasyQueryConverter
import cn.song.mybatisplus.visualizer.converter.MdsConverter
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.converter.jpa.CriteriaQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.DerivedQueryConverter
import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter
import cn.song.mybatisplus.visualizer.converter.jpa.JpaQueryEnhancer
import cn.song.mybatisplus.visualizer.converter.jpa.JpqlConverter
import cn.song.mybatisplus.visualizer.converter.jpa.PartTreeParser
import cn.song.mybatisplus.visualizer.converter.jpa.QueryByExampleConverter
import cn.song.mybatisplus.visualizer.converter.jpa.SpecificationQueryConverter
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.psi.jpa.DaoQueryKind
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryInfo
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator
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
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
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

    /**
     * T-ErrorLocate T-EL-03：最近一次预览的源锚点（文件 + 0 基光标行），错误定位动作消费。
     * 预览触发入口（ConvertToSqlAction）刷新；null = 本会话尚未有可解析锚点（定位按钮不出现）。
     * 单槽即可：预览弹窗每项目至多一个（M36.5.47 单例化），同刻只有一个错误面板可定位。
     */
    var lastPreviewSourceAnchor: SourceAnchor? = null

    /** 预览源锚点（文件 + 0 基行）。 */
    data class SourceAnchor(val file: com.intellij.openapi.vfs.VirtualFile, val line0: Int)

    init {
        // T-PerfTail #1：编辑器关闭即清理该文件的 WrapperCache 条目——长会话中条目数随打开
        // 文件集有界（LRU 512 上限之外的主动回收）。连接绑定 project Disposable，项目关闭自动注销。
        // FileEditorManagerListener 的 fileClosed 携带 VirtualFile，与 WrapperCache 键的
        // VirtualFile.url 字符串维度（T-Perf-18）直接对齐。
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileClosed(
                    source: com.intellij.openapi.fileEditor.FileEditorManager,
                    file: com.intellij.openapi.vfs.VirtualFile,
                ) {
                    cache.evictFile(file.url)
                }
            },
        )
    }

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
        val resolveVariables = effectiveReplaceParams()
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
                // O-8（缺陷 B）：字符串服务链实体解析失败（三通道与方法引用推断全失败）→
                // 返回带显式警示的空结果（面板红字），不再静默 return null——
                // 「返回 null = 非 wrapper 锚点」契约收窄保留：仅 located 带 entityResolutionFailed
                // 标记（服务链形态）时降级，工厂/Kotlin/构造形态与其余锚点行为不变
                if (located.entityResolutionFailed) {
                    LOG.info("preview: DEGRADE table=null, wrapperType=${located.chain.wrapperType}, " +
                             "anchor=${anchor.textRange}")
                    return PreviewResult(
                        sql = "",
                        compactSql = "",
                        unresolvedParams = emptyList(),
                        paramTable = null,
                        wrapperType = located.chain.wrapperType,
                        tableName = null,
                        warning = "⚠ 字符串服务链无法推断实体表：Wrapper 链未携带实体类型" +
                            "（无 <实体> 类型实参，服务接口/调用返回类型也无实体泛型），SQL 无法生成",
                    )
                }
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
                    located.chain.wrapperType !in UPDATE_DELETE_JOIN_TYPES -> {
                    val parsed = QueryWrapperParser(table).parse(located.chain)
                    // O-8（缺陷 C）：`last("LIMIT n")` 尾段透传 DELETE 渲染（此前落在 SelectStatement
                    // 独有字段被静默丢弃；跨方言渲染与否由 SqlGenerator 按 [Dialect.supportsDeleteLimit] 决定）
                    DeleteStatement(table, parsed.conditions, limit = parsed.limit)
                }
                else -> ConverterRegistry.convert(located.chain, table) ?: return null
            }
            // T-M15-07：逻辑删除注入（@TableLogic 实体 + 配置开启）——查询/计数/exists/删除自动追加 `AND deleted = 0`
            // （对齐 MyBatis-Plus 全局 logic-delete-field 配置；UPDATE 不注入——更新可能修改 deleted 本身）
            // T-M20-05（P1-01 语义修正）：MPJ 六方法按源码顺序求值——主表/副表逻辑删除独立开关，
            // logicDelToOn/logicDelToWhere 仅控制**副表** deleted 条件位置（ON / WHERE 末尾），不再影响主表注入
            // （旧实现把二者当作全局开关是错误近似，见 [evaluateLogicDeleteState]）
            val logicDelete = evaluateLogicDeleteState(located.chain.calls)
            val baseStatement = if (effectiveInjectLogicDelete()) {
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
        // T-J1/T-J6/T-J21：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作派发
        // （receiver 家族感知，消歧规则见 JpaCrudOperationConverter KDoc）——receiver 判定为
        // JPA 家族时必须走 JpaEntityResolver：MP EntityResolver 只认 @TableName，JPA 实体
        // （@Table(name="t_user")）会被输出成 `INSERT INTO user_entity` 错表错列；
        // 非 JPA 家族 convert 返回 null，维持下方 MP 批量路径现状（存量行为零变化）
        // T-J5/T-J20 前置分支：Specification / Query By Example executor 调用先于 JPA CRUD 家族判定——
        // `findAll(spec)` / `findAll(example)` 的方法名同在 JpaCrudOperationConverter.REPOSITORY_METHODS
        // 名单内，后置会被渲染成「无 WHERE 全表 SELECT」（JpaCrudOperationConverter KDoc 建议的扩面方式：
        // 在 SqlPreviewService 侧新增前置分支，不改 Wave2 交付文件）
        if (batchCall != null) {
            val specPreview = SpecificationQueryConverter.convert(batchCall, resolveVariables)
            if (specPreview != null) {
                val result = PreviewResult(
                    sql = specPreview.sql,
                    compactSql = specPreview.sql.replace(Regex("\\s+"), " ").trim(),
                    unresolvedParams = specPreview.paramSources,
                    paramTable = ParameterReplacer.buildParamTable(specPreview.paramSources),
                    paramHints = specPreview.paramHints,
                    wrapperType = null,
                    tableName = specPreview.tableName,
                    warning = specPreview.warning,
                    columns = specPreview.columns,
                )
                recordHistory(result, anchor)
                return result
            }
            val examplePreview = QueryByExampleConverter.convert(batchCall, resolveVariables)
            if (examplePreview != null) {
                val result = PreviewResult(
                    sql = examplePreview.sql,
                    compactSql = examplePreview.sql.replace(Regex("\\s+"), " ").trim(),
                    unresolvedParams = examplePreview.paramSources,
                    paramTable = ParameterReplacer.buildParamTable(examplePreview.paramSources),
                    paramHints = examplePreview.paramHints,
                    wrapperType = null,
                    tableName = examplePreview.tableName,
                    columns = examplePreview.columns,
                )
                recordHistory(result, anchor)
                return result
            }
            // EQ/DS/QD（M44 其他 ORM 第一批）前置派发：Easy-Query proxy 链 / MyBatis Dynamic SQL /
            // Querydsl——三者均非 MP Wrapper 链（locateWithTable 不命中），按 entry+receiver 启发认领；
            // 不认领返回 null 走后续 JPA CRUD / MP 批量管道（存量行为零变化）
            val ormPreview = convertOtherOrm(batchCall, resolveVariables)
            if (ormPreview != null) {
                recordHistory(ormPreview, anchor)
                return ormPreview
            }
        }
        val jpaCrud = batchCall?.let {
            JpaCrudOperationConverter.convert(
                it,
                resolveVariables = effectiveReplaceParams(),
            )
        }
        if (jpaCrud != null) {
            val result = PreviewResult(
                sql = jpaCrud.sql,
                compactSql = jpaCrud.sql,
                unresolvedParams = jpaCrud.paramSources,
                paramTable = jpaCrud.paramGroups,
                wrapperType = null,
                tableName = jpaCrud.tableName,
                warning = jpaCrud.warning,
                paramHints = jpaCrud.paramHints,
                batchModel = jpaCrud.batchModel,
            )
            recordHistory(result, anchor)
            return result
        }
        // T-M23-01/02：批量路径接线——逻辑删除注入开关（settings.injectLogicDelete）与变量求值开关
        // （settings.replaceParams，实参可静态求值时内联）对齐 Wrapper 路径；参数来源/回填提示透传面板
        val batch = batchCall?.let {
            BatchOperationConverter.convert(
                it,
                injectLogicDelete = effectiveInjectLogicDelete(),
                resolveVariables = effectiveReplaceParams(),
                // D2：INSERT 租户列追加 = 主开关 AND 子开关（injectTenantOnInsert）；UPDATE 的 SET 租户注入
                // 走主开关（见上方 enhanceUpdateSet 链），不受本参数影响
                injectTenantLine = (settings?.state?.injectTenantLine ?: true) &&
                    (settings?.state?.injectTenantOnInsert ?: true),
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
     * 其他 ORM 第一批（M44）前置派发：Easy-Query proxy（EQ_PROXY）/ MyBatis Dynamic SQL（MDS）/
     * Querydsl（QD）。三者按 entry + receiver 启发认领（普通类同名方法不命中，负例守卫），
     * 不认领返回 null（交回 JPA CRUD / MP 批量管道）。
     */
    private fun convertOtherOrm(call: PsiMethodCallExpression, resolveVariables: Boolean): PreviewResult? {
        val eq = EasyQueryConverter.convert(call, resolveVariables)
        if (eq != null) {
            return PreviewResult(
                sql = eq.sql,
                compactSql = eq.sql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = eq.paramSources,
                paramTable = ParameterReplacer.buildParamTable(eq.paramSources),
                paramHints = eq.paramHints,
                wrapperType = WrapperType.EQ_PROXY,
                tableName = eq.tableName,
                warning = eq.warning,
                columns = eq.columns,
                batchModel = eq.batchModel,
            )
        }
        val mds = MdsConverter.convert(call, resolveVariables)
        if (mds != null) {
            return PreviewResult(
                sql = mds.sql,
                compactSql = mds.sql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = mds.paramSources,
                paramTable = ParameterReplacer.buildParamTable(mds.paramSources),
                paramHints = mds.paramHints,
                wrapperType = WrapperType.MDS,
                tableName = mds.tableName,
                warning = mds.warning,
                columns = mds.columns,
            )
        }
        val qd = QuerydslConverter.convert(call, resolveVariables)
        if (qd != null) {
            return PreviewResult(
                sql = qd.sql,
                compactSql = qd.sql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = qd.paramSources,
                paramTable = ParameterReplacer.buildParamTable(qd.paramSources),
                paramHints = qd.paramHints,
                wrapperType = WrapperType.QD,
                tableName = qd.tableName,
                warning = qd.warning,
                columns = qd.columns,
            )
        }
        return null
    }

    /**
     * JPA 推导查询预览（F-14 阶段四）：解析 Repository 方法名 → PartTree → SqlStatement → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpaGutterLineMarkerProvider] 点击调用与
     * T-J9 调用点反查（ConvertToSqlAction）调用。
     *
     * @param method   Repository 声明方法
     * @param callArgs T-J9 调用点实参（`userRepository.findByName(x, PageRequest.of(0, 20))` 的实参列表）；
     *                 非 null 时对 PageRequest.of / Sort.by 做静态求值（有字面量就内联渲染，无则 ? 占位）
     */
    fun previewDerivedQuery(method: PsiMethod, callArgs: List<PsiExpression>? = null): PreviewResult? {
        val methodInfo = DerivedMethodLocator.locate(method.containingFile ?: return null, method.textRange.startOffset)
            ?: return null
        val entityClass = methodInfo.entityClass ?: return null
        val table = JpaEntityResolver().resolve(entityClass) ?: return null
        val partTree = PartTreeParser.parse(methodInfo.methodName)
        var statement = DerivedQueryConverter.convert(partTree, table)
        // T-J10：Pageable/Page/Sort/Limit 参数全路径——Pageable/Page → LIMIT 占位（调用点形态静态求值）、
        // Sort → ORDER BY（Sort.by 字面量内联）、Limit（3.2） → 仅 LIMIT 无 OFFSET
        val pageSort = JpaQueryEnhancer.evaluatePageSort(method, callArgs, currentDialect(), table)
        if (pageSort != null) {
            var select = statement as? SelectStatement
            pageSort.limit?.let { limit -> select = select?.copy(limit = limit) }
            if (pageSort.orderBy.isNotEmpty()) select = select?.copy(orderBy = pageSort.orderBy)
            if (select != null) statement = select
        }
        // 使用实际方法参数名（而非通用的 param1/param2）
        // 分页/排序/限流参数：排除参数本身，追加分页占位参数行（pageOffset/pageSize 等）
        val paramNames = method.parameterList.parameters
            .filter { param ->
                val typeName = param.type.canonicalText.substringBefore('<')
                !JpaQueryEnhancer.isPageableType(typeName) &&
                    !JpaQueryEnhancer.isSortType(typeName) &&
                    !JpaQueryEnhancer.isLimitType(typeName)
            }
            .map { it.name } + (pageSort?.extraParamNames ?: emptyList())
        // DerivedQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement)
        // T-J4：IN/NOT IN 条件参数接线——inClauseParameterOrdinals 序号对齐方法参数名
        // （过滤 Pageable 后的 paramNames 序），把 IN 参数挂进 buildJpaPreviewResult 的
        // inClauseParams（面板回填按逗号分割多值，对齐 JPQL/HQL 路径的 Result.inClauseParams 惯例）
        val inClauseParams = DerivedQueryConverter.inClauseParameterOrdinals(methodInfo.methodName)
            .mapNotNull { paramNames.getOrNull(it) }
            .toSet()
        // T-J15/J22：@Lock（FOR UPDATE）/ @QueryHints / @EntityGraph（LEFT JOIN）/ 返回类型 notes；
        // pageNotes：分页/排序分析已在上方完成（含调用点实参求值），透传语义注释、跳过二次分析
        val enhancedSql = applyJpaMethodEnhancements(
            method, sql, table, native = false,
            limitAppliedInModel = true,
            pageNotes = pageSort?.notes ?: emptyList(),
        )
        return buildJpaPreviewResult(statement, enhancedSql, table.tableName, paramNames, method, inClauseParams)
    }

    /**
     * JPA 方法级 SQL 增强（T-J10/J15/J22，文本级后处理，previewDerivedQuery / previewJpql /
     * previewHql / previewCriteriaQuery 各路径共用）：
     *
     *  - **T-J10**：宿主方法 `Pageable`/`Page`/`Limit` 参数 → LIMIT/OFFSET 占位（方言形态）；
     *    `Sort` 参数静态求值 → ORDER BY（与查询内已有子句并存时降级注释，避免双子句）；
     *  - **T-J15**：`@Lock(PESSIMISTIC_WRITE)` → SELECT 尾部 `FOR UPDATE`（共享锁形态注释说明方言差异）；
     *    `@QueryHints` 逐条注释；`@EntityGraph(attributePaths)` → LEFT JOIN 注入（按关联元数据解析
     *    真实表/列；解析失败 / 原生 SQL 路径降级注释）；
     *  - **T-J22**：返回类型 notes（Page count 二连 / Slice 多取一条 / Stream / 接口投影）；
     *    `Page` 返回 + `@Query.countQuery` 存在时一并展示 count SQL。
     *
     * 注释行以 `-- ` 前缀追加 SQL 尾部（对齐 JpaCrudOperationConverter notes 惯例）；
     * 全程无注解/无参数时返回原文（存量字节级行为零变化）。
     */
    private fun applyJpaMethodEnhancements(
        method: PsiMethod?,
        sql: String,
        table: TableInfo?,
        native: Boolean,
        limitAppliedInModel: Boolean = false,
        pageNotes: List<String>? = null,
    ): String = runCatching {
        if (method == null) return@runCatching sql
        val dialect = currentDialect()
        val notes = mutableListOf<String>()
        var enhanced = sql

        // T-J15：@EntityGraph → LEFT JOIN（JPQL/HQL 非原生路径；原生查询运行时不应用实体图）
        var joinLines: List<String> = emptyList()
        JpaQueryEnhancer.entityGraph(method)?.let { graph ->
            if (native || table == null) {
                notes += "@EntityGraph(attributePaths)：原生 SQL 查询运行时不应用实体图，预览不追加 JOIN"
            } else {
                val (joins, graphNotes) = JpaQueryEnhancer.resolveEntityGraphJoins(graph, table)
                notes += graphNotes
                val mainAlias = detectMainAlias(enhanced, table)
                joinLines = joins.map { JpaQueryEnhancer.renderJoinLine(it, mainAlias, dialect) }
                if (joins.isNotEmpty()) {
                    notes += "@EntityGraph(attributePaths=${graph.paths.joinToString(", ")})：按实体图 LEFT JOIN 抓取（type=${graph.type ?: "LOAD"}）"
                }
            }
        }

        // T-J10：Pageable/Limit → LIMIT；Sort → ORDER BY（查询内已有子句时降级注释）。
        // limitAppliedInModel：派生/Criteria 路径 LIMIT 已在语句模型级挂载（传 true 跳过文本追加，
        // 避免同语句双 LIMIT）；
        // pageNotes 非 null：调用方已完成分页/排序分析（含调用点实参求值，T-J9/J10），notes 直接透传、
        // 跳过本方法内的二次分析（callArgs=null 的重分析会产出与实际内联结果相悖的「静态不可解析」注释）
        if (pageNotes != null) {
            notes += pageNotes
        } else {
            JpaQueryEnhancer.evaluatePageSort(method, null, dialect, table)?.let { pageSort ->
                notes += pageSort.notes
                pageSort.limit?.let { limit ->
                    if (limitAppliedInModel ||
                        JpaQueryEnhancer.containsTopLevelClause(enhanced, Regex("(?i)\\b(LIMIT|OFFSET|FETCH)\\b"))
                    ) {
                        if (!limitAppliedInModel) {
                            notes += "查询内已含分页子句，方法分页参数以查询内为准（预览不重复追加）"
                        }
                    } else {
                        enhanced += "\n" + dialect.renderLimit(limit)
                    }
                }
                if (pageSort.orderBy.isNotEmpty()) {
                    if (JpaQueryEnhancer.containsTopLevelClause(enhanced, Regex("(?i)\\bORDER\\s+BY\\b"))) {
                        notes += "查询内已含 ORDER BY，Sort 参数运行时与其合并"
                    } else {
                        enhanced += "\nORDER BY " + pageSort.orderBy.joinToString(", ") {
                            it.column.qualifiedName + if (it.asc) " ASC" else " DESC"
                        }
                    }
                }
            }
        }

        // JOIN 行插入（置于 LIMIT/ORDER BY 之前的顶层位置）
        enhanced = JpaQueryEnhancer.insertJoinLines(enhanced, joinLines)

        // T-J15：@Lock → FOR UPDATE / 共享锁方言差异注释
        JpaQueryEnhancer.lockClause(method)?.let { (clause, note) ->
            notes += note
            if (clause != null) enhanced += " $clause"
        }
        // T-J15：@QueryHints 逐条注释
        notes += JpaQueryEnhancer.queryHintNotes(method)
        // T-J22：返回类型 notes + Page 返回时展示 @Query.countQuery 的 count SQL
        notes += JpaQueryEnhancer.returnTypeNotes(method)
        notes += countQueryNotes(method, table)

        if (notes.isEmpty()) return@runCatching sql
        enhanced + notes.filter { it.isNotBlank() }.joinToString("") { "\n-- $it" }
    }.getOrDefault(sql)

    /** T-J22：@Query.countQuery → count SQL 注释行（Page 返回语义；转换失败降级原文注释）。 */
    private fun countQueryNotes(method: PsiMethod, table: TableInfo?): List<String> = runCatching {
        val countJpql = JpaQueryEnhancer.countQueryAttribute(method) ?: return@runCatching emptyList()
        // 注释前缀 "-- " 由 applyJpaMethodEnhancements 统一追加，此处只给内容行
        listOf("@Query.countQuery（Page 返回的 count 伴随查询）：") + runCatching {
            table?.let { t -> JpqlConverter.convert(countJpql, t, project).sql } ?: countJpql
        }.getOrDefault(countJpql).lines()
    }.getOrDefault(emptyList())

    /**
     * 从 SQL 文本探测主表别名（EntityGraph JOIN 的 ON 左值用）：
     * `FROM t_user u` → `u`；无别名/别名是 SQL 关键字 → 表名本名。
     */
    private fun detectMainAlias(sql: String, table: TableInfo): String = runCatching {
        val regex = Regex("(?i)\\bFROM\\s+[`\"(]*([\\w.]+)[`\"]*)\\s+([a-zA-Z_][\\w$]*)")
        val match = regex.find(sql)
        val alias = match?.groupValues?.get(2)
        if (alias == null || SQL_CLAUSE_KEYWORDS.any { alias.equals(it, ignoreCase = true) }) {
            table.tableName
        } else {
            alias
        }
    }.getOrDefault(table.tableName)

    /**
     * JPQL/@Query 预览（波次一）：解析 @Query 注解方法 → JPQL 字符串 → SQL → PreviewResult。
     *
     * 供 [cn.song.mybatisplus.visualizer.ui.gutter.JpqlGutterLineMarkerProvider] 点击调用。
     * T-J10/J15/J22：方法级增强（Pageable/Limit → LIMIT 占位、@Lock → FOR UPDATE、
     * @QueryHints 注释、@EntityGraph → LEFT JOIN、Page 返回 → count SQL 展示）见
     * [applyJpaMethodEnhancements]。
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
        // T-J10：分页/排序参数占位名（无参数时空列表，存量行为零变化）
        val extraParamNames = JpaQueryEnhancer.evaluatePageSort(method, null, currentDialect(), table)
            ?.extraParamNames ?: emptyList()
        val allParamNames = paramNames + extraParamNames
        // 原生 SQL：转换命名参数 :param → ? 后展示（保持可读性）
        if (info.isNative) {
            val convertedSql = convertNativeSqlParameters(jpql)
            // 检测 IN 子句参数（在命名参数替换前检测 :paramName）
            val inParams = JpqlConverter.findInClauseParams(jpql)
            val hints = if (inParams.isNotEmpty()) allParamNames.map { ParamHint(quote = true, inList = it in inParams) } else emptyList()
            // T-J15：@EntityGraph 对原生查询运行时不生效 → 仅注释说明；LIMIT/@Lock/notes 照常
            val enhancedSql = applyJpaMethodEnhancements(method, convertedSql, table, native = true)
            return PreviewResult(
                sql = enhancedSql,
                // 无增强时保持旧字节级行为（convertedSql 原样），有增强时压缩多行空白
                compactSql = if (enhancedSql == convertedSql) convertedSql else enhancedSql.replace(Regex("\\s+"), " ").trim(),
                unresolvedParams = allParamNames,
                paramTable = ParameterReplacer.buildParamTable(allParamNames),
                paramHints = hints,
                wrapperType = null,
                tableName = table?.tableName
                    ?: info.entityClass?.name?.let { JpaEntityResolver.inferDecoratedTableName(it, project) } ?: "",
                columns = table?.allColumnNames ?: emptyList(),
            )
        }
        // JPQL → JpqlConverter.convert → Result（转换后 SQL + 语句模型）
        // 关键：使用转换后的 SQL 字符串（含 ? 占位符）直接用于面板显示和参数回填。
        // 若用 SqlGenerator 重新生成，条件树为空会丢失 WHERE 子句和 ? 占位符。
        // Wave1 第三参 project：激活 PSI 级 JOIN ON（@JoinColumn 真实化）与子查询实体映射
        val result = JpqlConverter.convert(jpql, table, project)
        // T-J10/J15/J22：方法级增强（LIMIT/ORDER BY/FOR UPDATE/EntityGraph JOIN/notes/count SQL）
        val enhancedSql = applyJpaMethodEnhancements(method, result.sql, table, native = false)
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = enhancedSql,
            tableName = table?.tableName ?: "",
            paramNames = allParamNames,
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
     * T-J10：Pageable/Sort/Limit 类型参数本体不登记参数行（分页占位参数行 pageSize/pageOffset 等
     * 由 [JpaQueryEnhancer.evaluatePageSort] 的 extraParamNames 补充，避免同一参数双行）。
     */
    private fun extractJpqlParamNames(method: PsiMethod): List<String> {
        val parameters = method.parameterList.parameters
        val paramAnnotationQName = "org.springframework.data.repository.query.Param"
        return parameters.mapNotNull { param ->
            val typeName = param.type.canonicalText.substringBefore('<')
            if (JpaQueryEnhancer.isPageableType(typeName) ||
                JpaQueryEnhancer.isSortType(typeName) ||
                JpaQueryEnhancer.isLimitType(typeName)
            ) {
                return@mapNotNull null
            }
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
     * T-J10：宿主方法 Pageable/Limit 参数 → LIMIT 子句（语句模型级，进 previewModel 交互重生成）。
     */
    fun previewCriteriaQuery(anchor: PsiElement): PreviewResult? {
        // 1. 用 CriteriaQueryLocator.locate(anchor) 获取 CriteriaQueryModel
        val model = CriteriaQueryLocator.locate(anchor) ?: return null
        // 2. CriteriaQueryConverter.convert(model) → SelectStatement
        // T-TS-07：显式传本服务所属项目——实体类/join/子查询的**类名兜底推断**按该项目读取
        // 项目级表配置（原先传 null → 取「首个打开项目」，多项目同开时表名修饰错项目）
        var statement = CriteriaQueryConverter.convert(model, project = project)
        val hostMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java)
        // 3. 参数名：从包含 CriteriaQuery 的宿主方法参数列表提取
        val paramNames = hostMethod?.parameterList?.parameters?.map { it.name } ?: emptyList()
        // T-J10：宿主方法分页参数 → LIMIT 子句（criteria 路径是语句模型，直接挂 limit）
        val pageSort = JpaQueryEnhancer.evaluatePageSort(hostMethod, null, currentDialect(), null)
        if (pageSort != null) {
            val select = statement as? SelectStatement
            val limited = pageSort.limit?.let { limit -> select?.copy(limit = limit) } ?: select
            if (limited != null) statement = limited
        }
        // CriteriaQueryConverter 解析条件树，SqlGenerator 生成的 SQL 含 ? 占位符
        val sql = cn.song.mybatisplus.visualizer.sql.SqlGenerator.generate(statement)
        val resolvedTable = model.entityClass?.let { JpaEntityResolver().resolve(it) }
        val tableName = model.entityClassName?.let { JpaEntityResolver.inferDecoratedTableName(it, project) } ?: ""
        // T-J10/J15/J22：方法级增强（Sort ORDER BY/锁/hints/返回类型 notes；LIMIT 已在语句模型级挂载，
        // 分页/排序 notes 由上方分析透传）
        val enhancedSql = applyJpaMethodEnhancements(
            hostMethod, sql, resolvedTable, native = false,
            limitAppliedInModel = true,
            pageNotes = pageSort?.notes ?: emptyList(),
        )
        val allParamNames = paramNames + (pageSort?.extraParamNames ?: emptyList())
        return buildJpaPreviewResult(statement, enhancedSql, tableName, allParamNames, hostMethod)
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
                val result = JpqlConverter.convert(named.query, table, project)
                val paramNames = extractParamNamesFromHql(named.query)
                // T-J10/J15/J22：宿主方法级增强
                val enhancedSql = applyJpaMethodEnhancements(info.method, result.sql, table, native = false)
                val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, currentDialect(), table)
                    ?.extraParamNames ?: emptyList()
                return buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = enhancedSql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames + extraParams,
                    method = info.method,
                    inClauseParams = result.inClauseParams,
                )
            }
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（hbm 纯 POJO 项目无注解可查）
            val hbm = runCatching { HbmXmlEntityResolver.findNamedQuery(info.hql, project) }.getOrNull()
            if (hbm != null) {
                return previewHbmNamedQuery(hbm, info.method)
            }
        }

        val table = resolveHqlTable(info.entityClass, info.entityClassName, info.hql)
        // Wave1 第三参 project：激活 PSI 级 JOIN ON 与子查询实体映射
        // T-HqlInhCols：@Entity(name=...) 实体别名透传，别名形态 HQL 实体名才能被表名替换
        val result = HqlConverter.convert(
            info.hql, table, project,
            entityAlias = info.entityClass?.let { runCatching { JpaMetadataReader.entityNameOf(it) }.getOrNull() },
        )
        // 参数名提取：
        // 1. 追踪 setParameter 结果（位置参数用 valueExpr 即变量名，命名参数用 paramName）
        // 2. 若为空（链式调用场景），直接从 HQL 提取
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        // T-J10/J15/J22：宿主方法级增强
        val enhancedSql = applyJpaMethodEnhancements(info.method, result.sql, table, native = false)
        val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, currentDialect(), table)
            ?.extraParamNames ?: emptyList()
        // 关键：使用 HqlConverter 转换后的 SQL 字符串（含 ? 占位符），而非从 statement 重新生成。
        return buildJpaPreviewResult(
            statement = result.statement,
            convertedSql = enhancedSql,
            tableName = table?.tableName ?: "",
            paramNames = paramNames + extraParams,
            method = info.method,
            inClauseParams = result.inClauseParams,
        )
    }

    /**
     * 原生 SQL 预览（createNativeQuery）。
     * 不做实体→表名映射，仅替换 :param → ? 并解析实体表名。
     * T-J10/J15：宿主方法级增强（Pageable/Limit → LIMIT、@Lock → FOR UPDATE、notes）。
     */
    private fun previewNativeSql(info: HqlQueryInfo): PreviewResult? {
        val sql = convertNativeSqlParameters(info.hql)
        val paramNames = info.parameters.map { if (it.isPositional) it.valueExpr else it.paramName }.ifEmpty {
            extractParamNamesFromHql(info.hql)
        }
        // 解析实体表名（用于显示）
        val tableName = resolveHqlEntityTable(info.entityClass) ?: info.entityClassName ?: ""
        // T-J10/J15/J22：方法级增强（native 路径：EntityGraph 仅注释说明）
        val enhancedSql = applyJpaMethodEnhancements(info.method, sql, null, native = true)
        val extraParams = JpaQueryEnhancer.evaluatePageSort(info.method, null, currentDialect(), null)
            ?.extraParamNames ?: emptyList()
        val allParamNames = paramNames + extraParams
        return PreviewResult(
            sql = enhancedSql,
            compactSql = enhancedSql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = allParamNames,
            paramTable = ParameterReplacer.buildParamTable(allParamNames),
            paramHints = allParamNames.map { ParamHint(quote = !isNumericParam(info.method, it)) },
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
        val result = JpqlConverter.convert(jpql, table, project)
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
     *
     * T-J21：按 [CustomDaoHqlLocator] 登记的 [DaoQueryKind] 分派——
     *  - HQL（默认）：既有 HqlConverter 管道（行为不变）；
     *  - NAMED_QUERY（findByNamedQuery 族）：hql 字段存查询名——复用 NamedQueryLocator.findByQueryName
     *    按名回查实体 @NamedQuery 的真实 JPQL 再走 JpqlConverter（参考 previewNamedQuery 既有做法）；
     *    回查失败优雅降级为可读占位（不抛异常、不出错误 SQL）；
     *  - QBC（executeFind）：可读占位「QBC/DetachedCriteria 形态暂不支持静态解析」；
     *  - ENTITY_OP（get/load/save/delete 等）：locate 不认领（无 SQL 字符串），由 preview 批量分支
     *    经 JpaCrudOperationConverter 派发，不经本方法。
     */
    fun previewCustomDaoHql(anchor: PsiElement): PreviewResult? {
        val info = CustomDaoHqlLocator.locate(anchor) ?: return null
        if (info.kind == DaoQueryKind.QBC) {
            return placeholderPreview(
                "-- QBC/DetachedCriteria 形态暂不支持静态解析（条件树运行时构建）",
            )
        }
        if (info.kind == DaoQueryKind.NAMED_QUERY) {
            val named = runCatching { NamedQueryLocator.findByQueryName(project, info.hql) }.getOrNull()
            if (named != null && named.query.isNotBlank()) {
                val entityClass = named.annotation?.let { PsiTreeUtil.getParentOfType(it, PsiClass::class.java, false) }
                val table = entityClass?.let { JpaEntityResolver().resolve(it) }
                val result = JpqlConverter.convert(named.query, table, project)
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
            // T-H3：Java @NamedQuery 未命中 → hbm 命名查询 fallback（与 previewHql 同款）
            val hbm = runCatching { HbmXmlEntityResolver.findNamedQuery(info.hql, project) }.getOrNull()
            if (hbm != null) {
                return previewHbmNamedQuery(hbm, info.method)
            }
            // 回查失败：优雅降级占位（跨文件/命名不规范），不出错误 SQL
            return placeholderPreview(
                "-- 命名查询 \"${info.hql}\"：未能回查到 @NamedQuery 定义（跨文件/命名不规范），无法静态解析 JPQL",
            )
        }
        val table = resolveHqlTable(null, info.entityClassName, info.hql)
        // Wave1 第三参 project：激活 PSI 级 JOIN ON 与子查询实体映射
        // T-HqlInhCols：别名反查命中时从解析出的 PsiClass 读实体别名透传（此链无 entityClass 入参）
        val resolvedEntityClass = table?.entityClassName?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(info.entityClassName ?: it, com.intellij.psi.search.GlobalSearchScope.allScope(project))
            }.getOrNull()
        }
        val result = HqlConverter.convert(
            info.hql, table, project,
            entityAlias = resolvedEntityClass?.let { runCatching { JpaMetadataReader.entityNameOf(it) }.getOrNull() },
        )
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

    /** T-J21：可读占位预览卡（优雅降级：无 SQL 模板时给出解释性注释，不抛异常）。 */
    private fun placeholderPreview(note: String): PreviewResult = PreviewResult(
        sql = note,
        compactSql = note,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
    )

    /**
     * T-H3：hbm 命名查询预览——按种类分派：
     *  - HQL（`<query>`）→ [HqlConverter] 管道（实体表经 [resolveHqlTable] 路径 3 hbm 映射取得）；
     *  - 原生（`<sql-query>`）→ 命名参数 `:param` → `?` 替换（与 previewNativeSql 同款）。
     *
     * 供 hbm 文件 gutter（[cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider]）
     * 与 createNamedQuery / 自定义 DAO 回查 fallback 共用。
     */
    fun previewHbmNamedQuery(query: HbmXmlMapping.NamedQuery, hostMethod: PsiMethod? = null): PreviewResult? {
        if (query.queryText.isBlank()) return null
        val paramNames = extractParamNamesFromHql(query.queryText)
        return when (query.kind) {
            HbmXmlMapping.QueryKind.NATIVE -> {
                val sql = convertNativeSqlParameters(query.queryText)
                val enhancedSql = applyJpaMethodEnhancements(hostMethod, sql, null, native = true)
                PreviewResult(
                    sql = enhancedSql,
                    compactSql = enhancedSql.replace(Regex("\\s+"), " ").trim(),
                    unresolvedParams = paramNames,
                    paramTable = ParameterReplacer.buildParamTable(paramNames),
                    paramHints = paramNames.map { ParamHint(quote = true) },
                    wrapperType = null,
                    tableName = null,
                )
            }
            HbmXmlMapping.QueryKind.HQL -> {
                // 查询名前缀（LegacyItem.findLowStockHql → LegacyItem）优先，回落 FROM 子句实体
                val entityName = query.name.substringBefore('.', "").trim()
                    .ifBlank { null } ?: extractEntityNameFromHql(query.queryText)
                val table = resolveHqlTable(null, entityName, query.queryText)
                // T-HqlInhCols：hbm 项目实体别名多由 XML <entity-name> 承载，注解读不到即 null，安全
                val result = HqlConverter.convert(query.queryText, table, project)
                val enhancedSql = applyJpaMethodEnhancements(hostMethod, result.sql, table, native = false)
                buildJpaPreviewResult(
                    statement = result.statement,
                    convertedSql = enhancedSql,
                    tableName = table?.tableName ?: "",
                    paramNames = paramNames,
                    method = hostMethod,
                    inClauseParams = result.inClauseParams,
                )
            }
        }
    }

    /** T-H2：hbm 文件 gutter 点击入口——从 `<query>`/`<sql-query>` XmlTag 构造命名查询后预览。 */
    fun previewHbmNamedQueryTag(project: Project, tag: com.intellij.psi.xml.XmlTag): PreviewResult? {
        val name = tag.getAttributeValue("name") ?: return null
        val kind = cn.song.mybatisplus.visualizer.ui.gutter.HbmNamedQueryGutterLineMarkerProvider.kindOf(tag.name)
            ?: return null
        // 文本提取复用 resolver 的解析口径（直属 XmlText、排除子标签）；tag 与 findNamedQuery 同源
        val text = tag.children.filterIsInstance<com.intellij.psi.xml.XmlText>()
            .joinToString("") { it.text }.trim()
        return previewHbmNamedQuery(HbmXmlMapping.NamedQuery(name = name, kind = kind, queryText = text))
    }

    /**
     * JpaSpecificationExecutor 调用预览（T-J5）：定位 anchor 所在方法调用 →
     * [SpecificationQueryConverter] 解析 Specification lambda/组合器 → SELECT/COUNT/DELETE + WHERE。
     *
     * 供 gutter 点击与 T-J9 调用点动作路径调用；不识别（非 executor 家族/非 Specification 实参）返回 null。
     */
    fun previewSpecification(anchor: PsiElement): PreviewResult? {
        val call = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull() ?: return null
        val preview = SpecificationQueryConverter.convert(call, effectiveReplaceParams()) ?: return null
        val result = PreviewResult(
            sql = preview.sql,
            compactSql = preview.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = preview.paramSources,
            paramTable = ParameterReplacer.buildParamTable(preview.paramSources),
            paramHints = preview.paramHints,
            wrapperType = null,
            tableName = preview.tableName,
            warning = preview.warning,
            columns = preview.columns,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * QueryByExampleExecutor 调用预览（T-J20）：定位 anchor 所在方法调用 →
     * [QueryByExampleConverter] 解析 Example.of(probe[, matcher]) → probe 非空属性等值 WHERE。
     */
    fun previewQueryByExample(anchor: PsiElement): PreviewResult? {
        val call = generateSequence(anchor) { it.parent }
            .filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull() ?: return null
        val preview = QueryByExampleConverter.convert(call, effectiveReplaceParams()) ?: return null
        val result = PreviewResult(
            sql = preview.sql,
            compactSql = preview.sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = preview.paramSources,
            paramTable = ParameterReplacer.buildParamTable(preview.paramSources),
            paramHints = preview.paramHints,
            wrapperType = null,
            tableName = preview.tableName,
            columns = preview.columns,
        )
        recordHistory(result, anchor)
        return result
    }

    /**
     * @Procedure 存储过程预览（T-J16）：repository 方法注解 → CALL 渲染
     * （procedureName/value 优先、方法名兜底）+ outputParameterName/name 引用标注。
     *
     * 供 gutter 点击与 T-J9 调用点动作路径调用；无 @Procedure 注解返回 null。
     */
    fun previewProcedure(method: PsiMethod): PreviewResult? {
        val info = JpaQueryEnhancer.procedureInfo(method) ?: return null
        val placeholders = (1..info.paramCount).joinToString(", ") { "?" }
        val sql = buildString {
            append("CALL ").append(info.procName).append('(').append(placeholders).append(')')
            info.nameRef?.let { append("\n-- @Procedure(name=\"").append(it).append("\")：引用元数据具名存储过程查询，过程名以元数据注册为准") }
            info.outputParameterName?.let { append("\n-- 输出参数：").append(it).append("（注册为 OUT 参数，执行后按名取值）") }
        }
        val result = PreviewResult(
            sql = sql,
            compactSql = sql.replace(Regex("\\s+"), " ").trim(),
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = info.procName,
        )
        recordHistory(result, method)
        return result
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
        // T-SettingsTruth P4：项目级 logicDeleteValue 覆盖（空回退全局，再兜底 "0"）
        val logicDeleteValue = effectiveLogicDeleteValue().ifBlank { "0" }
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
     * O-5：当前方言（项目级优先，回退全局）；T-DialectAutoInfer：auto 开启时智能推断。
     */
    fun currentDialect(): Dialect =
        if (effectiveDialectAuto()) DialectRegistry.effective(project = project)
        else DialectRegistry.byName(effectiveDialectName())

    /**
     * T-DialectAutoInfer：有效方言模式（项目级 dialectAuto 非 null 且启用项目设置时优先，
     * 否则全局）。
     *
     * T-TS-06：项目级分支改走 [ProjectPluginSettings.effectiveDialectAuto]（与其余 effective*
     * 同口径——回退语义内置在项目级方法里，此处仅透传）。原实现直读
     * `projectSettings?.takeIf { it.isEnabled() }?.state?.dialectAuto` 绕开了该方法，
     * 使其成为零消费的死方法（守卫断言三的限定接收者口径查获）；两式语义等价：
     * 启用且 dialectAuto 非 null → 项目值，否则回退全局。
     */
    private fun effectiveDialectAuto(): Boolean =
        effective({ runCatching { PluginSettings.getInstance().state.dialectAuto }.getOrDefault(true) },
                  { projectSettings?.effectiveDialectAuto() })

    /** O-5：有效方言名称（项目级优先，回退全局；手动模式专用）。 */
    private fun effectiveDialectName(): String =
        effective({ settings?.state?.dialect ?: PluginSettings.DEFAULT_DIALECT },
                  { projectSettings?.effectiveDialect() })

    /**
     * 是否格式化输出（true=多行缩进，false=压缩；O-5：项目级优先）。
     */
    fun formatSql(): Boolean =
        effective({ settings?.state?.formatSql ?: true },
                  { projectSettings?.effectiveFormatSql() })

    // T-SettingsTruth P4：以下 7 项直读点全部改走项目级 effective——
    // useProjectSettings 开启时项目级覆盖全局，关闭/null/空回退全局（回退语义在
    // ProjectPluginSettings.effectiveXxx 内置，此处仅透传；effectiveString 由零调用转正）。

    /** 有效参数替换开关（项目级优先，回退全局）。 */
    private fun effectiveReplaceParams(): Boolean =
        effective({ settings?.state?.replaceParams ?: true },
                  { projectSettings?.effectiveReplaceParams() })

    /** 有效逻辑删除注入开关（项目级优先，回退全局）。 */
    private fun effectiveInjectLogicDelete(): Boolean =
        effective({ settings?.state?.injectLogicDelete ?: true },
                  { projectSettings?.effectiveInjectLogicDelete() })

    /** 有效逻辑删除值（项目级优先，空回退全局）。 */
    private fun effectiveLogicDeleteValue(): String =
        effectiveString(settings?.state?.logicDeleteValue ?: "",
                        projectSettings?.effectiveLogicDeleteValue())

    /** 有效表前缀（项目级优先，空回退全局）。 */
    private fun effectiveTablePrefix(): String =
        effectiveString(settings?.state?.tablePrefix ?: "",
                        projectSettings?.effectiveTablePrefix())

    /** 有效表后缀（项目级优先，空回退全局）。 */
    private fun effectiveTableSuffix(): String =
        effectiveString(settings?.state?.tableSuffix ?: "",
                        projectSettings?.effectiveTableSuffix())

    /** 有效表名推断策略（项目级优先，空回退全局）。 */
    private fun effectiveTableNameInference(): String =
        effectiveString(settings?.state?.tableNameInference ?: PluginSettings.INFERENCE_SNAKE,
                        projectSettings?.effectiveTableNameInference())

    /** 有效忽略字段名（项目级优先，空回退全局）。 */
    private fun effectiveIgnoredFields(): String =
        effectiveString(settings?.state?.ignoredFields ?: "",
                        projectSettings?.effectiveIgnoredFields())

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
     * 否则按 entityClassName 查找 @Entity 类（含 @Entity(name=...) 实体别名反查）；
     * 最后从 HQL 的 FROM 子句提取类名查找 / hbm 映射兜底。
     *
     * T-HqlInhCols：列空结果不再直接短路——JpaEntityResolver 异常路径返回
     * 非空 TableInfo 但列为空（如继承链解析失败/抽象父类映射异常），若在此短路
     * 会跳过 hbm 兜底与别名反查，下游展开器必出「列信息未解析」降级注释；
     * 判据改为「非 null 且列非空」，列空时继续走后续路径。
     */
    private fun resolveHqlTable(entityClass: PsiClass?, entityClassName: String?, hql: String): TableInfo? {
        val resolver = JpaEntityResolver()
        // T-Fix-SettingsScope：下方 project 一律取本服务绑定的 [project]，勿用
        // openProjects.firstOrNull()——多项目同开时后者取谁取决于 IDE 打开顺序，会在 B 项目里
        // 按 A 项目的项目级表配置修饰表名，并使 GlobalSearchScope/JavaPsiFacade 落到错误的项目范围。
        val className = entityClassName ?: extractEntityNameFromHql(hql)

        // 路径 1：有 PsiClass 直接解析（JPA 注解）。列空不短路到「更弱的路径」——
        // 夹具/局部类场景路径 2/3 反而找不到该类；列空仅在「有更好的候选」时被替换：
        // 即后续路径找到的表列非空才覆盖，否则保留路径 1 结果（含表名与实体类名，
        // 展开器照常给「列信息未解析」诊断，不丢 FROM 表名替换）。
        var resolved: TableInfo? = null
        if (entityClass != null) {
            val table = resolver.resolve(entityClass)
            if (table.columns.isNotEmpty()) return table
            resolved = table
        }

        // 路径 2：按类名查找 @Entity 类（JPA 注解）；列空不短路
        if (className != null) {
            val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(className, scope)
                ?: findClassByShortName(project, className, scope)
                ?: findClassByEntityName(project, className, scope)
            if (psiClass != null) {
                val table = resolver.resolve(psiClass)
                if (table.columns.isNotEmpty()) return table
                if (resolved == null) resolved = table
            }
        }

        // 路径 3：Hibernate XML 映射文件（.hbm.xml）
        if (className != null) {
            val hbmMapping = HbmXmlEntityResolver.resolve(className, project)
            if (hbmMapping != null) {
                return convertHbmMappingToTableInfo(hbmMapping)
            }
        }

        return resolved
    }

    /**
     * T-HqlInhCols：按 @Entity(name=...) 实体别名查找实体类。
     *
     * HQL `FROM Alias` 的 Alias 可为实体名（JPA 语义）而非类名——
     * 类名快路径（findClass/findClassByShortName）未命中时按注解值反查。
     * 扫描结果（实体名 → 全限定名）缓存于 Project userData，以
     * PsiModificationTracker.modificationCount 失效（照抄 EntityClassFinder 的
     * @TableName 反查同款缓存惯例；未命中也入缓存防重复全量扫描）。
     */
    private fun findClassByEntityName(
        project: com.intellij.openapi.project.Project,
        entityName: String,
        scope: com.intellij.psi.search.GlobalSearchScope,
    ): PsiClass? {
        if (entityName.isBlank()) return null
        val current = com.intellij.psi.util.PsiModificationTracker.getInstance(project).modificationCount
        val existing = project.getUserData(ENTITY_NAME_CACHE_KEY)
        val cache = if (existing != null && existing.count == current) existing
        else EntityNameLookupCache(current, mutableMapOf()).also { project.putUserData(ENTITY_NAME_CACHE_KEY, it) }

        val key = entityName.lowercase()
        val cached = cache.names[key]
        if (cached != null) {
            val shortName = cached.substringAfterLast('.')
            return com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                .getClassesByName(shortName, scope)
                .firstOrNull { it.qualifiedName == cached }
        }
        if (cached != null || (cached == null && cache.names.containsKey(key) && cache.names[key] == null)) return null
        // 已知未命中（空串哨兵值）快速返回
        if (cache.names.containsKey(key)) return null

        // 慢路径：全量扫项目类短名，匹配 @Entity(name=...) 注解值（jakarta/javax/短名三轨）
        var matchedQName: String? = null
        val namesCache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
        for (shortName in namesCache.getAllClassNames()) {
            for (clazz in namesCache.getClassesByName(shortName, scope)) {
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                val alias = JpaMetadataReader.entityNameOf(clazz) ?: continue
                if (alias.equals(entityName, ignoreCase = true)) {
                    matchedQName = clazz.qualifiedName
                    break
                }
            }
            if (matchedQName != null) break
        }
        cache.names[key] = matchedQName ?: ""
        if (matchedQName == null) return null
        val shortName = matchedQName.substringAfterLast('.')
        return namesCache.getClassesByName(shortName, scope)
            .firstOrNull { it.qualifiedName == matchedQName }
    }

    /** 实体别名反查缓存条目：modificationCount + 实体名→全限定名（空串=已知未命中）。 */
    private data class EntityNameLookupCache(
        val count: Long,
        val names: MutableMap<String, String>,
    )

    private val ENTITY_NAME_CACHE_KEY =
        com.intellij.openapi.util.Key.create<EntityNameLookupCache>("mp-visualizer.entityNameLookupCache")

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

        /**
         * T-PreviewFast P7：最近一次预览请求的起始时刻（点击/快捷键入口记时）。三段耗时的锚点：
         * 请求 → 弹窗（[showResult] 记）→ 控制台可见（面板 embedConsole 记）。仅诊断用，
         * 无锁 volatile 读写，取不到 0 即忽略。
         */
        @Volatile
        var lastPreviewRequestMs: Long = 0L
            internal set

        /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法；单一来源，评估逻辑 [evaluateLogicDeleteState] 使用）。 */
        private const val METHOD_DISABLE_LOGIC_DEL = "disableLogicDel"
        private const val METHOD_ENABLE_LOGIC_DEL = "enableLogicDel"
        private const val METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel"
        private const val METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel"
        private const val METHOD_LOGIC_DEL_TO_ON = "logicDelToOn"
        private const val METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere"

        /** 默认租户列名（T-TenantLine）：tenantColumnName 空白时回退此值。 */
        private const val DEFAULT_TENANT_COLUMN = "tenant_id"

        /** SQL 子句关键字（detectMainAlias 探测主表别名时排除的关键字形态）。 */
        private val SQL_CLAUSE_KEYWORDS = setOf(
            "WHERE", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN", "ON", "CROSS",
            "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "UNION", "SET", "AS",
        )

        /**
         * 统一展示入口（M10-03）：恒以弹窗（[SqlPreviewDialog]）展示预览结果，
         * 供 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 与 gutter 图标点击共用（M11 起收敛为弹窗）。
         *
         * T-RefreshDbSteal F2：弹窗单例化——同项目已有预览弹窗时先 dispose 旧弹窗再开新。
         * 旧实现每次调用都 new 一个 MODELESS 弹窗且从不关闭，多弹窗各自持有 rehost 监听，
         * 互相抢占插件级共享控制台（真机 6 连抢根因）；单例化后同一时刻每个项目至多一个
         * 预览弹窗存活，旧面板的监听随 dispose 显式移除（F3），僵尸源头消灭。
         * 代价：旧弹窗的滚动/尺寸状态不保留（新弹窗重新初始化，尺寸走 DimensionService 记忆）。
         *
         * @param result null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
         * @param sourceLocation 当前代码位置（文件名:行号），来源列默认值；null 表示未知
         */
        fun showResult(project: Project, result: PreviewResult?, sourceLocation: String? = null) {
            // T-PreviewFast P7：请求→弹窗耗时（第二段锚点；后续控制台可见由面板记）
            runCatching {
                val req = lastPreviewRequestMs
                if (req > 0 && result != null) {
                    LOG.info("[T-PreviewFast] request→dialog: ${System.currentTimeMillis() - req}ms")
                }
            }
            if (result == null) {
                // T-UiNative T-UN-02：未找到可转换 Wrapper 提示改轻通知（原模态弹窗——纯告知无需打断）；
                // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
                if (!ApplicationManager.getApplication().isUnitTestMode) {
                    runCatching {
                        Notification(
                            NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("action.notFound.title"),
                            MpSqlVisualizerBundle.message("action.notFound.message"),
                            NotificationType.INFORMATION,
                        ).notify(project)
                    }
                }
                return
            }
            // 关旧开新：旧弹窗 dispose 级联释放面板（rehost 监听随之移除），再开新弹窗
            val previous = activePreviewDialogs[project]?.get()
            if (previous != null) {
                if (previous.isDisposed) {
                    LOG.info("[M36] preview dialog singleton: previous dialog already disposed")
                } else {
                    LOG.info("[M36] preview dialog singleton: closing previous dialog (id=${System.identityHashCode(previous)})")
                    runCatching { previous.doDispose() }
                }
            }
            val dialog = SqlPreviewDialog(project, result, sourceLocation)
            activePreviewDialogs[project] = java.lang.ref.WeakReference(dialog)
            dialog.show()
        }

        /** T-RefreshDbSteal F2：项目 → 当前存活的预览弹窗（弱引用——弹窗被用户关闭后不阻回收）。 */
        private val activePreviewDialogs = java.util.concurrent.ConcurrentHashMap<Project, java.lang.ref.WeakReference<SqlPreviewDialog>>()

        /** T-RefreshDbSteal F2：测试访问器——当前项目登记的存活预览弹窗（单例化断言用）。 */
        internal fun activePreviewDialogForTest(project: Project): SqlPreviewDialog? =
            activePreviewDialogs[project]?.get()

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
            // T-PreviewFast P7：三段耗时埋点起点（点击/快捷键触发即记时）——真机排障时直接给出
            // 「请求→弹窗」「弹窗→控制台可见」两段数字，取代靠多条阶段日志手工推时序
            lastPreviewRequestMs = System.currentTimeMillis()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = try {
                    ReadAction.compute<PreviewResult?, Throwable> { compute() }
                } catch (e: Throwable) {
                    null
                }
                // T-ExecSlowOps F2：确定性 SQL（无参数开关/批量/XML 模型）预览成功即后台预热
                // exec 临时文件（写盘→refresh→方言映射→findFile→填缓存槽），弹窗打开后
                // prepareDefaultConsole 命中缓存，EDT 冷路径（refresh+findFile 慢操作 SEVERE 本体）
                // 退化为兜底。独立池线程 fire-and-forget——预热含 VFS refresh 有界耗时，不得推迟
                // 弹窗；带参/批量/XML 预览 SQL 随面板勾选变化，预热内容不可信，跳过。
                if (result != null && result.previewModel == null && result.batchModel == null &&
                    result.xmlModel == null && result.fillClauses.isNullOrEmpty() &&
                    cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()
                ) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        runCatching {
                            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.prewarmExecTempFile(project, result.sql)
                        }.onFailure {
                            LOG.warn("[M36] exec prewarm dispatch failed: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater { onResult(result) }
            }
        }
    }
}
