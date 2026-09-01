package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 实体字段与数据库列的映射。
 *
 * @param fieldName  实体字段名（如 shareSchemeId）
 * @param columnName 数据库列名（如 share_scheme_id）
 * @param isId       是否主键（@TableId）
 * @param idTypeAuto T-M23-04：主键是否自增（@TableId(type = IdType.AUTO)）——INSERT 模板
 *                   默认不勾选主键列（对齐 MP：自增主键不进 INSERT 列清单）；非 AUTO 恒勾选
 * @param fieldType  实体字段类型全限定名（M13，如 java.lang.String / java.lang.Long），
 *                   用于参数回填的引号推断；PSI 解析失败时为 null
 * @param fill       自动填充标注（@TableField(fill = FieldFill.XXX) 的文本，T-M15-08）：
 *                   INSERT / INSERT_UPDATE 参与插入、UPDATE / INSERT_UPDATE 参与更新；null 表示无填充
 * @param comment    字段注释（M16：注解 &gt; javadoc &gt; 行注释，四级优先级含 schema 预留）
 * @param role       T-M23.3：字段角色（角色分组排序键，默认 ColumnRole.BUSINESS——
 *                   存量构造零影响；排序由 EntityResolver 计算并稳定排序）
 */
public final class ColumnInfo {
    private final String fieldName;
    private final String columnName;
    private final boolean isId;
    private final boolean idTypeAuto;
    private final String fieldType;
    private final String fill;
    private final String comment;
    private final ColumnRole role;

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment, ColumnRole role) {
        this.fieldName = fieldName;
        this.columnName = columnName;
        this.isId = isId;
        this.idTypeAuto = idTypeAuto;
        this.fieldType = fieldType;
        this.fill = fill;
        this.comment = comment;
        this.role = role;
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill, String comment) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType, String fill) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, fill, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                      String fieldType) {
        this(fieldName, columnName, isId, idTypeAuto, fieldType, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId, boolean idTypeAuto) {
        this(fieldName, columnName, isId, idTypeAuto, null, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName, boolean isId) {
        this(fieldName, columnName, isId, false, null, null, null, ColumnRole.BUSINESS);
    }

    public ColumnInfo(String fieldName, String columnName) {
        this(fieldName, columnName, false, false, null, null, null, ColumnRole.BUSINESS);
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean isId() {
        return isId;
    }

    public boolean isIdTypeAuto() {
        return idTypeAuto;
    }

    public String getFieldType() {
        return fieldType;
    }

    public String getFill() {
        return fill;
    }

    public String getComment() {
        return comment;
    }

    public ColumnRole getRole() {
        return role;
    }

    public ColumnInfo copy(String fieldName, String columnName, boolean isId, boolean idTypeAuto,
                           String fieldType, String fill, String comment, ColumnRole role) {
        return new ColumnInfo(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColumnInfo)) {
            return false;
        }
        ColumnInfo that = (ColumnInfo) o;
        return isId == that.isId
                && idTypeAuto == that.idTypeAuto
                && Objects.equals(fieldName, that.fieldName)
                && Objects.equals(columnName, that.columnName)
                && Objects.equals(fieldType, that.fieldType)
                && Objects.equals(fill, that.fill)
                && Objects.equals(comment, that.comment)
                && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, columnName, isId, idTypeAuto, fieldType, fill, comment, role);
    }

    @Override
    public String toString() {
        return "ColumnInfo(fieldName=" + fieldName + ", columnName=" + columnName + ", isId=" + isId
                + ", idTypeAuto=" + idTypeAuto + ", fieldType=" + fieldType + ", fill=" + fill
                + ", comment=" + comment + ", role=" + role + ")";
    }
}
