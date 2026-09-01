package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.psi.PsiClass;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JPA CriteriaQuery 中间表示（CriteriaQueryLocator 产物，CriteriaQueryConverter 输入）。
 *
 * 从 PSI 调用链中提取的结构化查询模型，涵盖 FROM / SELECT / WHERE / GROUP BY / HAVING / ORDER BY / JOIN。
 */
public final class CriteriaQueryModel {
    /** 实体类全限定名（from 的实体类）。 */
    private final @Nullable String entityClassName;
    /** 实体类 PsiClass（可解析时非 null）。 */
    private final @Nullable PsiClass entityClass;
    /** SELECT 列列表（root.get(...) 形式）；空表示 select(root) 全列。 */
    private final @NotNull List<SelectColumn> selectColumns;
    /** WHERE 条件中间列表。 */
    private final @NotNull List<PredicateModel> predicates;
    /** GROUP BY 列列表。 */
    private final @NotNull List<GroupByModel> groupBys;
    /** HAVING 条件中间列表（顶层以 AND 连接）。 */
    private final @NotNull List<PredicateModel> havings;
    /** ORDER BY 列表。 */
    private final @NotNull List<OrderByModel> orderBys;
    /** JOIN 列表。 */
    private final @NotNull List<JoinModel> joins;
    /** createQuery 的结果类型全限定名（与 entityClassName 通常一致；多态投影时可能不同）。 */
    private final @Nullable String resultClassName;

    public CriteriaQueryModel(@Nullable String entityClassName, @Nullable PsiClass entityClass, @NotNull List<SelectColumn> selectColumns, @NotNull List<PredicateModel> predicates, @NotNull List<GroupByModel> groupBys, @NotNull List<PredicateModel> havings, @NotNull List<OrderByModel> orderBys, @NotNull List<JoinModel> joins, @Nullable String resultClassName) {
        this.entityClassName = entityClassName;
        this.entityClass = entityClass;
        this.selectColumns = selectColumns;
        this.predicates = predicates;
        this.groupBys = groupBys;
        this.havings = havings;
        this.orderBys = orderBys;
        this.joins = joins;
        this.resultClassName = resultClassName;
    }

    public @Nullable String getEntityClassName() { return entityClassName; }
    public @Nullable String entityClassName() { return entityClassName; }
    public @Nullable PsiClass getEntityClass() { return entityClass; }
    public @Nullable PsiClass entityClass() { return entityClass; }
    public @NotNull List<SelectColumn> getSelectColumns() { return selectColumns; }
    public @NotNull List<SelectColumn> selectColumns() { return selectColumns; }
    public @NotNull List<PredicateModel> getPredicates() { return predicates; }
    public @NotNull List<PredicateModel> predicates() { return predicates; }
    public @NotNull List<GroupByModel> getGroupBys() { return groupBys; }
    public @NotNull List<GroupByModel> groupBys() { return groupBys; }
    public @NotNull List<PredicateModel> getHavings() { return havings; }
    public @NotNull List<PredicateModel> havings() { return havings; }
    public @NotNull List<OrderByModel> getOrderBys() { return orderBys; }
    public @NotNull List<OrderByModel> orderBys() { return orderBys; }
    public @NotNull List<JoinModel> getJoins() { return joins; }
    public @NotNull List<JoinModel> joins() { return joins; }
    public @Nullable String getResultClassName() { return resultClassName; }
    public @Nullable String resultClassName() { return resultClassName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CriteriaQueryModel)) return false;
        CriteriaQueryModel that = (CriteriaQueryModel) o;
        return java.util.Objects.equals(this.entityClassName, that.entityClassName) && java.util.Objects.equals(this.entityClass, that.entityClass) && java.util.Objects.equals(this.selectColumns, that.selectColumns) && java.util.Objects.equals(this.predicates, that.predicates) && java.util.Objects.equals(this.groupBys, that.groupBys) && java.util.Objects.equals(this.havings, that.havings) && java.util.Objects.equals(this.orderBys, that.orderBys) && java.util.Objects.equals(this.joins, that.joins) && java.util.Objects.equals(this.resultClassName, that.resultClassName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(entityClassName, entityClass, selectColumns, predicates, groupBys, havings, orderBys, joins, resultClassName);
    }

    @Override
    public String toString() {
        return "CriteriaQueryModel[" + "entityClassName=" + entityClassName + ", " + "entityClass=" + entityClass + ", " + "selectColumns=" + selectColumns + ", " + "predicates=" + predicates + ", " + "groupBys=" + groupBys + ", " + "havings=" + havings + ", " + "orderBys=" + orderBys + ", " + "joins=" + joins + ", " + "resultClassName=" + resultClassName + "]";
    }
}
