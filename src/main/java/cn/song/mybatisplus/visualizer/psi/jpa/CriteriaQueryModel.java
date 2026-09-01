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
public record CriteriaQueryModel(
        /** 实体类全限定名（from 的实体类）。 */
        @Nullable String entityClassName,
        /** 实体类 PsiClass（可解析时非 null）。 */
        @Nullable PsiClass entityClass,
        /** SELECT 列列表（root.get(...) 形式）；空表示 select(root) 全列。 */
        @NotNull List<SelectColumn> selectColumns,
        /** WHERE 条件中间列表。 */
        @NotNull List<PredicateModel> predicates,
        /** GROUP BY 列列表。 */
        @NotNull List<GroupByModel> groupBys,
        /** HAVING 条件中间列表（顶层以 AND 连接）。 */
        @NotNull List<PredicateModel> havings,
        /** ORDER BY 列表。 */
        @NotNull List<OrderByModel> orderBys,
        /** JOIN 列表。 */
        @NotNull List<JoinModel> joins,
        /** createQuery 的结果类型全限定名（与 entityClassName 通常一致；多态投影时可能不同）。 */
        @Nullable String resultClassName
) {
}
