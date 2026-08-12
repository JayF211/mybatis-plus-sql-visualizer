package cn.song.mybatisplus.visualizer.model

/**
 * 列引用：数据库列及其可选的表别名与 SQL 别名（F-01/F-11 selectAs 产物）。
 *
 * @param tableAlias 表别名（单表查询为 null；联表查询如 cd / cssm）
 * @param columnName 列名（如 share_scheme_id）
 * @param alias      SQL 别名（selectAs 时设置，如 AS device_id）
 * @param function   聚合函数（selectMax/selectCount 等，如 MAX / COUNT；null 表示普通列）
 * @param rawExpr    selectFunc 的完整表达式（如 `COUNT(CASE WHEN t.status = 1 THEN 1 END)`；非空时优先渲染）
 */
data class ColumnRef(
    val tableAlias: String? = null,
    val columnName: String,
    val alias: String? = null,
    val function: String? = null,
    val rawExpr: String? = null,
) {
    /** 渲染为带表限定的列表达式，如 `cssm.sort` 或 `sort`。 */
    val qualifiedName: String
        get() = if (tableAlias != null) "$tableAlias.$columnName" else columnName

    /** 渲染完整列表达式（含聚合函数与 AS 别名），如 `MAX(t.power)`、`COUNT(t.id) AS applyCount`。 */
    val rendered: String
        get() {
            val expr = rawExpr ?: (function?.let { "$it($qualifiedName)" } ?: qualifiedName)
            return if (alias != null) "$expr AS $alias" else expr
        }
}

/**
 * ORDER BY 项。
 *
 * @param column 排序列
 * @param asc    true=ASC，false=DESC
 */
data class OrderByItem(
    val column: ColumnRef,
    val asc: Boolean,
)

/**
 * 分页子句（MP 的 last("limit ...") / page 参数解析产物）。
 *
 * @param offset 偏移量（null 表示无 OFFSET）
 * @param count  行数限制
 */
data class LimitClause(
    val offset: Long? = null,
    val count: Long,
)
