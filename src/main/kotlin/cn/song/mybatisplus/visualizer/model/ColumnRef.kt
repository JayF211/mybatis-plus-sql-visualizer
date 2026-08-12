package cn.song.mybatisplus.visualizer.model

/**
 * 列引用：数据库列及其可选的表别名与 SQL 别名（F-01/F-11 selectAs 产物）。
 *
 * @param tableAlias 表别名（单表查询为 null；联表查询如 cd / cssm）
 * @param columnName 列名（如 share_scheme_id）
 * @param alias      SQL 别名（selectAs 时设置，如 AS device_id）
 */
data class ColumnRef(
    val tableAlias: String? = null,
    val columnName: String,
    val alias: String? = null,
) {
    /** 渲染为带表限定的列表达式，如 `cssm.sort` 或 `sort`。 */
    val qualifiedName: String
        get() = if (tableAlias != null) "$tableAlias.$columnName" else columnName

    /** 渲染完整列表达式（含 AS 别名），如 `cd.id AS device_id`。 */
    val rendered: String
        get() = if (alias != null) "$qualifiedName AS $alias" else qualifiedName
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
