package cn.song.mybatisplus.visualizer.model

/**
 * UPDATE 语句模型（F-09，LambdaUpdateWrapper 转换产物）。
 *
 * @param sets SET 赋值列表
 * @param conditions WHERE 条件（为空表示全表更新，UI 层需警示）
 * @param rawTail 语句尾部原生透传（U05，T-M15-04）：更新 wrapper 上 `last("AND (... >= 0)")` 等
 *                非 limit 形态，原样追加到 SQL 末尾（防超发兜底条件）
 * @param tableAlias 主表别名（T-M21-08：UpdateJoinWrapper 联表更新渲染 `UPDATE 表名 别名`；单表更新为 null）
 * @param joins 联表子句（T-M21-08：UpdateJoinWrapper 渲染于 FROM 之后、SET 之前，
 *              `UPDATE user t LEFT JOIN address t1 ON (t1.user_id = t.id) SET ...`）
 */
data class UpdateStatement(
    override val table: TableInfo,
    val sets: List<SetClause>,
    override val conditions: List<Condition> = emptyList(),
    val rawTail: String? = null,
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
) : SqlStatement()

/**
 * SET 赋值子句。
 *
 * 两种形式（互斥）：
 *  - [column] + [value]：`set(Entity::getX, v)` → `SET col = v`
 *  - [rawSql]：`setSql("col = expr")` → 原样渲染
 */
data class SetClause(
    val column: ColumnRef? = null,
    val value: SqlValue? = null,
    val rawSql: String? = null,
) {
    /** 是否为原始 SQL 形式（setSql）。 */
    val isRaw: Boolean get() = rawSql != null
}

/**
 * DELETE 语句模型（F-10，LambdaDeleteWrapper 转换产物）。
 *
 * @param conditions WHERE 条件（为空表示全表删除，UI 层需红色警示）
 * @param deleteTargets 删除目标表别名列表（T-M21-08：DeleteJoinWrapper 的
 *                      `delete(Class...)/delete(String...)/deleteAll()` 控制删除哪些表，
 *                      如 listOf("t", "t1") → `DELETE t, t1 FROM ...`；空列表走既有
 *                      `DELETE FROM 表名` 形态，单表删除不受影响）
 * @param tableAlias 主表别名（T-M21-08：DeleteJoinWrapper 渲染 `DELETE t FROM 表名 t`，FROM 后带主表别名；
 *                   单表删除为 null）
 * @param joins 联表子句（T-M21-08：DeleteJoinWrapper 渲染于 FROM 之后、WHERE 之前，
 *              `DELETE t,t1 FROM user t LEFT JOIN address t1 ON (...) WHERE ...`）
 */
data class DeleteStatement(
    override val table: TableInfo,
    override val conditions: List<Condition> = emptyList(),
    val deleteTargets: List<String> = emptyList(),
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
) : SqlStatement()
