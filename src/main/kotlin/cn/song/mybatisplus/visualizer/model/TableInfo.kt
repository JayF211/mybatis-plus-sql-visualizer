package cn.song.mybatisplus.visualizer.model

/**
 * 表信息（F-13 实体解析产物）。
 *
 * 由实体类解析得到：表名（@TableName 注解或命名策略推断）、字段列映射（@TableField 或命名策略）。
 *
 * @param entityClassName 实体类的简单类名（如 DeviceInfoEntity）
 * @param tableName       数据库表名（如 device_info）
 * @param columns         实体字段到列的映射（含主键标记）
 */
data class TableInfo(
    val entityClassName: String,
    val tableName: String,
    val columns: List<ColumnInfo>,
) {
    /** 便捷查找：按实体字段名取列信息，找不到返回 null。 */
    fun columnByField(fieldName: String): ColumnInfo? = columns.firstOrNull { it.fieldName == fieldName }

    /** 全部列名（用于 SELECT * 展开）。 */
    val allColumnNames: List<String> get() = columns.map { it.columnName }
}

/**
 * 实体字段与数据库列的映射。
 *
 * @param fieldName  实体字段名（如 shareSchemeId）
 * @param columnName 数据库列名（如 share_scheme_id）
 * @param isId       是否主键（@TableId）
 * @param fieldType  实体字段类型全限定名（M13，如 java.lang.String / java.lang.Long），
 *                   用于参数回填的引号推断；PSI 解析失败时为 null
 */
data class ColumnInfo(
    val fieldName: String,
    val columnName: String,
    val isId: Boolean = false,
    val fieldType: String? = null,
)
