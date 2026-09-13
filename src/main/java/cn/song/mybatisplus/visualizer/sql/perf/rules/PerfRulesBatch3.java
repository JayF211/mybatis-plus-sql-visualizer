package cn.song.mybatisplus.visualizer.sql.perf.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.song.mybatisplus.visualizer.sql.perf.PerfRule;
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 第三批性能规则（高级规则，共 6 条）。
 */
public final class PerfRulesBatch3 {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final PerfRulesBatch3 INSTANCE = new PerfRulesBatch3();

    /** 所有第三批规则实例。 */
    @NotNull
    public static final List<PerfRule> all = List.of(
            CorrelatedSubqueryRule.INSTANCE,
            ImplicitTypeConversionRule.INSTANCE,
            BetweenOnDatesRule.INSTANCE,
            NestedSubqueryInSelectRule.INSTANCE,
            OffsetPaginationRule.INSTANCE,
            MultipleGroupByOrdersRule.INSTANCE
    );

    private PerfRulesBatch3() {
    }

    /**
     * `correlated_subquery`：关联子查询（引用外层列，每行执行一次）。
     */
    public static final class CorrelatedSubqueryRule implements PerfRule {
        @NotNull
        public static final CorrelatedSubqueryRule INSTANCE = new CorrelatedSubqueryRule();

        private CorrelatedSubqueryRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "correlated_subquery";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps) {
                    Set<String> outerAliases = collectTableAliases(ps);
                    if (hasCorrelatedSubquery(ps, outerAliases)) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN, "perf.correlated_subquery"));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    /**
     * `implicit_type_conversion`：列与字面量类型不匹配。
     */
    public static final class ImplicitTypeConversionRule implements PerfRule {
        @NotNull
        public static final ImplicitTypeConversionRule INSTANCE = new ImplicitTypeConversionRule();

        private ImplicitTypeConversionRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "implicit_type_conversion";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps && ps.getWhere() != null) {
                    List<String> mismatches = findTypeMismatches(ps.getWhere());
                    if (!mismatches.isEmpty()) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.WARN,
                                "perf.implicit_type_conversion", mismatches));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    /**
     * `between_on_dates`：BETWEEN 日期范围查询。
     */
    public static final class BetweenOnDatesRule implements PerfRule {
        @NotNull
        public static final BetweenOnDatesRule INSTANCE = new BetweenOnDatesRule();

        private BetweenOnDatesRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "between_on_dates";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps && ps.getWhere() != null) {
                    int betweenCount = countBetweenExpressions(ps.getWhere());
                    if (betweenCount > 0) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO,
                                "perf.between_on_dates", List.of(String.valueOf(betweenCount))));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    /**
     * `nested_subquery_in_select`：SELECT 子句子查询。
     */
    public static final class NestedSubqueryInSelectRule implements PerfRule {
        @NotNull
        public static final NestedSubqueryInSelectRule INSTANCE = new NestedSubqueryInSelectRule();

        private NestedSubqueryInSelectRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "nested_subquery_in_select";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps) {
                    int count = countSelectSubqueries(ps);
                    if (count > 0) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO,
                                "perf.nested_subquery_in_select", List.of(String.valueOf(count))));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    /**
     * `offset_pagination`：大偏移量分页。
     */
    public static final class OffsetPaginationRule implements PerfRule {
        @NotNull
        public static final OffsetPaginationRule INSTANCE = new OffsetPaginationRule();

        private OffsetPaginationRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "offset_pagination";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps) {
                    long offsetValue = getOffsetValue(ps);
                    if (offsetValue > 1000) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO,
                                "perf.offset_pagination", List.of(String.valueOf(offsetValue))));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    /**
     * `multiple_group_by_orders`：复杂 GROUP BY + ORDER BY 组合。
     */
    public static final class MultipleGroupByOrdersRule implements PerfRule {
        @NotNull
        public static final MultipleGroupByOrdersRule INSTANCE = new MultipleGroupByOrdersRule();

        private MultipleGroupByOrdersRule() {
        }

        @Override
        @NotNull
        public String getRuleId() {
            return "multiple_group_by_orders";
        }

        @Override
        @NotNull
        public List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast) {
            if (ast == null) {
                return List.of();
            }
            List<PerfWarning> warnings = new ArrayList<>();
            for (Select select : collectSelects(ast)) {
                if (select instanceof PlainSelect ps) {
                    int groupByCount = (ps.getGroupBy() != null
                            && ps.getGroupBy().getGroupByExpressionList() != null)
                            ? ps.getGroupBy().getGroupByExpressionList().size() : 0;
                    int orderByCount = ps.getOrderByElements() == null ? 0 : ps.getOrderByElements().size();
                    if (groupByCount >= 3 && orderByCount >= 2) {
                        warnings.add(new PerfWarning(getRuleId(), PerfSeverity.INFO, "perf.multiple_group_by_orders"));
                    }
                }
            }
            return warnings.stream().limit(1).toList();
        }
    }

    // ------------------------------------------------------------------
    // 辅助函数（原 object 内私有成员，转为类私有静态方法）
    // ------------------------------------------------------------------

    private static List<Select> collectSelects(Statement ast) {
        List<Select> result = new ArrayList<>();
        if (ast instanceof PlainSelect ps) {
            result.add(new ParenthesedSelect(ps));
        } else if (ast instanceof SetOperationList sol) {
            for (Select sel : sol.getSelects()) {
                result.add(sel);
            }
        } else if (ast instanceof ParenthesedSelect ps) {
            result.add(ps);
        }
        return result;
    }

    private static Set<String> collectTableAliases(PlainSelect select) {
        Set<String> aliases = new LinkedHashSet<>();
        FromItem fromItem = select.getFromItem();
        if (fromItem instanceof Table tbl) {
            if (tbl.getAlias() != null) {
                aliases.add(tbl.getAlias().getName().toLowerCase());
            }
            aliases.add(tbl.getName().toLowerCase());
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                FromItem right = join.getRightItem();
                if (right instanceof Table tbl) {
                    if (tbl.getAlias() != null) {
                        aliases.add(tbl.getAlias().getName().toLowerCase());
                    }
                    aliases.add(tbl.getName().toLowerCase());
                }
            }
        }
        return aliases;
    }

    private static boolean hasCorrelatedSubquery(PlainSelect select, Set<String> outerAliases) {
        boolean found = false;
        if (select.getFromItem() instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub && psub.getWhere() != null) {
                found = referencesOuterAlias(psub.getWhere(), outerAliases);
            }
        }
        if (!found) {
            found = checkWhereForCorrelated(select.getWhere(), outerAliases);
        }
        return found;
    }

    private static boolean checkWhereForCorrelated(Expression expr, Set<String> outerAliases) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub && psub.getWhere() != null) {
                return referencesOuterAlias(psub.getWhere(), outerAliases);
            }
            return false;
        } else if (expr instanceof AndExpression and) {
            return checkWhereForCorrelated(and.getLeftExpression(), outerAliases)
                    || checkWhereForCorrelated(and.getRightExpression(), outerAliases);
        } else if (expr instanceof OrExpression or) {
            return checkWhereForCorrelated(or.getLeftExpression(), outerAliases)
                    || checkWhereForCorrelated(or.getRightExpression(), outerAliases);
        }
        return false;
    }

    private static boolean referencesOuterAlias(Expression expr, Set<String> outerAliases) {
        if (expr instanceof Column col) {
            if (col.getTable() != null) {
                return outerAliases.contains(col.getTable().getName().toLowerCase());
            }
            return false;
        } else if (expr instanceof AndExpression and) {
            return referencesOuterAlias(and.getLeftExpression(), outerAliases)
                    || referencesOuterAlias(and.getRightExpression(), outerAliases);
        } else if (expr instanceof OrExpression or) {
            return referencesOuterAlias(or.getLeftExpression(), outerAliases)
                    || referencesOuterAlias(or.getRightExpression(), outerAliases);
        } else if (expr instanceof ParenthesedSelect ps) {
            Select sub = ps.getSelect();
            if (sub instanceof PlainSelect psub && psub.getWhere() != null) {
                return referencesOuterAlias(psub.getWhere(), outerAliases);
            }
            return false;
        }
        return false;
    }

    private static List<String> findTypeMismatches(Expression expr) {
        List<String> result = new ArrayList<>();
        Matcher matches = PRB3_MISMATCH_PATTERN.matcher(expr.toString());
        while (matches.find()) {
            String col = matches.group(1);
            if (col.toLowerCase().matches(PRB3_NUMERIC_LIKE_COLUMN)) {
                result.add(col);
            }
        }
        return result.stream().distinct().toList();
    }

    // T-Perf-23：函数体内 Pattern.compile(...) 提为常量（热路径免重复编译）
    private static final Pattern PRB3_MISMATCH_PATTERN = Pattern.compile("(`?\\w+`?)\\s*=\\s*'([^']*)'");
    private static final String PRB3_NUMERIC_LIKE_COLUMN = ".*(id|num|count|age|qty|price|amount|code|status).*";

    private static int countBetweenExpressions(Expression expr) {
        if (expr instanceof Between) {
            return 1;
        } else if (expr instanceof AndExpression and) {
            return countBetweenExpressions(and.getLeftExpression()) + countBetweenExpressions(and.getRightExpression());
        } else if (expr instanceof OrExpression or) {
            return countBetweenExpressions(or.getLeftExpression()) + countBetweenExpressions(or.getRightExpression());
        }
        return 0;
    }

    private static int countSelectSubqueries(PlainSelect select) {
        int count = 0;
        for (SelectItem item : select.getSelectItems()) {
            if (item.getExpression() instanceof ParenthesedSelect) {
                count++;
            }
        }
        return count;
    }

    private static long getOffsetValue(PlainSelect select) {
        Offset offsetObj = select.getOffset();
        if (offsetObj == null || offsetObj.getOffset() == null) {
            return 0;
        }
        Expression offset = offsetObj.getOffset();
        if (offset instanceof Number num) {
            return num.longValue();
        }
        return 0;
    }
}
