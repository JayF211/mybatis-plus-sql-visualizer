package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SELECT 列中间表示。
 */
public final class SelectColumn {
    /** 实体字段名（驼峰，如 shareSchemeId）；聚合函数时为 null。 */
    private final @Nullable String fieldName;
    /** 列别名（当前未使用，预留）。 */
    private final @Nullable String alias;
    /** 聚合函数信息；null 表示非聚合列。 */
    private final @Nullable AggregateInfo aggregate;

    public SelectColumn(@Nullable String fieldName, @Nullable String alias, @Nullable AggregateInfo aggregate) {
        this.fieldName = fieldName;
        this.alias = alias;
        this.aggregate = aggregate;
    }

    public @Nullable String getFieldName() { return fieldName; }
    public @Nullable String fieldName() { return fieldName; }
    public @Nullable String getAlias() { return alias; }
    public @Nullable String alias() { return alias; }
    public @Nullable AggregateInfo getAggregate() { return aggregate; }
    public @Nullable AggregateInfo aggregate() { return aggregate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SelectColumn)) return false;
        SelectColumn that = (SelectColumn) o;
        return java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.alias, that.alias) && java.util.Objects.equals(this.aggregate, that.aggregate);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(fieldName, alias, aggregate);
    }

    @Override
    public String toString() {
        return "SelectColumn[" + "fieldName=" + fieldName + ", " + "alias=" + alias + ", " + "aggregate=" + aggregate + "]";
    }
}
