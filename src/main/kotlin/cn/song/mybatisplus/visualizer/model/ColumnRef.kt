package cn.song.mybatisplus.visualizer.model

/**
 * 列引用：数据库列及其可选的表别名与 SQL 别名（F-01/F-11 selectAs 产物）。
 *
 * @param tableAlias 表别名（单表查询为 null；联表查询如 cd / cssm）
 * @param columnName 列名（如 share_scheme_id）
 * @param alias      SQL 别名（selectAs 时设置，如 AS device_id）
 * @param function   聚合函数（selectMax/selectCount 等，如 MAX / COUNT；null 表示普通列）
 * @param rawExpr    selectFunc 的完整表达式（如 `COUNT(CASE WHEN t.status = 1 THEN 1 END)`；非空时优先渲染）
     * @param entityClass 列来源实体类名（selectAs 第一参方法引用的接收者类名；用于图表列归属推断）
     * @param type        Java 类型全限定名（selectAs 第二参方法引用的返回类型；用于图表列类型显示）
 */
data class ColumnRef(
    val tableAlias: String? = null,
    val columnName: String,
    val alias: String? = null,
    val function: String? = null,
    val rawExpr: String? = null,
    val entityClass: String? = null,
    val type: String? = null,
    /**
     * 表达式内占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）。
     *
     * `select("amount * " + x + " AS v")` 这类片段实参中的运行期段渲染成 `?` 后，须在 SELECT
     * 列列表的渲染点按序登记，否则面板按位置回填会整体错位。
     */
    val rawParams: List<String> = emptyList(),
) {
    /** 渲染为带表限定的列表达式，如 `cssm.sort` 或 `sort`。 */
    val qualifiedName: String
        get() = if (tableAlias != null) "$tableAlias.$columnName" else columnName

    /**
     * T-M23.5：带表限定的列表达式，列名命中**本方言**保留字 → 按方言符号引用（`t.`table``）——
     * 别名前缀不包、列名本体按需包。供解析期 raw 拼接（QueryWrapperParser 等）与
     * [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 渲染共用，与 `quoteIfKeyword` 同语义。
     */
    fun qualifiedName(dialect: cn.song.mybatisplus.visualizer.sql.Dialect): String =
        (tableAlias?.let { "$it." } ?: "") + dialect.quoteIfKeyword(columnName)

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
 * @param offset 偏移量（null 表示无 OFFSET）；字面量直接渲染，运行时值渲染 `?`（T-M29-03）
 * @param count  行数限制；字面量直接渲染，运行时值渲染 `?`
 */
data class LimitClause(
    val offset: SqlValue? = null,
    val count: SqlValue,
) {
    /** 便捷构造：Long 字面量（`last("limit 10")` 文本解析等既有路径，T-M29-03 兼容）。 */
    constructor(offset: Long? = null, count: Long) : this(
        offset?.let { SqlValue.Literal(it.toString()) },
        SqlValue.Literal(count.toString()),
    )
}
