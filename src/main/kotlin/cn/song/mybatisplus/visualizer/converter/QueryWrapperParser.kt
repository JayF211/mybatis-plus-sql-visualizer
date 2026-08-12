package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.sql.SqlGenerator

/**
 * LambdaQueryWrapper → SelectStatement 转换器（M1 核心，F-01）。
 *
 * 遍历 [WrapperChain.calls]，按方法名分派构建 SELECT 语句的各个部分。
 * 纯逻辑、无 PSI 依赖，可独立单测。
 *
 * @param table        实体表信息
 * @param columnPrefix 列引用表别名前缀（MPJ 联表场景传主表别名 "t"；单表查询为 null）
 *
 * 已知简化（后续里程碑补齐）：
 *  - `or()` 无参调用（切换连接符）暂不建模，M1 条件间为隐式 AND
 *  - `having` 仅支持单条件
 *  - 集合实参（`in(column, collection)`）由 psi 层展开为多值
 */
class QueryWrapperParser(
    private val table: TableInfo,
    private val columnPrefix: String? = null,
) {

    /** 动态条件 toggle id 序号（M12，pre-order 递增，同一 parse 树内稳定）。 */
    private var toggleSeq = 0

    /**
     * 解析调用链为 SELECT 语句模型。
     *
     * @param chain PSI 解析产物
     * @return 可直接交给 SqlGenerator 的语句模型
     */
    fun parse(chain: WrapperChain): SelectStatement {
        toggleSeq = 0
        return parseInternal(chain)
    }

    /** 递归解析体：顶层 parse 重置 toggleSeq，嵌套调用走本方法（id 延续同一序列）。 */
    private fun parseInternal(chain: WrapperChain): SelectStatement {
        val selectColumns = mutableListOf<ColumnRef>()
        val conditions = mutableListOf<Condition>()
        val groupBy = mutableListOf<ColumnRef>()
        var having: Condition? = null
        val orderBy = mutableListOf<OrderByItem>()
        var limit: LimitClause? = null
        var distinct = false
        var orPending = false

        for (rawCall in chain.calls) {
            // 布尔条件重载（P02/M12）：eq(boolean, col, val) 首参为布尔标志 →
            // 剥离标志并固化为动态条件 toggle 元数据（不再丢弃 false 条件，改由生成层默认排除）
            val analysis = rawCall.analyzeFlag()
            val call = analysis?.call ?: rawCall
            if (call.args.isEmpty() && call.methodName in CONDITION_METHODS) continue

            when (call.methodName) {
                // ---------- SELECT 列 ----------
                "select" -> selectColumns += columnArgs(call)
                "selectMax" -> columnArgs(call).firstOrNull()?.let { selectColumns += it.copy(function = "MAX") }
                "selectCount" -> selectColumns += parseSelectCount(call)
                "selectFunc" -> selectColumns += parseSelectFunc(call)
                "distinct" -> distinct = true

                // ---------- 二元比较 ----------
                "eq" -> orPending = addConditionFlagged(conditions, simple(call, "=").flagged(analysis), orPending)
                "ne" -> orPending = addConditionFlagged(conditions, simple(call, "!=").flagged(analysis), orPending)
                "gt" -> orPending = addConditionFlagged(conditions, simple(call, ">").flagged(analysis), orPending)
                "ge" -> orPending = addConditionFlagged(conditions, simple(call, ">=").flagged(analysis), orPending)
                "lt" -> orPending = addConditionFlagged(conditions, simple(call, "<").flagged(analysis), orPending)
                "le" -> orPending = addConditionFlagged(conditions, simple(call, "<=").flagged(analysis), orPending)

                // ---------- 模糊 ----------
                "like" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.ANYWHERE).flagged(analysis), orPending)
                "notLike" -> orPending = addConditionFlagged(conditions, like(call, negated = true, LikePosition.ANYWHERE).flagged(analysis), orPending)
                "likeLeft" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.LEFT).flagged(analysis), orPending)
                "likeRight" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.RIGHT).flagged(analysis), orPending)

                // ---------- 范围 ----------
                "in" -> orPending = addConditionFlagged(conditions, inList(call, negated = false).flagged(analysis), orPending)
                "notIn" -> orPending = addConditionFlagged(conditions, inList(call, negated = true).flagged(analysis), orPending)
                "between" -> orPending = addConditionFlagged(conditions, between(call, negated = false).flagged(analysis), orPending)
                "notBetween" -> orPending = addConditionFlagged(conditions, between(call, negated = true).flagged(analysis), orPending)

                // ---------- 空值 ----------
                "isNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NULL").flagged(analysis), orPending)
                "isNotNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NOT NULL").flagged(analysis), orPending)

                // ---------- 自定义片段 ----------
                "apply" -> orPending = addConditionFlagged(conditions, parseApply(call), orPending)

                // ---------- 结构 ----------
                "groupBy" -> groupBy += columnArgs(call)
                "having" -> having = parseHaving(call)
                "orderByAsc" -> orderBy += orderItems(call, asc = true)
                "orderByDesc" -> orderBy += orderItems(call, asc = false)
                "orderBy" -> orderBy += orderItems(call, asc = true)
                "last" -> limit = parseLimit(call)

                // ---------- 逻辑分组 ----------
                // 无参 or()（P14）：与前一条件组成 OR 分组（分隔符）；有参 and/or → 嵌套 lambda 分组
                "or" -> if (call.args.isEmpty()) {
                    orPending = true
                } else {
                    orPending = addConditionFlagged(conditions, parseGroup(call), orPending)
                }
                "and" -> orPending = addConditionFlagged(conditions, parseGroup(call), orPending)

                // 其他方法（not/嵌套条件等）暂忽略，保证健壮性
            }
        }

        return SelectStatement(
            table = table,
            selectColumns = selectColumns,
            distinct = distinct,
            conditions = conditions,
            groupBy = groupBy,
            having = having,
            orderBy = orderBy,
            limit = limit,
        )
    }

    /**
     * 解析 selectCount(Entity::getX, VO::getY) → COUNT(列) [AS 别名]。
     *
     * 第一参为列（带主表前缀）；第二参为 [WrapperArg.AliasArg]（VO 属性名，保留驼峰）或字符串别名。
     */
    private fun parseSelectCount(call: WrapperCall): List<ColumnRef> {
        val column = columnArgs(call).firstOrNull() ?: return emptyList()
        val alias = when (val arg = call.args.getOrNull(1)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        }
        return listOf(if (alias != null) column.copy(function = "COUNT", alias = alias) else column.copy(function = "COUNT"))
    }

    /**
     * 解析 selectFunc(模板, func -> func.accept(列).values(值), 别名) → 完整聚合列（清单 P09）。
     *
     * 模板规则（对齐 MPJ）：
     *  - `%s`：列占位符，按 [WrapperArg.FuncArg.columns] 顺序替换（列带主表别名前缀）
     *  - `{0}`/`{1}`：绑定参数占位符，按 [WrapperArg.FuncArg.values] 顺序替换（字面量原样、运行时值渲染为 `?`）
     */
    private fun parseSelectFunc(call: WrapperCall): List<ColumnRef> {
        val template = (call.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return emptyList()
        val func = call.args.getOrNull(1) as? WrapperArg.FuncArg ?: return emptyList()
        val alias = (call.args.getOrNull(2).asValue() as? SqlValue.Literal)?.unquoted

        var expr = template
        // %s 列占位替换（逐列、带主表别名前缀）
        for (col in func.columns) {
            if (!expr.contains("%s")) break
            expr = expr.replaceFirst("%s", columnPrefix?.let { col.withTableAlias(it) }?.qualifiedName ?: col.qualifiedName)
        }
        // {0}/{1} 绑定参数替换
        for ((index, value) in func.values.withIndex()) {
            expr = expr.replace("{$index}", renderFuncValue(value))
        }
        if (expr.contains("%s")) return emptyList() // 列数不足，无法完整渲染
        return listOf(ColumnRef(columnName = expr, rawExpr = expr, alias = alias))
    }

    /** selectFunc 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderFuncValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    // ------------------------------------------------------------------
    // 各操作解析
    // ------------------------------------------------------------------

    /** 二元比较：`column op value`。 */
    private fun simple(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val value = argAsValue(call.args.getOrNull(1)) ?: return null
        return Condition.Simple(column, op, value)
    }

    /** LIKE 条件。 */
    private fun like(call: WrapperCall, negated: Boolean, position: LikePosition): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val value = argAsValue(call.args.getOrNull(1)) ?: return null
        return Condition.Like(column, negated, value, position)
    }

    /** IN 列表条件：`column IN (v1, v2, ...)`。 */
    private fun inList(call: WrapperCall, negated: Boolean): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val values = call.args.drop(1).mapNotNull { argAsValue(it) }
        if (values.isEmpty()) return null
        return Condition.In(column, negated, values)
    }

    /** BETWEEN 条件。 */
    private fun between(call: WrapperCall, negated: Boolean): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        val lo = argAsValue(call.args.getOrNull(1)) ?: return null
        val hi = argAsValue(call.args.getOrNull(2)) ?: return null
        return Condition.Between(column, negated, lo, hi)
    }

    /** 一元条件：IS NULL / IS NOT NULL。 */
    private fun unary(call: WrapperCall, op: String): Condition? {
        val column = argAsColumn(call.args.firstOrNull()) ?: return null
        return Condition.Unary(column, op)
    }

    /** 逻辑分组：and(lambda) / or(lambda) / and(wrapper) / or(wrapper)。 */
    private fun parseGroup(call: WrapperCall): Condition? {
        val connector = if (call.methodName == "or") Connector.OR else Connector.AND
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg ?: return null
        // 递归解析嵌套链的条件列表，按连接符组装为分组（parseInternal 延续 toggle id 序列）
        val children = parseInternal(nested.chain).conditions
        if (children.isEmpty()) return null
        return Condition.Group(connector, children)
    }

    /** HAVING：M1 支持单条件（取嵌套链/条件的第一个条件）。 */
    private fun parseHaving(call: WrapperCall): Condition? {
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg
        return nested?.let { parseInternal(it.chain).conditions.firstOrNull() }
    }

    /** ORDER BY 项列表；支持 `orderByAsc("alias", Entity::getX)` 字符串别名前缀（清单 P11）。 */
    private fun orderItems(call: WrapperCall, asc: Boolean): List<OrderByItem> {
        val first = call.args.getOrNull(0)
        // 形式一：orderByAsc("alias", Entity::getX) —— 第一参为表别名
        if (first is WrapperArg.ValueArg && call.args.size >= 2) {
            val alias = (first.value as? SqlValue.Literal)?.unquoted
            if (!alias.isNullOrEmpty()) {
                return call.args.drop(1)
                    .mapNotNull { (it as? WrapperArg.ColumnArg)?.column?.withTableAlias(alias) }
                    .map { OrderByItem(it, asc) }
            }
        }
        // 形式二：orderByDesc(Entity::getX) / 字符串列
        return columnArgs(call).map { OrderByItem(it, asc) }
    }

    /** LIMIT 解析：last("limit 10") / last("limit 5, 10")（MP 语义：offset, count）。 */
    private fun parseLimit(call: WrapperCall): LimitClause? {
        val value = argAsValue(call.args.firstOrNull()) as? SqlValue.Literal ?: return null
        val text = value.unquoted.trim()
        val limitMatch = Regex("""(?i)limit\s+(\d+)(?:\s*,\s*(\d+))?""").find(text) ?: return null
        val first = limitMatch.groupValues[1].toLongOrNull() ?: return null
        val second = limitMatch.groupValues[2].toLongOrNull()
        // "limit 5, 10" → offset=5, count=10；"limit 10" → count=10
        return if (second != null) {
            LimitClause(offset = first, count = second)
        } else {
            LimitClause(count = first)
        }
    }

    // ------------------------------------------------------------------
    // 参数辅助
    // ------------------------------------------------------------------

    /** 可空条件安全添加辅助（解析失败返回 null 时静默跳过）。 */
    private fun MutableList<Condition>.addNotNull(condition: Condition?) {
        if (condition != null) add(condition)
    }

    // ------------------------------------------------------------------
    // 条件布尔重载 / 无参 or() / apply（清单 P02/P10/P14）
    // ------------------------------------------------------------------

    /** 支持布尔条件重载的方法集合（eq/like/in/between 等）。 */
    private val CONDITION_METHODS = setOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "likeLeft", "likeRight",
        "in", "notIn", "between", "notBetween",
        "isNull", "isNotNull",
    )

    /** 一元条件方法（isNull/isNotNull 布尔重载形态为 [flag, col] 2 参，区别于 3 参二元方法）。 */
    private val UNARY_METHODS = setOf("isNull", "isNotNull")

    /**
     * 布尔条件重载分析结果：剥离布尔标志后的调用 + 动态条件 toggle 预置信息（默认勾选态/标志来源）。
     */
    private data class FlagAnalysis(
        val call: WrapperCall,
        val flagSource: String?,
        val defaultEnabled: Boolean,
    )

    /**
     * 分析条件调用的布尔条件重载（P02/M12）：首参为布尔标志 → 剥离并固化动态条件元数据。
     * 非布尔重载（普通条件/其他方法）返回 null。
     *
     * 标志判定：一元方法（isNull/isNotNull）标志形态为 [flag, col]（2 参）；
     * 其余为 [flag, col, val...]（≥3 参）。首参为 ColumnArg 等列引用时恒非标志。
     *
     * M13 简化：不再识别判空语义（isNotBlank/isNotNull 等）、不做静态求值，
     * 默认勾选态仅按字面量 true/false 判定，运行时布尔一律默认勾选（复选框纯手动）。
     */
    private fun WrapperCall.analyzeFlag(): FlagAnalysis? {
        if (methodName !in CONDITION_METHODS) return null
        val first = args.getOrNull(0)
        if (first is WrapperArg.ColumnArg || first is WrapperArg.NestedArg || first is WrapperArg.OnConditionArg) return null
        val firstValue = first.asValue() ?: return null
        val flaggedArity = if (methodName in UNARY_METHODS) args.size == 2 else args.size >= 3
        if (!flaggedArity) return null

        val flagText = when (firstValue) {
            is SqlValue.Literal -> firstValue.unquoted
            is SqlValue.Placeholder -> firstValue.source
        }
        val defaultEnabled = (firstValue as? SqlValue.Literal)?.unquoted != "false"
        return FlagAnalysis(WrapperCall(methodName, args.drop(1)), flagText, defaultEnabled)
    }

    /**
     * 为条件挂载动态条件元数据（值来源/标签由条件模型计算，id 按 pre-order 递增）。
     */
    private fun Condition.bindToggle(analysis: FlagAnalysis): Condition {
        val toggle = ConditionToggle(
            id = "c${toggleSeq++}",
            flagSource = analysis.flagSource,
            defaultEnabled = analysis.defaultEnabled,
            label = SqlGenerator.renderConditionText(this),
            valueSource = valueSourceOf(this),
        )
        return withToggle(toggle)
    }

    /** 条件挂载动态条件元数据（M12）：分析结果非 null 且条件非 null 时挂载。 */
    private fun Condition?.flagged(analysis: FlagAnalysis?): Condition? =
        if (analysis == null) this else this?.bindToggle(analysis)

    /**
     * 条件关联的未解析参数来源：单值占位符条件（Simple/Like）取占位符 source，供面板值输入框绑定；
     * 多值/无值条件（in/between/一元/raw/分组）第一版无独立值输入框（checkbox-only）。
     */
    private fun valueSourceOf(condition: Condition): String? = when (condition) {
        is Condition.Simple -> (condition.value as? SqlValue.Placeholder)?.source
        is Condition.Like -> (condition.pattern as? SqlValue.Placeholder)?.source
        else -> null
    }

    /**
     * 添加条件并维护 orPending 状态：条件成功加入后 orPending 复位（无参 or() 已被消费），
     * 条件解析失败（null）时保持 orPending（后续条件仍与 or() 前的条件组合）。
     */
    private fun addConditionFlagged(
        conditions: MutableList<Condition>,
        condition: Condition?,
        orPending: Boolean,
    ): Boolean {
        if (condition == null) return orPending
        conditions.addCondition(condition, orPending)
        return false
    }

    /**
     * 添加条件；orPending 为 true 时与上一条件组成 OR 分组（无参 or() 分隔符，清单 P14）。
     */
    private fun MutableList<Condition>.addCondition(condition: Condition, orPending: Boolean) {
        if (orPending && isNotEmpty()) {
            val prev = removeAt(size - 1)
            add(Condition.Group(Connector.OR, listOf(prev, condition)))
        } else {
            add(condition)
        }
    }

    /**
     * 解析 apply（清单 P10）：
     *  - `apply("1 = 0")` → 原样 SQL 片段
     *  - `apply(condition, "(rel.a, rel.id) > ({0}, {1})", a, b)` → 布尔前缀 + `{i}` 参数替换
     *  - `apply(sql)`（sql 为运行时变量）→ 无法静态渲染，忽略
     *
     * M12：布尔前缀不再丢弃 false 条件，而是固化为动态条件 toggle（默认排除，生成层过滤）。
     */
    private fun parseApply(call: WrapperCall): Condition? {
        val args = call.args
        if (args.isEmpty()) return null
        val first = args[0]
        // 布尔条件前缀：args>=2 且首参为布尔字面量（true/false）或运行时布尔表达式（占位符/非值实参）
        val flagValue = first.asValue()
        val hasFlag = args.size >= 2 && when {
            flagValue is SqlValue.Literal -> flagValue.unquoted == "true" || flagValue.unquoted == "false"
            flagValue is SqlValue.Placeholder -> true
            first !is WrapperArg.ValueArg -> true
            else -> false
        }
        val startIndex = if (hasFlag) 1 else 0
        if (args.size <= startIndex) return null
        val template = (args.getOrNull(startIndex).asValue() as? SqlValue.Literal)?.unquoted ?: return null
        val values = args.drop(startIndex + 1).mapNotNull { it.asValue() }
        var sql = template
        for ((index, value) in values.withIndex()) {
            sql = sql.replace("{$index}", renderFuncValue(value))
        }
        val toggle = if (hasFlag) {
            val flagSource = when (val f = first) {
                is WrapperArg.ValueArg -> when (val v = f.value) {
                    is SqlValue.Literal -> v.unquoted
                    is SqlValue.Placeholder -> v.source
                }
                else -> null
            }
            ConditionToggle(
                id = "c${toggleSeq++}",
                flagSource = flagSource,
                defaultEnabled = flagSource != "false",
                label = SqlGenerator.renderConditionText(Condition.Raw(sql)),
            )
        } else {
            null
        }
        return Condition.Raw(sql, toggle = toggle)
    }

    /** 取调用中所有列实参（带主表别名前缀）。 */
    private fun columnArgs(call: WrapperCall): List<ColumnRef> =
        call.args.mapNotNull { (it as? WrapperArg.ColumnArg)?.column }
            .map { columnPrefix?.let { p -> it.withTableAlias(p) } ?: it }

    /** 实参转列：Lambda 列（ColumnArg）补主表别名前缀；字符串列名（ValueArg）原样（MPJQuery 已含别名）。 */
    private fun argAsColumn(arg: WrapperArg?): ColumnRef? = when (arg) {
        is WrapperArg.ColumnArg -> columnPrefix?.let { arg.column.withTableAlias(it) } ?: arg.column
        is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
        else -> null
    }

    /** 实参转值。 */
    private fun argAsValue(arg: WrapperArg?): SqlValue? = arg.asValue()
}
