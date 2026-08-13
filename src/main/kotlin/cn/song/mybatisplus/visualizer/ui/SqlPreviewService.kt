package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.ConverterRegistry
import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.BatchOperationConverter
import cn.song.mybatisplus.visualizer.psi.WrapperCache
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethodCallExpression

/**
 * SQL 预览服务（M3-01 整合层，F-07 统一入口）。
 *
 * 职责：把编辑器中的任意 PSI 元素转换为可展示的 [PreviewResult]。
 * 集成链路：WrapperLocator 定位 → ConverterRegistry 分发（SPI）→ SqlGenerator 生成 →
 * ParameterReplacer 参数对照 → WrapperCache 缓存（F-20）。
 *
 * M5 起读取 [PluginSettings] 配置：方言（分页子句）、格式化开关（多行/单行）。
 * 轻服务（M16）：`@Service(Service.Level.PROJECT)` 注解声明，无需 plugin.xml `<projectService>` 注册
 * （IDE 自动发现；构造函数仅依赖 Project，满足轻服务条件），获取方式不变（project.getService）。
 */
@Service(Service.Level.PROJECT)
class SqlPreviewService(private val project: Project) {

    /** 文件级解析缓存（F-20）：文件内容变更（modificationStamp）即失效。 */
    private val cache = WrapperCache()

    /**
     * T-M19.4（用户实测：表名配置修改后预览不生效——缓存按文件戳失效，设置变更不触发）：
     * 影响解析结果的表相关设置签名，作为 [WrapperCache] 缓存维度。
     * 字段：推断策略 / 忽略字段名 / 添加表前缀 / 添加表后缀 / 移除表前缀 / 移除表后缀。
     */
    private fun tableConfigSignature(): String {
        val s = settings?.state
        return listOf(
            s?.tableNameInference ?: PluginSettings.INFERENCE_SNAKE,
            s?.ignoredFields ?: "",
            s?.tablePrefix ?: "",
            s?.tableSuffix ?: "",
            s?.removeTablePrefix ?: "",
            s?.removeTableSuffix ?: "",
        ).joinToString("|")
    }

    /**
     * 全局配置（测试环境未注册 service 时为 null，使用默认值）。
     */
    private val settings: PluginSettings? =
        runCatching { PluginSettings.getInstance() }.getOrNull()

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
            val table = located.table ?: return null
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
            val finalStatement = if (settings?.state?.injectLogicDelete ?: true) {
                injectLogicDeleteCondition(table, statement, logicDelete)
            } else {
                statement
            }
            val result = buildPreviewResult(located.chain.wrapperType, table, finalStatement)
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
        val batch = batchCall?.let { BatchOperationConverter.convert(it) } ?: return null
        return PreviewResult(
            sql = batch.sql,
            compactSql = batch.sql,
            unresolvedParams = emptyList(),
            paramTable = batch.paramGroups,
            wrapperType = null,
            tableName = batch.tableName,
        )
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
        )
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
     *  - 主表：mainActive 时 WHERE 追加 `deleted = 0`（保持既有无别名格式）；
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
        return when (statement) {
            is SelectStatement -> {
                var conditions = statement.conditions
                if (state.mainActive) {
                    table.logicDeleteColumn?.let { column ->
                        conditions = conditions + Condition.Simple(
                            ColumnRef(columnName = column), "=", SqlValue.Literal("0"),
                        )
                    }
                }
                // 副表注入：ON 追加 / WHERE 末尾（子查询形态 tableName 以 '(' 开头无 logicDeleteColumn，天然跳过）
                val subWhere = mutableListOf<Condition>()
                val joins = statement.joins.map { join ->
                    val column = join.logicDeleteColumn ?: return@map join
                    if (!state.subActive) return@map join
                    val cond = Condition.Simple(
                        ColumnRef(tableAlias = join.alias, columnName = column), "=", SqlValue.Literal("0"),
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
                        ColumnRef(columnName = column), "=", SqlValue.Literal("0"),
                    ),
                )
            }
            else -> statement
        }
    }

    /** 单个条件节点 → 条件视图（自身 toggle + 嵌套 Group 子节点递归）。 */
    private fun Condition.toConditionViews(): List<ConditionView> {
        val own = toggle?.let {
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

    /**
     * 当前方言（配置未加载时默认 MySQL）。
     */
    fun currentDialect(): Dialect = settings?.let { DialectRegistry.byName(it.state.dialect) } ?: DialectRegistry.byName(null)

    /**
     * 是否格式化输出（true=多行缩进，false=单行）。
     */
    fun formatSql(): Boolean = settings?.state?.formatSql ?: true

    /**
     * 风险警示（F-09/F-10）：UPDATE / DELETE 无 WHERE 条件时提示全表操作。
     */
    private fun buildWarning(statement: SqlStatement): String? = when (statement) {
        is UpdateStatement -> if (statement.conditions.isEmpty()) "⚠ 无 WHERE 条件，将更新全表数据！" else null
        is DeleteStatement -> if (statement.conditions.isEmpty()) "⚠ 无 WHERE 条件，将删除全表数据！" else null
        else -> null
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
     * 清空缓存（编辑器文件切换/项目关闭时由持有方调用）。
     */
    fun clearCache() = cache.clear()

    companion object {
        /** 逻辑删除控制方法名（T-M20-05，MPJ 官方六方法；单一来源，评估逻辑 [evaluateLogicDeleteState] 使用）。 */
        private const val METHOD_DISABLE_LOGIC_DEL = "disableLogicDel"
        private const val METHOD_ENABLE_LOGIC_DEL = "enableLogicDel"
        private const val METHOD_DISABLE_SUB_LOGIC_DEL = "disableSubLogicDel"
        private const val METHOD_ENABLE_SUB_LOGIC_DEL = "enableSubLogicDel"
        private const val METHOD_LOGIC_DEL_TO_ON = "logicDelToOn"
        private const val METHOD_LOGIC_DEL_TO_WHERE = "logicDelToWhere"

        /**
         * 统一展示入口（M10-03）：恒以弹窗（[SqlPreviewDialog]）展示预览结果，
         * 供 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 与 gutter 图标点击共用（M11 起收敛为弹窗）。
         *
         * @param result null 表示未找到可转换的 Wrapper → 弹友好提示（不抛异常）
         */
        fun showResult(project: Project, result: PreviewResult?) {
            if (result == null) {
                Messages.showInfoMessage(
                    project,
                    MpSqlVisualizerBundle.message("action.notFound.message"),
                    MpSqlVisualizerBundle.message("action.notFound.title"),
                )
                return
            }
            SqlPreviewDialog(project, result).show()
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
