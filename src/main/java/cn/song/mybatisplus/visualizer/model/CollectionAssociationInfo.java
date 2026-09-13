package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 集合关联信息（T-J7 @OneToMany/@ManyToMany 关联增强、T-J18 @ElementCollection/@CollectionTable）。
 *
 * 与 {@link AssociationInfo}（@ManyToOne/@OneToOne 主表侧 JOIN 语义）分开建模：
 * 集合关联是「独立第二查询/中间表」语义，不参与主表 JOIN 列推断。
 *
 * @param fieldName   集合字段名（Java 字段名）
 * @param kind        集合关联类型
 * @param targetTable 目标表名：ONE_TO_MANY/MANY_TO_MANY 为元素实体表；ELEMENT_COLLECTION 为集合表名
 *                    （@CollectionTable 未指定 name 时按「主表名_字段名」推断）
 * @param mappedBy    对方实体持有关联字段名（mappedBy；null 表示本侧为持有方/无反向声明）
 * @param joinTable   中间表信息（@JoinTable / @CollectionTable）
 * @param joinColumns 单向 @OneToMany + @JoinColumn 的关联列（外键在目标表）
 * @param orderBy     @OrderBy(value) 原文（如 "name ASC"；未指定为 null——默认按主键）
 * @param orderColumn @OrderColumn(name) 索引列名（未指定为 null）
 */
public final class CollectionAssociationInfo {
    private final String fieldName;
    private final CollectionKind kind;
    private final String targetTable;
    private final String mappedBy;
    private final JoinTableInfo joinTable;
    private final List<JoinColumnRef> joinColumns;
    private final String orderBy;
    private final String orderColumn;

    public CollectionAssociationInfo(String fieldName, CollectionKind kind, String targetTable, String mappedBy,
                                     JoinTableInfo joinTable, List<JoinColumnRef> joinColumns,
                                     String orderBy, String orderColumn) {
        this.fieldName = fieldName;
        this.kind = kind;
        this.targetTable = targetTable;
        this.mappedBy = mappedBy;
        this.joinTable = joinTable;
        this.joinColumns = joinColumns;
        this.orderBy = orderBy;
        this.orderColumn = orderColumn;
    }

    public CollectionAssociationInfo(String fieldName, CollectionKind kind, String targetTable) {
        this(fieldName, kind, targetTable, null, null, List.of(), null, null);
    }

    public String getFieldName() {
        return fieldName;
    }

    public CollectionKind getKind() {
        return kind;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public String getMappedBy() {
        return mappedBy;
    }

    public JoinTableInfo getJoinTable() {
        return joinTable;
    }

    public List<JoinColumnRef> getJoinColumns() {
        return joinColumns;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public String getOrderColumn() {
        return orderColumn;
    }

    public CollectionAssociationInfo copy(String fieldName, CollectionKind kind, String targetTable, String mappedBy,
                                          JoinTableInfo joinTable, List<JoinColumnRef> joinColumns,
                                          String orderBy, String orderColumn) {
        return new CollectionAssociationInfo(fieldName, kind, targetTable, mappedBy, joinTable, joinColumns,
                orderBy, orderColumn);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CollectionAssociationInfo)) {
            return false;
        }
        CollectionAssociationInfo that = (CollectionAssociationInfo) o;
        return Objects.equals(fieldName, that.fieldName)
                && kind == that.kind
                && Objects.equals(targetTable, that.targetTable)
                && Objects.equals(mappedBy, that.mappedBy)
                && Objects.equals(joinTable, that.joinTable)
                && Objects.equals(joinColumns, that.joinColumns)
                && Objects.equals(orderBy, that.orderBy)
                && Objects.equals(orderColumn, that.orderColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, kind, targetTable, mappedBy, joinTable, joinColumns, orderBy, orderColumn);
    }

    @Override
    public String toString() {
        return "CollectionAssociationInfo(fieldName=" + fieldName + ", kind=" + kind + ", targetTable=" + targetTable
                + ", mappedBy=" + mappedBy + ", joinTable=" + joinTable + ", joinColumns=" + joinColumns
                + ", orderBy=" + orderBy + ", orderColumn=" + orderColumn + ")";
    }
}
