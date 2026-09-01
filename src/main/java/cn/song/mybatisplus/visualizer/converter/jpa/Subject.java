package cn.song.mybatisplus.visualizer.converter.jpa;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Subject 段：方法名中 `By` 之前的部分。
 *
 * @param operation 操作类型（FIND / COUNT / EXISTS / DELETE）
 * @param distinct  是否去重
 * @param limit     限制条数（Top N / First N），null 表示不限制
 */
public final class Subject {
    private final @NotNull Operation operation;
    private final boolean distinct;
    private final @Nullable Integer limit;

    public Subject(@NotNull Operation operation, boolean distinct, @Nullable Integer limit) {
        this.operation = operation;
        this.distinct = distinct;
        this.limit = limit;
    }

    public @NotNull Operation getOperation() { return operation; }
    public @NotNull Operation operation() { return operation; }
    public boolean isDistinct() { return distinct; }
    public boolean distinct() { return distinct; }
    public @Nullable Integer getLimit() { return limit; }
    public @Nullable Integer limit() { return limit; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Subject)) return false;
        Subject that = (Subject) o;
        return java.util.Objects.equals(this.operation, that.operation) && java.util.Objects.equals(this.distinct, that.distinct) && java.util.Objects.equals(this.limit, that.limit);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(operation, distinct, limit);
    }

    @Override
    public String toString() {
        return "Subject[" + "operation=" + operation + ", " + "distinct=" + distinct + ", " + "limit=" + limit + "]";
    }
}
