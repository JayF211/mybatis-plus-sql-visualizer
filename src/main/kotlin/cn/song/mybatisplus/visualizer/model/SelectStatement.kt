package cn.song.mybatisplus.visualizer.model

/**
 * SQL 语句模型（SQL 生成器的输入）。
 *
 * M1 实现 SELECT（LambdaQueryWrapper）；M4 扩展 UPDATE（LambdaUpdateWrapper）与
 * DELETE（LambdaDeleteWrapper），并支持 MPJ 联表（SelectStatement 带 [JoinClause]）。
 */
sealed class SqlStatement {

    /** 目标表信息。 */
    abstract val table: TableInfo

    /** WHERE 条件列表（顶层以 AND 连接）。 */
    abstract val conditions: List<Condition>
}

/**
 * 联表子句（MPJ，F-11/F-12）。
 *
 * @param type        联表类型（INNER / LEFT / RIGHT）
 * @param tableName   关联表名
 * @param alias       关联表别名（MPJ 惯例：实体类名首字母小写）
 * @param onConditions ON 连接条件（如 `cssm.scheme_id = t.share_scheme_id`）
 */
data class JoinClause(
    val type: JoinType,
    val tableName: String,
    val alias: String,
    val onConditions: List<Condition>,
)

/**
 * 联表类型。
 */
enum class JoinType { INNER, LEFT, RIGHT }

/**
 * SELECT 语句模型。
 *
 * @param selectColumns SELECT 列；为空表示全部列（`SELECT *` 语义，生成器展开为全列名）
 * @param tableAlias    主表别名（单表查询为 null；MPJ 联表默认 `t`）
 * @param joins         联表子句列表（MPJ）
 * @param distinct      是否 SELECT DISTINCT（清单 P05）
 * @param groupBy       GROUP BY 列
 * @param having        HAVING 条件（单个）
 * @param orderBy       ORDER BY 项列表
 * @param limit         分页子句（null 表示无 LIMIT）
 */
data class SelectStatement(
    override val table: TableInfo,
    val selectColumns: List<ColumnRef> = emptyList(),
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
    val distinct: Boolean = false,
    override val conditions: List<Condition> = emptyList(),
    val groupBy: List<ColumnRef> = emptyList(),
    val having: Condition? = null,
    val orderBy: List<OrderByItem> = emptyList(),
    val limit: LimitClause? = null,
) : SqlStatement()
