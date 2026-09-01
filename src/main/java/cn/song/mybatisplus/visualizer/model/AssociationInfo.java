package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 关联信息（JPA @ManyToOne / @OneToOne 关联）。
 *
 * @param fieldName    关联字段名（Java 字段名，如 "address"）
 * @param joinColumn   外键列名（主表侧，如 "address_id"）
 * @param targetTable  目标表名（副表，如 "address"）
 * @param targetAlias  建议别名（实体类名首字母小写，如 "address"）
 */
public final class AssociationInfo {
    private final String fieldName;
    private final String joinColumn;
    private final String targetTable;
    private final String targetAlias;

    public AssociationInfo(String fieldName, String joinColumn, String targetTable, String targetAlias) {
        this.fieldName = fieldName;
        this.joinColumn = joinColumn;
        this.targetTable = targetTable;
        this.targetAlias = targetAlias;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getJoinColumn() {
        return joinColumn;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public String getTargetAlias() {
        return targetAlias;
    }

    public AssociationInfo copy(String fieldName, String joinColumn, String targetTable, String targetAlias) {
        return new AssociationInfo(fieldName, joinColumn, targetTable, targetAlias);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssociationInfo)) {
            return false;
        }
        AssociationInfo that = (AssociationInfo) o;
        return Objects.equals(fieldName, that.fieldName)
                && Objects.equals(joinColumn, that.joinColumn)
                && Objects.equals(targetTable, that.targetTable)
                && Objects.equals(targetAlias, that.targetAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, joinColumn, targetTable, targetAlias);
    }

    @Override
    public String toString() {
        return "AssociationInfo(fieldName=" + fieldName + ", joinColumn=" + joinColumn
                + ", targetTable=" + targetTable + ", targetAlias=" + targetAlias + ")";
    }
}
