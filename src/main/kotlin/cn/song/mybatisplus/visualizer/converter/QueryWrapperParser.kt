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

        for (call in chain.calls) {
            when (call.methodName) {
                // ---------- SELECT 列 ----------
                "select" -> selectColumns += columnArgs(call)

                // ---------- 二元比较 ----------
                "eq" -> conditions.addNotNull(simple(call, "="))
                "ne" -> conditions.addNotNull(simple(call, "!="))
                "gt" -> conditions.addNotNull(simple(call, ">"))
                "ge" -> conditions.addNotNull(simple(call, ">="))
                "lt" -> conditions.addNotNull(simple(call, "<"))
                "le" -> conditions.addNotNull(simple(call, "<="))

                // ---------- 模糊 ----------
                "like" -> conditions.addNotNull(like(call, negated = false, LikePosition.ANYWHERE))
                "notLike" -> conditions.addNotNull(like(call, negated = true, LikePosition.ANYWHERE))
                "likeLeft" -> conditions.addNotNull(like(call, negated = false, LikePosition.LEFT))
                "likeRight" -> conditions.addNotNull(like(call, negated = false, LikePosition.RIGHT))

                // ---------- 范围 ----------
                "in" -> conditions.addNotNull(inList(call, negated = false))
                "notIn" -> conditions.addNotNull(inList(call, negated = true))
                "between" -> conditions.addNotNull(between(call, negated = false))
                "notBetween" -> conditions.addNotNull(between(call, negated = true))

                // ---------- 空值 ----------
                "isNull" -> conditions.addNotNull(unary(call, "IS NULL"))
                "isNotNull" -> conditions.addNotNull(unary(call, "IS NOT NULL"))

                // ---------- 结构 ----------
                "groupBy" -> groupBy += columnArgs(call)
                "having" -> having = parseHaving(call)
                "orderByAsc" -> orderBy += orderItems(call, asc = true)
                "orderByDesc" -> orderBy += orderItems(call, asc = false)
                "orderBy" -> orderBy += orderItems(call, asc = true)
                "last" -> limit = parseLimit(call)

                // ---------- 逻辑分组 ----------
                "and", "or" -> conditions.addNotNull(parseGroup(call))

                // 其他方法（apply/not/嵌套条件等）M1 暂忽略，保证健壮性
            }
        }

        return SelectStatement(
            table = table,
            selectColumns = selectColumns,
            conditions = conditions,
            groupBy = groupBy,
            having = having,
            orderBy = orderBy,
            limit = limit,
        )
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

    /** ORDER BY 项列表。 */
    private fun orderItems(call: WrapperCall, asc: Boolean): List<OrderByItem> =
        columnArgs(call).map { OrderByItem(it, asc) }

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
