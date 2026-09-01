package cn.song.mybatisplus.visualizer.psi.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 聚合函数信息（P1-5 CriteriaQuery 聚合函数支持）。
 */
public final class AggregateInfo {
    /** 聚合函数名（COUNT / SUM / MAX / MIN / AVG）。 */
    private final @NotNull String function;
    /** 聚合目标字段名；COUNT(*) 时为 null。 */
    private final @Nullable String fieldName;
    /** 是否 DISTINCT。 */
    private final boolean distinct;

    public AggregateInfo(@NotNull String function, @Nullable String fieldName, boolean distinct) {
        this.function = function;
        this.fieldName = fieldName;
        this.distinct = distinct;
    }

    public @NotNull String getFunction() { return function; }
    public @NotNull String function() { return function; }
    public @Nullable String getFieldName() { return fieldName; }
    public @Nullable String fieldName() { return fieldName; }
    public boolean isDistinct() { return distinct; }
    public boolean distinct() { return distinct; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AggregateInfo)) return false;
        AggregateInfo that = (AggregateInfo) o;
        return java.util.Objects.equals(this.function, that.function) && java.util.Objects.equals(this.fieldName, that.fieldName) && java.util.Objects.equals(this.distinct, that.distinct);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(function, fieldName, distinct);
    }

    @Override
    public String toString() {
        return "AggregateInfo[" + "function=" + function + ", " + "fieldName=" + fieldName + ", " + "distinct=" + distinct + "]";
    }
}
