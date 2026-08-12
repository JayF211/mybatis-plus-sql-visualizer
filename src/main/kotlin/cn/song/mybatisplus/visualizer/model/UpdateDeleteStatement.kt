package cn.song.mybatisplus.visualizer.model

/**
 * UPDATE 语句模型（F-09，LambdaUpdateWrapper 转换产物）。
 *
 * @param sets SET 赋值列表
 * @param conditions WHERE 条件（为空表示全表更新，UI 层需警示）
 */
data class UpdateStatement(
    override val table: TableInfo,
    val sets: List<SetClause>,
    override val conditions: List<Condition> = emptyList(),
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
 */
data class DeleteStatement(
    override val table: TableInfo,
    override val conditions: List<Condition> = emptyList(),
) : SqlStatement()
