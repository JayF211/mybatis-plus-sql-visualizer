package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType

/**
 * MPJQueryWrapper 转换器（F-12）：字符串字段版联表 SELECT。
 *
 * 与 [MpjLambdaConverter] 的区别：列与 join 均为字符串形式，无需实体解析：
 *  - `select("t.id AS device_id", ...)` → SELECT 原样列
 *  - `leftJoin("car_station_share_scheme_merchant_info m", "m.scheme_id = t.share_scheme_id")`
 *    → JOIN 子句（表名 + 可选别名 + ON 字符串）
 *  - `eq("t.id", "1001")` 等条件 → 字符串列名原样（QueryWrapperParser 的 ValueArg 分支）
 *
 * 字符串列已含表别名（如 `t.id`），不再补前缀。
 */
object MpjQueryConverter : WrapperConverter {

    /** MPJ 主表别名惯例（字符串写法中由调用方显式使用，如 `t.id`）。 */
    private const val MAIN_ALIAS = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_QUERY)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        val selectCalls = chain.calls.filter { it.methodName == "select" }
        val joinCalls = chain.calls.filter { it.methodName in JOIN_METHODS }
        // selectIgnore（T-M20-04）：MPJQueryWrapper 独有——selectAll 全列展开后剔除列（字符串形态）
        val ignoreCalls = chain.calls.filter { it.methodName == "selectIgnore" }

        // 其余调用（eq/orderBy 等）委托 Query 解析；字符串列原样（不补前缀）
        val restCalls = chain.calls.filterNot {
            it.methodName == "select" || it.methodName in JOIN_METHODS || it.methodName == "selectIgnore"
        }
        val base = QueryWrapperParser(table, MAIN_ALIAS)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, restCalls))

        // select("t.id AS device_id") 字符串列原样渲染（unquoted 去引号）
        var selectColumns = base.selectColumns + selectCalls.flatMap { call ->
            call.args.mapNotNull { arg ->
                (arg.asValue() as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
            }
        }

        // selectIgnore：仅当 selectColumns 为空（selectAll 语义，全列展开）且表有列时生效——
        // 直接产出 `全列 - 忽略列` 的显式列清单（带主表别名前缀），交由生成层原样渲染；
        // 显式 select 时忽略集合不生效（与 MPJ 语义一致）；表无列时无从剔除，保持原样（文档化边界）
        if (selectColumns.isEmpty() && table.columns.isNotEmpty()) {
            val ignored = ignoreCalls.flatMap { call -> call.args.mapNotNull { ignoredColumnName(it) } }.toSet()
            selectColumns = (table.allColumnNames - ignored).map { ColumnRef(columnName = "$MAIN_ALIAS.$it") }
        }

        val joins = joinCalls.mapNotNull { parseJoin(it) }

        return base.copy(
            tableAlias = MAIN_ALIAS,
            selectColumns = selectColumns,
            joins = joins,
        )
    }

    /**
     * selectIgnore 实参 → 列名（归一化）：ColumnArg 取 columnName；ValueArg 取字面量（原样字符串列）。
     */
    private fun ignoredColumnName(arg: WrapperArg): String? = when (arg) {
        is WrapperArg.ColumnArg -> normalizeIgnored(arg.column.columnName)
        is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted?.let(::normalizeIgnored)
        else -> null
    }

    /** 归一化忽略列名：去空白、AS 别名后缀、主表别名前缀（`"t.station_id"` / `"t.station_id AS device_id"` → `station_id`）。 */
    private fun normalizeIgnored(name: String): String {
        var core = name.trim()
        core = core.substringBefore(" AS ").substringBefore(" as ").trim()
        return core.removePrefix("$MAIN_ALIAS.")
    }

    /**
     * 解析 join("table_name alias", "on sql") → 联表子句。
     *
     * @param call join 调用；第一参数为"表名 [别名]"，第二参数为 ON 条件字符串
     */
    private fun parseJoin(call: WrapperCall): JoinClause? {
        val type = when (call.methodName) {
            "leftJoin" -> JoinType.LEFT
            "innerJoin" -> JoinType.INNER
            else -> JoinType.RIGHT
        }
        val tableText = (call.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted?.trim() ?: return null
        val parts = tableText.split(Regex("\\s+"))
        val tableName = parts[0]
        val alias = parts.getOrNull(1) ?: tableName

        val onText = (call.args.getOrNull(1).asValue() as? SqlValue.Literal)?.unquoted
        val onConditions = if (onText != null) listOf(Condition.Raw(onText)) else emptyList()

        return JoinClause(type, tableName, alias, onConditions)
    }

    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin")
}
