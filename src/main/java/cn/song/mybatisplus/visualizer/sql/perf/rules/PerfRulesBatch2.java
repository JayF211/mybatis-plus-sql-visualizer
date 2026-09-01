package cn.song.mybatisplus.visualizer.sql.perf.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 第二批性能规则（需要 JSqlParser AST 解析，共 8 条）。
 */
public final class PerfRulesBatch2 {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final PerfRulesBatch2 INSTANCE = new PerfRulesBatch2();

    @NotNull
    public static final List<PerfRule> all = List.of(
            JoinWithoutOnRule.INSTANCE,
            SubqueryDepthRule.INSTANCE,
            InClauseOverflowRule.INSTANCE,
            FunctionOnColumnRule.INSTANCE,
            OrConditionCountRule.INSTANCE,
            DistinctWithJoinRule.INSTANCE,
            GroupByWithoutAggregateRule.INSTANCE,
            NoLimitLargeResultRule.INSTANCE
    );

    private PerfRulesBatch2() {
    }

    public static final class JoinWithoutOnRule implements PerfRule {
        @NotNull
        public static final JoinWithoutOnRule INSTANCE = new JoinWithoutOnRule();

        private JoinWithoutOnRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "join_without_on";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                boolean hasBadJoin = select.getJoins() != null
                        && select.getJoins().stream().anyMatch(PerfRulesBatch2::isSimpleJoinWithoutOn);
                if (hasBadJoin) {
                    warnings.add(new PerfWarning(getRuleId(), PerfSeverity.ERROR, "perf.join_without_on"));
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class SubqueryDepthRule implements PerfRule {
        @NotNull
        public static final SubqueryDepthRule INSTANCE = new SubqueryDepthRule();

        private SubqueryDepthRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "subquery_depth";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                int maxDepth = computeSubqueryDepth(select, 0);
                if (maxDepth > 2) {
                    warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.subquery_depth",
                            List.of(String.valueOf(maxDepth))));
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class InClauseOverflowRule implements PerfRule {
        @NotNull
        public static final InClauseOverflowRule INSTANCE = new InClauseOverflowRule();

        private InClauseOverflowRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "in_clause_overflow";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                if (select.getWhere() != null) {
                    int count = countInOverflows(select.getWhere());
                    if (count > 0) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.in_clause_overflow",
                                List.of(String.valueOf(count))));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class FunctionOnColumnRule implements PerfRule {
        @NotNull
        public static final FunctionOnColumnRule INSTANCE = new FunctionOnColumnRule();

        private FunctionOnColumnRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "function_on_column";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                if (select.getWhere() != null) {
                    List<String> funcs = findFunctionOnColumn(select.getWhere());
                    if (!funcs.isEmpty()) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.function_on_column", funcs));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class OrConditionCountRule implements PerfRule {
        @NotNull
        public static final OrConditionCountRule INSTANCE = new OrConditionCountRule();

        private OrConditionCountRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "or_condition_count";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                if (select.getWhere() != null) {
                    int orCount = countOrExpressions(select.getWhere());
                    if (orCount > 3) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.or_condition_count",
                                List.of(String.valueOf(orCount))));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class DistinctWithJoinRule implements PerfRule {
        @NotNull
        public static final DistinctWithJoinRule INSTANCE = new DistinctWithJoinRule();

        private DistinctWithJoinRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "distinct_with_join";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                boolean isDistinct = select.getDistinct() != null;
                boolean hasJoin = select.getJoins() != null && !select.getJoins().isEmpty();
                if (isDistinct && hasJoin) {
                    warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.distinct_with_join"));
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class GroupByWithoutAggregateRule implements PerfRule {
        @NotNull
        public static final GroupByWithoutAggregateRule INSTANCE = new GroupByWithoutAggregateRule();

        private GroupByWithoutAggregateRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "group_by_without_aggregate";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                GroupByElement groupBy = select.getGroupBy();
                boolean hasGroupBy = groupBy != null
                        && groupBy.getGroupByExpressionList() != null
                        && !groupBy.getGroupByExpressionList().isEmpty();
                boolean hasAggregate = hasAggregateFunction(select);
                if (hasGroupBy && !hasAggregate) {
                    warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.group_by_without_aggregate"));
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    public static final class NoLimitLargeResultRule implements PerfRule {
        @NotNull
        public static final NoLimitLargeResultRule INSTANCE = new NoLimitLargeResultRule();

        private NoLimitLargeResultRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "no_limit_large_result";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (PlainSelect select : collectSelects(ast)) {
                boolean noLimit = select.getLimit() == null;
                int joinCount = select.getJoins() == null ? 0 : select.getJoins().size();
                boolean noWhere = select.getWhere() == null;
                if (noLimit && joinCount >= 2 && noWhere) {
                    warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.no_limit_large_result"));
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    // ------------------------------------------------------------------
    // 辅助函数（原 object 内私有成员 / 扩展函数，转为类私有静态方法）
    // ------------------------------------------------------------------

    /** 收集语句树中所有 PlainSelect（含子查询中的）。 */
    private static List<PlainSelect> collectSelects(Statement ast) {
        List<PlainSelect> result = new ArrayList<>();
        if (ast instanceof PlainSelect ps) {
            result.add(ps);
        } else if (ast instanceof SetOperationList sol) {
            for (Select sel : sol.getSelects()) {
                PlainSelect unwrapped = unwrapPlainSelect(sel);
                if (unwrapped != null) {
                    result.add(unwrapped);
                }
            }
        } else if (ast instanceof ParenthesedSelect ps) {
            PlainSelect unwrapped = unwrapPlainSelect(ps.getSelect());
            if (unwrapped != null) {
                result.add(unwrapped);
            }
        }
        List<PlainSelect> subQueries = new ArrayList<>();
        for (PlainSelect ps : result) {
            extractSubQueries(ps, subQueries);
        }
        result.addAll(subQueries);
        return result;
    }

    private static PlainSelect unwrapPlainSelect(Select select) {
        if (select instanceof PlainSelect ps) {
            return ps;
        } else if (select instanceof ParenthesedSelect ps) {
            return unwrapPlainSelect(ps.getSelect());
        }
        return null;
    }

    private static void extractSubQueries(PlainSelect select, List<PlainSelect> out) {
        net.sf.jsqlparser.schema.FromItem fromItem = select.getFromItem();
        if (fromItem instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub) {
                out.add(psub);
                extractSubQueries(psub, out);
            }
        }
        findWhereSubqueries(select.getWhere(), out);
    }

    private static void findWhereSubqueries(Expression expr, List<PlainSelect> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof ParenthesedSelect ps) {
            PlainSelect p = unwrapPlainSelect(ps.getSelect());
            if (p != null) {
                out.add(p);
                extractSubQueries(p, out);
            }
        } else if (expr instanceof AndExpression and) {
            findWhereSubqueries(and.getLeftExpression(), out);
            findWhereSubqueries(and.getRightExpression(), out);
        } else if (expr instanceof OrExpression or) {
            findWhereSubqueries(or.getLeftExpression(), out);
            findWhereSubqueries(or.getRightExpression(), out);
        } else if (expr instanceof InExpression in) {
            findWhereSubqueries(in.getRightExpression(), out);
            findWhereSubqueries(in.getLeftExpression(), out);
        } else if (expr instanceof ExistsExpression ex) {
            findWhereSubqueries(ex.getRightExpression(), out);
        }
    }

    /** 扩展函数 {@code Join.isSimpleJoinWithoutOn()} 的等价静态方法。 */
    private static boolean isSimpleJoinWithoutOn(Join join) {
        boolean hasOn = join.getOnExpressions() != null && !join.getOnExpressions().isEmpty();
        boolean hasUsing = join.getUsingColumns() != null && !join.getUsingColumns().isEmpty();
        return !hasOn && !hasUsing && !join.isNatural() && !join.isCross() && !join.isSemi()
                && !join.isRight() && !join.isFull() && !join.isLeft() && !join.isSimple();
    }

    private static int computeSubqueryDepth(PlainSelect select, int currentDepth) {
        int[] maxDepth = {currentDepth};
        net.sf.jsqlparser.schema.FromItem fromItem = select.getFromItem();
        if (fromItem instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub) {
                maxDepth[0] = Math.max(maxDepth[0], computeSubqueryDepth(psub, currentDepth + 1));
            }
        }
        if (select.getWhere() != null) {
            findWhereSubqueryDepths(select.getWhere(), depth ->
                    maxDepth[0] = Math.max(maxDepth[0], currentDepth + 1 + depth));
        }
        return maxDepth[0];
    }

    private static void findWhereSubqueryDepths(Expression expr, IntConsumer callback) {
        if (expr instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub) {
                callback.accept(computeSubqueryDepth(psub, 0));
            }
        } else if (expr instanceof AndExpression and) {
            findWhereSubqueryDepths(and.getLeftExpression(), callback);
            findWhereSubqueryDepths(and.getRightExpression(), callback);
        } else if (expr instanceof OrExpression or) {
            findWhereSubqueryDepths(or.getLeftExpression(), callback);
            findWhereSubqueryDepths(or.getRightExpression(), callback);
        } else if (expr instanceof InExpression in) {
            findWhereSubqueryDepths(in.getRightExpression(), callback);
            findWhereSubqueryDepths(in.getLeftExpression(), callback);
        } else if (expr instanceof ExistsExpression ex) {
            findWhereSubqueryDepths(ex.getRightExpression(), callback);
        }
    }

    private static int countInOverflows(Expression expr) {
        int count = 0;
        if (expr instanceof InExpression in) {
            Expression rightItems = in.getRightExpression();
            if (rightItems instanceof ExpressionList<?> el) {
                if (el.size() > 100) {
                    count++;
                }
            }
        } else if (expr instanceof AndExpression and) {
            count += countInOverflows(and.getLeftExpression());
            count += countInOverflows(and.getRightExpression());
        } else if (expr instanceof OrExpression or) {
            count += countInOverflows(or.getLeftExpression());
            count += countInOverflows(or.getRightExpression());
        }
        return count;
    }

    private static List<String> findFunctionOnColumn(Expression expr) {
        List<String> result = new ArrayList<>();
        if (expr instanceof Function fn) {
            ExpressionList<?> params = fn.getParameters();
            if (params != null) {
                for (Expression param : params.getExpressions()) {
                    if (param instanceof Column col) {
                        result.add(fn.getName() + "(" + col.getColumnName() + ")");
                    }
                }
                // 递归检查嵌套参数中的函数
                for (Expression param : params.getExpressions()) {
                    result.addAll(findFunctionOnColumn(param));
                }
            }
        } else if (expr instanceof AndExpression and) {
            result.addAll(findFunctionOnColumn(and.getLeftExpression()));
            result.addAll(findFunctionOnColumn(and.getRightExpression()));
        } else if (expr instanceof OrExpression or) {
            result.addAll(findFunctionOnColumn(or.getLeftExpression()));
            result.addAll(findFunctionOnColumn(or.getRightExpression()));
        } else if (expr instanceof InExpression in) {
            result.addAll(findFunctionOnColumn(in.getLeftExpression()));
            result.addAll(findFunctionOnColumn(in.getRightExpression()));
        } else if (expr instanceof ExistsExpression ex) {
            result.addAll(findFunctionOnColumn(ex.getRightExpression()));
        } else if (expr instanceof ComparisonOperator cmp) {
            // 比较运算符：WHERE UPPER(col) = 'value'
            result.addAll(findFunctionOnColumn(cmp.getLeftExpression()));
            result.addAll(findFunctionOnColumn(cmp.getRightExpression()));
        } else if (expr instanceof ParenthesedSelect ps) {
            // 子查询内部也检查
            if (ps.getSelect() instanceof PlainSelect psub && psub.getWhere() != null) {
                result.addAll(findFunctionOnColumn(psub.getWhere()));
            }
        }
        return result.stream().distinct().toList();
    }

    private static int countOrExpressions(Expression expr) {
        if (expr instanceof OrExpression or) {
            return 1 + countOrExpressions(or.getLeftExpression()) + countOrExpressions(or.getRightExpression());
        } else if (expr instanceof AndExpression and) {
            return countOrExpressions(and.getLeftExpression()) + countOrExpressions(and.getRightExpression());
        }
        return 0;
    }

    private static boolean hasAggregateFunction(PlainSelect select) {
        for (SelectItem item : select.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof Function fn) {
                String name = fn.getName().toUpperCase();
                if (AGGREGATE_FUNCTIONS.contains(name)) {
                    return true;
                }
            }
            if (expr instanceof MySQLGroupConcat) {
                return true;
            }
        }
        if (select.getHaving() != null) {
            return true;
        }
        return false;
    }

    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MAX", "MIN", "GROUP_CONCAT",
            "STRING_AGG", "ARRAY_AGG", "BIT_AND", "BIT_OR"
    );
}
