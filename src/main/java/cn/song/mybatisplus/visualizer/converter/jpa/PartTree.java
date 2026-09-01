package cn.song.mybatisplus.visualizer.converter.jpa;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * PartTree 解析产物。
 *
 * @param subject    Subject 段（操作类型、Distinct、限制条数）
 * @param predicates Predicate 段（Or/And 连接的条件列表）
 * @param orderBy    OrderBy 排序子句
 */
public final class PartTree {
    private final @NotNull Subject subject;
    private final @NotNull List<PredicatePart> predicates;
    private final @NotNull List<OrderByPart> orderBy;

    public PartTree(@NotNull Subject subject, @NotNull List<PredicatePart> predicates, @NotNull List<OrderByPart> orderBy) {
        this.subject = subject;
        this.predicates = predicates;
        this.orderBy = orderBy;
    }

    public @NotNull Subject getSubject() { return subject; }
    public @NotNull Subject subject() { return subject; }
    public @NotNull List<PredicatePart> getPredicates() { return predicates; }
    public @NotNull List<PredicatePart> predicates() { return predicates; }
    public @NotNull List<OrderByPart> getOrderBy() { return orderBy; }
    public @NotNull List<OrderByPart> orderBy() { return orderBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PartTree)) return false;
        PartTree that = (PartTree) o;
        return java.util.Objects.equals(this.subject, that.subject) && java.util.Objects.equals(this.predicates, that.predicates) && java.util.Objects.equals(this.orderBy, that.orderBy);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(subject, predicates, orderBy);
    }

    @Override
    public String toString() {
        return "PartTree[" + "subject=" + subject + ", " + "predicates=" + predicates + ", " + "orderBy=" + orderBy + "]";
    }
}
