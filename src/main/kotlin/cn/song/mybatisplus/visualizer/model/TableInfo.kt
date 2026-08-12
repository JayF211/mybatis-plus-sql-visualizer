package cn.song.mybatisplus.visualizer.model

/**
 * 表信息（F-13 实体解析产物）。
 *
 * 由实体类解析得到：表名（@TableName 注解或命名策略推断）、字段列映射（@TableField 或命名策略）。
 *
 * @param entityClassName 实体类的简单类名（如 CarDeviceInfoEntity）
 * @param tableName       数据库表名（如 car_device_info）
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
 */
data class ColumnInfo(
    val fieldName: String,
    val columnName: String,
    val isId: Boolean = false,
)
