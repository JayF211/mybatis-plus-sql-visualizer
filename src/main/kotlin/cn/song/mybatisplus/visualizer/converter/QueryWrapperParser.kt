package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
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

    /**
     * 解析调用链为 SELECT 语句模型。
     *
     * @param chain PSI 解析产物
     * @return 可直接交给 SqlGenerator 的语句模型
     */
    fun parse(chain: WrapperChain): SelectStatement {
        val selectColumns = mutableListOf<ColumnRef>()
        val conditions = mutableListOf<Condition>()
        val groupBy = mutableListOf<ColumnRef>()
        var having: Condition? = null
        val orderBy = mutableListOf<OrderByItem>()
        var limit: LimitClause? = null
        var distinct = false
        var orPending = false

        for (rawCall in chain.calls) {
            // 布尔条件重载（P02）：eq(boolean, col, val) 首参为条件标志；字面量 false → 整体忽略
            val call = if (rawCall.methodName in CONDITION_METHODS) rawCall.skipConditionFlag() else rawCall
            if (call.args.isEmpty() && call.methodName in CONDITION_METHODS) continue

            when (call.methodName) {
                // ---------- SELECT 列 ----------
                "select" -> selectColumns += columnArgs(call)
                "selectMax" -> columnArgs(call).firstOrNull()?.let { selectColumns += it.copy(function = "MAX") }
                "selectCount" -> selectColumns += parseSelectCount(call)
                "selectFunc" -> selectColumns += parseSelectFunc(call)
                "distinct" -> distinct = true

                // ---------- 二元比较 ----------
                "eq" -> orPending = addConditionFlagged(conditions, simple(call, "="), orPending)
                "ne" -> orPending = addConditionFlagged(conditions, simple(call, "!="), orPending)
                "gt" -> orPending = addConditionFlagged(conditions, simple(call, ">"), orPending)
                "ge" -> orPending = addConditionFlagged(conditions, simple(call, ">="), orPending)
                "lt" -> orPending = addConditionFlagged(conditions, simple(call, "<"), orPending)
                "le" -> orPending = addConditionFlagged(conditions, simple(call, "<="), orPending)

                // ---------- 模糊 ----------
                "like" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.ANYWHERE), orPending)
                "notLike" -> orPending = addConditionFlagged(conditions, like(call, negated = true, LikePosition.ANYWHERE), orPending)
                "likeLeft" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.LEFT), orPending)
                "likeRight" -> orPending = addConditionFlagged(conditions, like(call, negated = false, LikePosition.RIGHT), orPending)

                // ---------- 范围 ----------
                "in" -> orPending = addConditionFlagged(conditions, inList(call, negated = false), orPending)
                "notIn" -> orPending = addConditionFlagged(conditions, inList(call, negated = true), orPending)
                "between" -> orPending = addConditionFlagged(conditions, between(call, negated = false), orPending)
                "notBetween" -> orPending = addConditionFlagged(conditions, between(call, negated = true), orPending)

                // ---------- 空值 ----------
                "isNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NULL"), orPending)
                "isNotNull" -> orPending = addConditionFlagged(conditions, unary(call, "IS NOT NULL"), orPending)

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
        // 递归解析嵌套链的条件列表，按连接符组装为分组
        val children = parse(nested.chain).conditions
        if (children.isEmpty()) return null
        return Condition.Group(connector, children)
    }

    /** HAVING：M1 支持单条件（取嵌套链/条件的第一个条件）。 */
    private fun parseHaving(call: WrapperCall): Condition? {
        val nested = call.args.firstOrNull() as? WrapperArg.NestedArg
        return nested?.let { parse(it.chain).conditions.firstOrNull() }
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

    /**
     * 处理条件布尔重载（清单 P02）：`eq(boolean, col, val)` / `like(boolean, col, val)` 等。
     *
     * 首参为布尔条件标志时移除（converter 无法静态求值运行时布尔 → 默认保留条件）；
     * 首参为字面量 `false` 时整体忽略该条件。
     */
    private fun WrapperCall.skipConditionFlag(): WrapperCall {
        val first = args.getOrNull(0)
        if (args.size < 3) return this
        if (first is WrapperArg.ColumnArg || first is WrapperArg.NestedArg || first is WrapperArg.OnConditionArg) {
            return this
        }
        if (first is WrapperArg.ValueArg) {
            val text = (first.value as? SqlValue.Literal)?.unquoted
            return if (text == "false") {
                WrapperCall(methodName, emptyList())
            } else {
                WrapperCall(methodName, args.drop(1))
            }
        }
        // 运行时布尔表达式（变量/方法调用等）→ 保留条件，移除标志位
        return WrapperCall(methodName, args.drop(1))
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
     */
    private fun parseApply(call: WrapperCall): Condition? {
        val args = call.args
        if (args.isEmpty()) return null
        val first = args[0]
        val firstLiteral = (first.asValue() as? SqlValue.Literal)?.unquoted
        // 布尔条件前缀：args>=2 且首参为布尔字面量或运行时表达式（非字符串字面量）
        val startIndex = if (args.size >= 2 && (firstLiteral == "true" || firstLiteral == "false" || first !is WrapperArg.ValueArg)) {
            1
        } else {
            0
        }
        if (args.size <= startIndex) return null
        val template = (args.getOrNull(startIndex).asValue() as? SqlValue.Literal)?.unquoted ?: return null
        // 布尔前缀为字面量 false → 条件整体禁用
        if (startIndex == 1 && firstLiteral == "false") return null
        val values = args.drop(startIndex + 1).mapNotNull { it.asValue() }
        var sql = template
        for ((index, value) in values.withIndex()) {
            sql = sql.replace("{$index}", renderFuncValue(value))
        }
        return Condition.Raw(sql)
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
