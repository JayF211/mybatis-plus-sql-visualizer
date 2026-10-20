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
 * @param unparsedSets 解析失败的 SET 调用原文（T-UpdateSetSqlConcat）：`setSql(sqlVar)` 这类
 *              纯运行期片段静态不可还原，只能省略；此处留痕供 UI 警示，
 *              避免静默产出「无 SET」的非法 SQL
 * @param rawTailParams [rawTail] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）
 */
data class UpdateStatement(
    override val table: TableInfo,
    val sets: List<SetClause>,
    override val conditions: List<Condition> = emptyList(),
    val rawTail: String? = null,
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
    val unparsedSets: List<String> = emptyList(),
    val rawTailParams: List<String> = emptyList(),
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
    /** [rawSql] 中占位符 `?` 的来源（按出现顺序，T-UpdateSetSqlConcat）：渲染时按序登记进参数表。 */
    val rawParams: List<String> = emptyList(),
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
 * @param limit 尾段（O-8 缺陷 C，T-StrWrapperDeleteWhere）：删除 wrapper 上 `last("LIMIT n")` 的
 *              parseLimit 产物——MP 语义 MySQL `DELETE ... LIMIT n`；渲染与否由方言
 *              （[cn.song.mybatisplus.visualizer.sql.Dialect.supportsDeleteLimit]）决定，
 *              不支持的方言静默省略（与分页方言策略一致）
 */
data class DeleteStatement(
    override val table: TableInfo,
    override val conditions: List<Condition> = emptyList(),
    val deleteTargets: List<String> = emptyList(),
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
    val limit: LimitClause? = null,
) : SqlStatement()
