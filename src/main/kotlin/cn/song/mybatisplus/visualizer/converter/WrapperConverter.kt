package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.EntityResolver

/**
 * Wrapper 转换器 SPI（T-M4-01，F-01/09/10/11/12 扩展点）。
 *
 * 每个 Wrapper 类型一个实现；新增类型只需实现本接口并注册进 [ConverterRegistry]，
 * UI 层（SqlPreviewService）无需改动即可接入（F-20 可扩展性要求）。
 */
interface WrapperConverter {

    /** 该转换器支持的 Wrapper 类型集合。 */
    val supportedTypes: Set<WrapperType>

    /**
     * 将 Wrapper 调用链转换为 SQL 语句模型。
     *
     * @param chain 调用链（PSI 解析产物）
     * @param table 实体解析的表信息
     * @return 语句模型（交给 SqlGenerator 渲染）
     */
    fun convert(chain: WrapperChain, table: TableInfo): SqlStatement
}

/**
 * 转换器注册表：按 Wrapper 类型分发到具体转换器。
 *
 * 新增 Wrapper 类型时在此追加实现（如 MPJ 联表、字符串版 QueryWrapper）。
 */
object ConverterRegistry {

    /** 全部已注册转换器（顺序即优先级；supportedTypes 互斥，顺序无实际影响）。 */
    val converters: List<WrapperConverter> = listOf(
        LambdaQueryConverter,
        LambdaUpdateConverter,
        LambdaDeleteConverter,
        MpjLambdaConverter,
        MpjQueryConverter,
        MpjLambdaQueryConverter,
        QueryWrapperConverter,
        UpdateWrapperConverter,
        // T-M21-08：MPJ 官方联表更新/删除（UpdateJoinWrapper / DeleteJoinWrapper）
        UpdateJoinConverter,
        DeleteJoinConverter,
    )

    /**
     * 转换调用链。
     *
     * @param chain 调用链
     * @param table 表信息
     * @return 语句模型；无匹配转换器时返回 null
     */
    fun convert(chain: WrapperChain, table: TableInfo): SqlStatement? =
        converters.firstOrNull { chain.wrapperType in it.supportedTypes }?.convert(chain, table)
}

// ---------------------------------------------------------------------------
// 共享实参解析辅助（各 converter 复用；对应 psi 层 WrapperArg 产物）
// ---------------------------------------------------------------------------

/**
 * 实参转列：ColumnArg 直接取；ValueArg(字面量) 视为字符串列名（MP 字符串字段版）。
 * 字符串列取 unquoted（如 `t.id AS device_id` 的 `t.id`），避免带引号。
 */
fun WrapperArg?.asColumn(): ColumnRef? = when (this) {
    is WrapperArg.ColumnArg -> column
    is WrapperArg.ValueArg -> (value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
    else -> null
}

/**
 * 实参转值。
 */
fun WrapperArg?.asValue(): SqlValue? = (this as? WrapperArg.ValueArg)?.value

/**
 * 为列引用补表别名（MPJ 主表场景），已有别名时保持不变。
 */
fun ColumnRef.withTableAlias(alias: String): ColumnRef =
    if (tableAlias == null) copy(tableAlias = alias) else this

/**
 * MPJ join 解析共享辅助（T-M21-08）：UpdateJoin/DeleteJoinConverter 复用。
 *
 * 复用策略说明：[MpjLambdaConverter.parseJoin] 为 private 且含 T-M21-02 扩展重载
 * （5 参右别名 / ext MFunction / 子表 MConsumer 子查询 join），不在本任务改动范围——
 * 此处实现联表更新/删除需要的**核心 ON 形态**（与 MpjLambdaConverter 既有分派对齐）：
 *  1. 三参 `leftJoin(Class, 关联表字段, 主表字段)` → ON 别名.左 = 主别名.右
 *  2. 四参带别名 `leftJoin(Class, "rel", 关联表字段, 主表字段)`
 *  3. `on(col1, col2)` 两列比较（left 主表列、right 关联表列）
 *  4. lambda ON `leftJoin(Class, on -> on.eq(...))`（含四参别名 + lambda ON）
 *  5. 字符串 SQL join `leftJoin("address t1 on t1.user_id = t.id"[, args])` 原样透传（{i} 参数替换）
 *  6. 自定义关键字 `join("FULL JOIN", condition, "sql"[, args])`
 *
 * 关联表信息：优先 psi 层解析的 [WrapperArg.EntityRefArg]（F-13 注解），否则按类名 snake_case 兜底
 * （统一入口 [EntityResolver.inferDecoratedTableName]）。
 */
internal object MpjJoinSupport {

    /** 联表方法全集（leftJoin/innerJoin/rightJoin/fullJoin + join 自定义关键字，官方 API）。 */
    val JOIN_METHODS: Set<String> = setOf("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join")

    /**
     * 解析 join 调用 → 联表子句（核心形态，见类 KDoc）。
     *
     * @param call      join 调用（psi 层已解析实参）
     * @param mainAlias 主表别名（ON 右列归属）
     */
    fun parseJoin(call: WrapperCall, mainAlias: String): JoinClause? {
        val type = when (call.methodName) {
            "leftJoin" -> JoinType.LEFT
            "innerJoin" -> JoinType.INNER
            "rightJoin" -> JoinType.RIGHT
            "fullJoin" -> JoinType.FULL
            else -> JoinType.INNER // join(自定义关键字)
        }
        val args = call.args

        // 6. 自定义关键字 join：join("FULL JOIN", condition, "address t1 on ..."[, args])
        if (call.methodName == "join") {
            val keyword = (args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            val joinSql = (args.getOrNull(2).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            return JoinClause(type, "", "", emptyList(), rawSql = renderJoinSql(joinSql, args.drop(3)), customKeyword = keyword)
        }

        // 5. 字符串 SQL join：leftJoin("address t1 on t1.user_id = t.id"[, args])——
        //    首参为含 SQL 结构（空格/括号/{i} 占位）的字符串字面量；纯类名（CamelCase，无空格）走类名形态
        val first = args.getOrNull(0)
        if (first is WrapperArg.ValueArg) {
            val literal = first.value as? SqlValue.Literal ?: return null
            val text = literal.unquoted
            if (!text.trimEnd().endsWith(".class") &&
                (text.contains(' ') || text.contains('(') || text.contains('{'))
            ) {
                val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
                val sql = (args.getOrNull(startIndex).asValue() as? SqlValue.Literal)?.unquoted ?: return null
                return JoinClause(type, "", "", emptyList(), rawSql = renderJoinSql(sql, args.drop(startIndex + 1)))
            }
        }

        val (tableInfo, className) = when (val arg = call.args.getOrNull(0)) {
            is WrapperArg.EntityRefArg -> arg.tableInfo to arg.tableInfo.entityClassName
            is WrapperArg.ValueArg -> {
                val cn = (arg.value as? SqlValue.Literal)?.raw?.removeSuffix(".class")?.trim() ?: return null
                null to cn
            }
            else -> return null
        }
        if (className.isEmpty()) return null

        // T-M19.4：子表推断走统一入口（策略 + 修饰链，全局语义）
        val tableName = tableInfo?.tableName ?: EntityResolver.inferDecoratedTableName(className)
        var alias = className.replaceFirstChar { it.lowercaseChar() }
        val onConditions = mutableListOf<Condition>()

        val rest = call.args.drop(1)
        val firstRest = rest.getOrNull(0)
        when {
            // 4. lambda ON：leftJoin(Class, on -> on.eq(...).eq(...))
            firstRest is WrapperArg.NestedArg ->
                onConditions += parseOnLambda(firstRest.chain, alias, mainAlias)

            else -> {
                var index = 0
                // 2. 四参带别名：leftJoin(Class, "rel", 关联表字段, 主表字段)
                if (firstRest is WrapperArg.ValueArg) {
                    val aliasText = (firstRest.value as? SqlValue.Literal)?.unquoted
                    if (!aliasText.isNullOrEmpty()) {
                        alias = aliasText
                        index = 1
                    }
                }
                val aliasNext = rest.getOrNull(index)
                when {
                    // 4b. 四参别名 + lambda ON：leftJoin(Class, "t1", on -> ...)
                    aliasNext is WrapperArg.NestedArg ->
                        onConditions += parseOnLambda(aliasNext.chain, alias, mainAlias)
                    // 3. on(col1, col2)：left 主表列、right 关联表列
                    aliasNext is WrapperArg.OnConditionArg ->
                        onConditions += rest.drop(index).mapNotNull { arg ->
                            (arg as? WrapperArg.OnConditionArg)?.let { oc ->
                                Condition.ColumnComparison(
                                    left = oc.left.withTableAlias(mainAlias),
                                    right = oc.right.withTableAlias(alias),
                                )
                            }
                        }
                    else -> {
                        // 1. 三参：leftJoin(Class, 关联表字段, 主表字段) → ON alias.左 = mainAlias.右
                        val left = rest.getOrNull(index) as? WrapperArg.ColumnArg
                        val right = rest.getOrNull(index + 1) as? WrapperArg.ColumnArg
                        if (left != null && right != null) {
                            onConditions += Condition.ColumnComparison(
                                left = left.column.withTableAlias(alias),
                                right = right.column.withTableAlias(mainAlias),
                            )
                        }
                    }
                }
            }
        }
        return JoinClause(type, tableName, alias, onConditions)
    }

    /**
     * lambda ON 条件解析（P13 形态）：`leftJoin(Class, on -> on.eq(...))`。
     *  - `eq/ne(col, 列|值)`：列可为方法引用（按左右归属加别名）或字符串列/表达式（原样透传）；
     *  - 右参为字符串且含 `.`（`eq("t1.workflow_id", "t.id")`）→ 字符串列引用，生成列对列比较；
     *    否则为字符串值（`eq("t2.script_status", "1")` → `= '1'`）；
     *  - `apply(sql)`：Raw 条件原样透传。
     */
    private fun parseOnLambda(chain: WrapperChain, alias: String, mainAlias: String): List<Condition> =
        chain.calls.mapNotNull { call ->
            when (call.methodName) {
                "eq", "ne" -> {
                    val left = call.args.getOrNull(0)
                    val leftCol = when (left) {
                        is WrapperArg.ColumnArg -> left.column.withTableAlias(alias)
                        is WrapperArg.ValueArg ->
                            (left.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
                        else -> null
                    } ?: return@mapNotNull null
                    val right = call.args.getOrNull(1)
                    when (right) {
                        is WrapperArg.ColumnArg ->
                            Condition.ColumnComparison(
                                left = leftCol,
                                right = right.column.withTableAlias(mainAlias),
                            )
                        is WrapperArg.ValueArg -> {
                            val literal = right.value as? SqlValue.Literal
                            if (literal != null && literal.unquoted.contains('.')) {
                                // 字符串列引用形态（"t.id"）→ 列对列比较
                                Condition.ColumnComparison(
                                    left = leftCol,
                                    right = ColumnRef(columnName = literal.unquoted),
                                )
                            } else {
                                val value = right.asValue() ?: return@mapNotNull null
                                Condition.Simple(leftCol, if (call.methodName == "ne") "<>" else "=", value)
                            }
                        }
                        else -> {
                            val value = right?.asValue() ?: return@mapNotNull null
                            Condition.Simple(leftCol, if (call.methodName == "ne") "<>" else "=", value)
                        }
                    }
                }
                "apply" -> {
                    val raw = (call.args.firstOrNull().asValue() as? SqlValue.Literal)?.unquoted
                    if (raw != null) Condition.Raw(raw) else null
                }
                else -> null
            }
        }

    /** 字符串 join 的 `{i}` 绑定参数替换（官方示例：`leftJoin("(select ... where addr.id = {0})", 1)`）。 */
    private fun renderJoinSql(template: String, values: List<WrapperArg>): String {
        var sql = template
        for ((index, value) in values.withIndex()) {
            sql = sql.replace("{$index}", renderJoinValue(value.asValue() ?: continue))
        }
        return sql
    }

    /** join 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderJoinValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    /** 首参是否为布尔条件前缀（true/false 字面量或运行时布尔表达式占位符）；列引用（ColumnArg）恒为列。 */
    private fun isFlagArgument(arg: WrapperArg): Boolean {
        if (arg is WrapperArg.ColumnArg) return false
        val value = arg.asValue()
        return when (value) {
            is SqlValue.Literal -> value.unquoted == "true" || value.unquoted == "false"
            is SqlValue.Placeholder -> true
            else -> false
        }
    }
}
