package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 关联信息（JPA @ManyToOne / @OneToOne 关联）。
 *
 * @param fieldName    关联字段名（Java 字段名，如 "address"）
 * @param joinColumn   外键列名（主表侧，如 "address_id"）；复合外键时为首列（完整清单见 joinColumns）
 * @param targetTable  目标表名（副表，如 "address"）
 * @param targetAlias  建议别名（实体类名首字母小写，如 "address"）
 * @param referencedColumnName 被引用列（T-J7 @JoinColumn referencedColumnName）；未指定为 null（默认对方主键）
 * @param joinColumns  外键列完整清单（T-J7 @JoinColumns 复合外键；单外键时为单元素列表，
 *                     无 @JoinColumn 时为推断名单元素列表）
 * @param mappedBy     对方实体持有关联字段名（T-J7 mappedBy）；null 表示本侧为持有方
 * @param hasForeignKey 本表是否持有外键（T-J7 侧别语义）：mappedBy 侧为 false——无 FK 列，
 *                     查询语义为按主键二次 SELECT；joinColumn 字段此时仅为占位推断值
 * @param joinTable    中间表信息（T-J7 @JoinTable；@ManyToOne/@OneToOne 恒为 null，@ManyToMany 见 CollectionAssociationInfo）
 * @param notFoundIgnore Hibernate @NotFound(action = IGNORE) 标记（T-J7）：目标行缺失时置 null 而非抛异常
 * @param mapsId       @MapsId 标记（T-J7）：外键列与引用实体的 @EmbeddedId/@Id 共享
 */
public final class AssociationInfo {
    private final String fieldName;
    private final String joinColumn;
    private final String targetTable;
    private final String targetAlias;
    private final String referencedColumnName;
    private final List<JoinColumnRef> joinColumns;
    private final String mappedBy;
    private final boolean hasForeignKey;
    private final JoinTableInfo joinTable;
    private final boolean notFoundIgnore;
    private final boolean mapsId;

    public AssociationInfo(String fieldName, String joinColumn, String targetTable, String targetAlias,
                           String referencedColumnName, List<JoinColumnRef> joinColumns, String mappedBy,
                           boolean hasForeignKey, JoinTableInfo joinTable, boolean notFoundIgnore, boolean mapsId) {
        this.fieldName = fieldName;
        this.joinColumn = joinColumn;
        this.targetTable = targetTable;
        this.targetAlias = targetAlias;
        this.referencedColumnName = referencedColumnName;
        this.joinColumns = joinColumns;
        this.mappedBy = mappedBy;
        this.hasForeignKey = hasForeignKey;
        this.joinTable = joinTable;
        this.notFoundIgnore = notFoundIgnore;
        this.mapsId = mapsId;
    }

    public AssociationInfo(String fieldName, String joinColumn, String targetTable, String targetAlias) {
        this(fieldName, joinColumn, targetTable, targetAlias, null, List.of(), null,
                true, null, false, false);
    }

    /** 七参缺省形态（{@code hasForeignKey=true} 等 Kotlin 默认参数的等价重载）。 */
    public AssociationInfo(String fieldName, String joinColumn, String targetTable, String targetAlias,
                           String referencedColumnName, List<JoinColumnRef> joinColumns, String mappedBy) {
        this(fieldName, joinColumn, targetTable, targetAlias, referencedColumnName, joinColumns, mappedBy,
                true, null, false, false);
    }

    public AssociationInfo(String fieldName, String joinColumn, String targetTable, String targetAlias,
                           String referencedColumnName, List<JoinColumnRef> joinColumns, String mappedBy,
                           boolean hasForeignKey) {
        this(fieldName, joinColumn, targetTable, targetAlias, referencedColumnName, joinColumns, mappedBy,
                hasForeignKey, null, false, false);
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

    public String getReferencedColumnName() {
        return referencedColumnName;
    }

    public List<JoinColumnRef> getJoinColumns() {
        return joinColumns;
    }

    public String getMappedBy() {
        return mappedBy;
    }

    public boolean isHasForeignKey() {
        return hasForeignKey;
    }

    public JoinTableInfo getJoinTable() {
        return joinTable;
    }

    public boolean isNotFoundIgnore() {
        return notFoundIgnore;
    }

    public boolean isMapsId() {
        return mapsId;
    }

    public AssociationInfo copy(String fieldName, String joinColumn, String targetTable, String targetAlias,
                                String referencedColumnName, List<JoinColumnRef> joinColumns, String mappedBy,
                                boolean hasForeignKey, JoinTableInfo joinTable, boolean notFoundIgnore,
                                boolean mapsId) {
        return new AssociationInfo(fieldName, joinColumn, targetTable, targetAlias, referencedColumnName,
                joinColumns, mappedBy, hasForeignKey, joinTable, notFoundIgnore, mapsId);
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
        return hasForeignKey == that.hasForeignKey
                && notFoundIgnore == that.notFoundIgnore
                && mapsId == that.mapsId
                && Objects.equals(fieldName, that.fieldName)
                && Objects.equals(joinColumn, that.joinColumn)
                && Objects.equals(targetTable, that.targetTable)
                && Objects.equals(targetAlias, that.targetAlias)
                && Objects.equals(referencedColumnName, that.referencedColumnName)
                && Objects.equals(joinColumns, that.joinColumns)
                && Objects.equals(mappedBy, that.mappedBy)
                && Objects.equals(joinTable, that.joinTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, joinColumn, targetTable, targetAlias, referencedColumnName,
                joinColumns, mappedBy, hasForeignKey, joinTable, notFoundIgnore, mapsId);
    }

    @Override
    public String toString() {
        return "AssociationInfo(fieldName=" + fieldName + ", joinColumn=" + joinColumn
                + ", targetTable=" + targetTable + ", targetAlias=" + targetAlias
                + ", referencedColumnName=" + referencedColumnName + ", joinColumns=" + joinColumns
                + ", mappedBy=" + mappedBy + ", hasForeignKey=" + hasForeignKey + ", joinTable=" + joinTable
                + ", notFoundIgnore=" + notFoundIgnore + ", mapsId=" + mapsId + ")";
    }
}
