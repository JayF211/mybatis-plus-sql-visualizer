package cn.song.mybatisplus.visualizer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * @param associations    关联信息列表（@ManyToOne / @OneToOne 关联元数据，用于 JOIN 构建）
 */
public final class TableInfo {
    private final String entityClassName;
    private final String tableName;
    private final List<ColumnInfo> columns;
    private final String logicDeleteColumn;
    private final String versionColumn;
    private final String comment;
    private final List<AssociationInfo> associations;

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn, String comment,
                     List<AssociationInfo> associations) {
        this.entityClassName = entityClassName;
        this.tableName = tableName;
        this.columns = columns;
        this.logicDeleteColumn = logicDeleteColumn;
        this.versionColumn = versionColumn;
        this.comment = comment;
        this.associations = associations;
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn, String comment) {
        this(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn, String versionColumn) {
        this(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, null, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns,
                     String logicDeleteColumn) {
        this(entityClassName, tableName, columns, logicDeleteColumn, null, null, List.of());
    }

    public TableInfo(String entityClassName, String tableName, List<ColumnInfo> columns) {
        this(entityClassName, tableName, columns, null, null, null, List.of());
    }

    public String getEntityClassName() {
        return entityClassName;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public String getLogicDeleteColumn() {
        return logicDeleteColumn;
    }

    public String getVersionColumn() {
        return versionColumn;
    }

    public String getComment() {
        return comment;
    }

    public List<AssociationInfo> getAssociations() {
        return associations;
    }

    /** 便捷查找：按实体字段名取列信息，找不到返回 null。 */
    public ColumnInfo columnByField(String fieldName) {
        for (ColumnInfo c : columns) {
            if (c.getFieldName().equals(fieldName)) {
                return c;
            }
        }
        return null;
    }

    /** 全部列名（用于 SELECT * 展开）。 */
    public List<String> getAllColumnNames() {
        List<String> result = new ArrayList<>();
        for (ColumnInfo c : columns) {
            result.add(c.getColumnName());
        }
        return result;
    }

    /** 按字段名查找关联信息。 */
    public AssociationInfo associationByField(String fieldName) {
        for (AssociationInfo a : associations) {
            if (a.getFieldName().equals(fieldName)) {
                return a;
            }
        }
        return null;
    }

    public TableInfo copy(String entityClassName, String tableName, List<ColumnInfo> columns,
                          String logicDeleteColumn, String versionColumn, String comment,
                          List<AssociationInfo> associations) {
        return new TableInfo(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment, associations);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TableInfo)) {
            return false;
        }
        TableInfo that = (TableInfo) o;
        return Objects.equals(entityClassName, that.entityClassName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columns, that.columns)
                && Objects.equals(logicDeleteColumn, that.logicDeleteColumn)
                && Objects.equals(versionColumn, that.versionColumn)
                && Objects.equals(comment, that.comment)
                && Objects.equals(associations, that.associations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityClassName, tableName, columns, logicDeleteColumn, versionColumn, comment, associations);
    }

    @Override
    public String toString() {
        return "TableInfo(entityClassName=" + entityClassName + ", tableName=" + tableName + ", columns=" + columns
                + ", logicDeleteColumn=" + logicDeleteColumn + ", versionColumn=" + versionColumn + ", comment=" + comment
                + ", associations=" + associations + ")";
    }
}
