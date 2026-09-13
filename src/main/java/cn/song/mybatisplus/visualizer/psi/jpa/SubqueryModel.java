package cn.song.mybatisplus.visualizer.psi.jpa;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 子查询中间表示（T-J17/J13 有界实现：单层子查询）。
 *
 * <p>支持 {@code Subquery<T> sq = query.subquery(...)} + {@code sq.from/select/where} 的有界抽取；
 * correlate 关联与多层嵌套无法静态渲染，置 degradedReason 后整体降级 Raw 注释。
 */
public final class SubqueryModel {
    private final @Nullable String entityClassName;
    private final @Nullable String selectFieldName;
    private final @NotNull List<PredicateModel> predicates;
    private final @Nullable String degradedReason;

    public SubqueryModel(@Nullable String entityClassName, @Nullable String selectFieldName,
                         @NotNull List<PredicateModel> predicates, @Nullable String degradedReason) {
        this.entityClassName = entityClassName;
        this.selectFieldName = selectFieldName;
        this.predicates = predicates;
        this.degradedReason = degradedReason;
    }

    public SubqueryModel() {
        this(null, null, List.of(), null);
    }

    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @Nullable String getSelectFieldName() { return selectFieldName; }
    public @Nullable String selectFieldName() { return selectFieldName; }
    public @NotNull List<PredicateModel> getPredicates() { return predicates; }
    public @NotNull List<PredicateModel> predicates() { return predicates; }
    public @Nullable String getDegradedReason() { return degradedReason; }
    public @Nullable String degradedReason() { return degradedReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubqueryModel)) return false;
        SubqueryModel that = (SubqueryModel) o;
        return Objects.equals(this.entityClassName, that.entityClassName) && Objects.equals(this.selectFieldName, that.selectFieldName) && Objects.equals(this.predicates, that.predicates) && Objects.equals(this.degradedReason, that.degradedReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityClassName, selectFieldName, predicates, degradedReason);
    }

    @Override
    public String toString() {
        return "SubqueryModel[" + "entityClassName=" + entityClassName + ", " + "selectFieldName=" + selectFieldName + ", " + "predicates=" + predicates + ", " + "degradedReason=" + degradedReason + "]";
    }
}
