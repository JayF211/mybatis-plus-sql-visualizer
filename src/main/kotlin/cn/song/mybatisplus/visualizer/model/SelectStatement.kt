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
 * @param type        联表类型（INNER / LEFT / RIGHT / FULL）
 * @param tableName   关联表名
 * @param alias       关联表别名（MPJ 惯例：实体类名首字母小写）
 * @param onConditions ON 连接条件（如 `cssm.scheme_id = t.share_scheme_id`）
 * @param rawSql      原生 join 子句（`leftJoin("address t1 on t1.user_id = t.id", args)`，M15-P0）；
 *                    非空时整段原样渲染（含别名与 ON），忽略 tableName/alias/onConditions
 * @param customKeyword 自定义 join 关键字（`join("FULL JOIN", ...)`，M15-P0）；null 时按 type 映射
 * @param logicDeleteColumn 关联表逻辑删除列（@TableLogic 标注字段的列名，T-M20-05）；null 表示无逻辑删除
 *                          （类名兜底 / 字符串 join / selectCollection 子查询形态无 TableInfo）。
 *                          SqlPreviewService 据此按 MPJ 官方语义注入副表 deleted 条件（默认 ON，
 *                          `logicDelToWhere()` 移 WHERE 末尾；`disableSubLogicDel()` 关闭）
 */
data class JoinClause(
    val type: JoinType,
    val tableName: String,
    val alias: String,
    val onConditions: List<Condition>,
    val rawSql: String? = null,
    val customKeyword: String? = null,
    val logicDeleteColumn: String? = null,
)

/**
 * 联表类型。
 */
enum class JoinType { INNER, LEFT, RIGHT, FULL }

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
 * @param rawFirst      SQL 最前片段（`first("...")` 官方 API，M15 增补）；非 null 时置于 SELECT 之前
 * @param comment       SQL 注释（`comment("...")` 官方 API，M15 增补）；非 null 时追加到语句末尾
 * @param unions        UNION / UNION ALL 子查询（MPJ 官方 API，M15 增补）；按调用顺序追加到主语句之后
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
    val rawFirst: String? = null,
    val comment: String? = null,
    val unions: List<UnionClause> = emptyList(),
) : SqlStatement()

/**
 * UNION 子查询子句（MPJ 官方 API）：`union(Class, w -> ...)` → `UNION (子查询)`。
 *
 * @param keyword UNION / UNION ALL
 * @param sql     子查询 SQL（compact 单行渲染）
 */
data class UnionClause(
    val keyword: String,
    val sql: String,
)
