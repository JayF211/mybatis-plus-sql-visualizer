package cn.song.mybatisplus.visualizer.model

/**
 * 表信息（F-13 实体解析产物）。
 *
 * 由实体类解析得到：表名（@TableName 注解或命名策略推断）、字段列映射（@TableField 或命名策略）。
 *
 * @param entityClassName 实体类的简单类名（如 DeviceInfoEntity）
 * @param tableName       数据库表名（如 device_info）
 * @param columns         实体字段到列的映射（含主键标记）
 * @param logicDeleteColumn 逻辑删除列（@TableLogic 标注字段的列名，T-M15-07）；null 表示无逻辑删除
 * @param versionColumn     乐观锁版本列（@Version 标注字段的列名，T-M22-02）；null 表示无乐观锁
 * @param comment         表注释（M16：类级注解/javadoc/行注释提取，schema 预留最高优先级）
 */
data class TableInfo(
    val entityClassName: String,
    val tableName: String,
    val columns: List<ColumnInfo>,
    val logicDeleteColumn: String? = null,
    val versionColumn: String? = null,
    val comment: String? = null,
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
 * @param idTypeAuto T-M23-04：主键是否自增（`@TableId(type = IdType.AUTO)`）——INSERT 模板
 *                   默认不勾选主键列（对齐 MP：自增主键不进 INSERT 列清单）；非 AUTO 恒勾选
 * @param fieldType  实体字段类型全限定名（M13，如 java.lang.String / java.lang.Long），
 *                   用于参数回填的引号推断；PSI 解析失败时为 null
 * @param fill       自动填充标注（@TableField(fill = FieldFill.XXX) 的文本，T-M15-08）：
 *                   INSERT / INSERT_UPDATE 参与插入、UPDATE / INSERT_UPDATE 参与更新；null 表示无填充
 * @param comment    字段注释（M16：注解 > javadoc > 行注释，四级优先级含 schema 预留）
 */
data class ColumnInfo(
    val fieldName: String,
    val columnName: String,
    val isId: Boolean = false,
    val idTypeAuto: Boolean = false,
    val fieldType: String? = null,
    val fill: String? = null,
    val comment: String? = null,
)
