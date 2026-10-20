package cn.song.mybatisplus.visualizer.sql.schema;

import cn.song.mybatisplus.visualizer.execute.SqlAstParser;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R46 执行前 Schema 校验的<b>纯逻辑校验器</b>（V65：introspection 三通道/metaCache/未绑定降级全在位）。
 *
 * <p>「渲染正确 ≠ 能执行」——列名错字、字段改型后旧 SQL 未跟，是动态 SQL 最高频的运行时错误。本
 * 对象把渲染产物 SQL 的表/列引用与一份 {@link SchemaSnapshot}（绑定数据源的元数据快照）比对，给出
 * 未识别对象 + 最接近名建议（did-you-mean）。</p>
 *
 * <h2>诚实降级口径（V65 实测结论，勿过度承诺）</h2>
 * 表<b>存在性</b>校验需要「表全集枚举」——插件侧无零成本 API（数据源全量遍历代价随 schema 规模线性，
 * 且「匹配不到」的负命中缓存同义于「未连接/内省未完成」，无法区分「表不存在」与「未预热」）。
 * 故 v1 只校验<b>已预热表的列</b>：
 * <ul>
 * <li>表名在快照里取不到列集 → <b>跳过该语句</b>（不误报「表不存在」，也不报列）；</li>
 * <li>多表/JOIN/子查询/解析失败 → 跳过（列归属无法判定，宁可不报）；</li>
 * <li>只报<b>裸列名</b>（未加表限定）；限定列（{@code t.a}）的别名归属需别名表映射，v1 不猜。</li>
 * </ul>
 *
 * <p>纯逻辑层：只依赖 JSqlParser + SchemaSnapshot（无 IDE 类型），普通 JUnit 可直接覆盖。</p>
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    /** 校验发现（v1 只有未识别列一类；表级发现按上「诚实降级」暂不产出）。 */
    public sealed interface Issue permits UnknownColumn {
        String table();

        String detail();
    }

    /** 未识别列：渲染 SQL 引用的列在快照里不存在。 */
    public record UnknownColumn(String table, String column, @Nullable String suggestion) implements Issue {
        @Override
        public String detail() {
            return suggestion != null ? column + " (did you mean " + suggestion + "?)" : column;
        }
    }

    /**
     * 校验 SQL 的对象引用。
     *
     * @param snapshot 绑定数据源的元数据快照（未绑定数据源时调用方<b>不应调用本方法</b>，见
     *                 {@code SchemaValidationGate}）
     * @return 去重后的发现清单；无可判定对象或解析失败返回空清单（fail-open：不误报）
     */
    @NotNull
    public static List<Issue> validate(@NotNull String sql, @NotNull SchemaSnapshot snapshot) {
        if (sql.isBlank() || snapshot.tables().isEmpty()) {
            return List.of();
        }
        List<Statement> statements;
        try {
            statements = SqlAstParser.INSTANCE.parseStatements(sql);
        } catch (Exception e) {
            return List.of();
        }
        Set<Issue> issues = new LinkedHashSet<>();
        for (Statement statement : statements) {
            Refs refs = refsOf(statement);
            if (refs == null) {
                continue;
            }
            Set<String> columns = snapshot.columnsOf(refs.table());
            if (columns == null) {
                continue;
            }
            for (String column : refs.columns()) {
                if (!columns.contains(column)) {
                    issues.add(new UnknownColumn(refs.table(), column, closestName(column, columns)));
                }
            }
        }
        return List.copyOf(issues);
    }

    /** 单语句的可判定引用：单一表 + 其裸列引用（不可判定返回 null）。 */
    private record Refs(String table, List<String> columns) {
    }

    /**
     * 提取 SQL 涉及的表名（最后一段，保留原大小写；供 IDE 侧按名预热元数据缓存）。
     *
     * <p>只覆盖 v1 校验支持的语句形态（UPDATE/DELETE/单表 SELECT）；解析失败返回空清单。</p>
     */
    @NotNull
    public static List<String> tableNames(@NotNull String sql) {
        if (sql.isBlank()) {
            return List.of();
        }
        List<Statement> statements;
        try {
            statements = SqlAstParser.INSTANCE.parseStatements(sql);
        } catch (Exception e) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Statement statement : statements) {
            Refs refs = refsOf(statement);
            if (refs != null && !names.contains(refs.table())) {
                names.add(refs.table());
            }
        }
        return names;
    }

    private static Refs refsOf(Statement statement) {
        if (statement instanceof Delete delete) {
            if (delete.getTables() != null && delete.getTables().size() > 1) {
                return null;
            }
            if (delete.getJoins() != null && !delete.getJoins().isEmpty()) {
                return null;
            }
            String table = tableNameOf(delete.getTable());
            if (table == null) {
                return null;
            }
            Collector collector = new Collector();
            collector.walk(delete.getWhere());
            if (collector.ambiguous) {
                return null;
            }
            return new Refs(table, List.copyOf(collector.columns));
        }
        if (statement instanceof Update update) {
            if (update.getJoins() != null && !update.getJoins().isEmpty()) {
                return null;
            }
            String table = tableNameOf(update.getTable());
            if (table == null) {
                return null;
            }
            Collector collector = new Collector();
            List<UpdateSet> updateSets = update.getUpdateSets();
            if (updateSets != null) {
                for (UpdateSet set : updateSets) {
                    if (set.getColumns() != null) {
                        for (Column col : set.getColumns()) {
                            collector.addColumn(col);
                        }
                    }
                    if (set.getValues() != null) {
                        for (Expression value : set.getValues()) {
                            collector.walk(value);
                        }
                    }
                }
            }
            collector.walk(update.getWhere());
            if (collector.ambiguous) {
                return null;
            }
            return new Refs(table, List.copyOf(collector.columns));
        }
        if (statement instanceof PlainSelect select) {
            if (select.getJoins() != null && !select.getJoins().isEmpty()) {
                return null;
            }
            if (!(select.getFromItem() instanceof Table fromTable)) {
                return null;
            }
            String table = tableNameOf(fromTable);
            if (table == null) {
                return null;
            }
            Collector collector = new Collector();
            if (select.getSelectItems() != null) {
                for (Object item : select.getSelectItems()) {
                    Object expressionObj = item instanceof net.sf.jsqlparser.statement.select.SelectItem<?> selectItem
                            ? selectItem.getExpression()
                            : null;
                    collector.walk(expressionObj instanceof Expression expr ? expr : null);
                }
            }
            collector.walk(select.getWhere());
            if (collector.ambiguous) {
                return null;
            }
            return new Refs(table, List.copyOf(collector.columns));
        }
        // 其它语句类型（INSERT/MERGE/DDL/SetOperationList 等）v1 不校验
        return null;
    }

    /** 收集裸列引用；命中子查询即置 ambiguous（列归属不可判定 → 调用方跳过整条语句）。 */
    private static final class Collector {
        final Set<String> columns = new LinkedHashSet<>();
        boolean ambiguous = false;

        void addColumn(@Nullable Column column) {
            String name = normalizeIdentifier(column != null ? column.getColumnName() : null);
            if (name == null) {
                return;
            }
            // 限定列（t.a）需别名表映射才能判定归属——v1 不猜
            String qualifier = column != null && column.getTable() != null ? column.getTable().getName() : null;
            if (qualifier != null && !qualifier.isBlank()) {
                return;
            }
            if (!name.isEmpty()) {
                columns.add(name);
            }
        }

        void walk(@Nullable Expression expr) {
            if (expr == null) {
                return;
            }
            if (expr instanceof Column column) {
                addColumn(column);
            } else if (expr instanceof Select) {
                ambiguous = true;
            } else if (expr instanceof ParenthesedExpressionList<?> parenthesedList) {
                for (Object item : parenthesedList) {
                    walk(item instanceof Expression expression ? expression : null);
                }
            } else if (expr instanceof ExpressionList<?> expressionList) {
                for (Object item : expressionList) {
                    walk(item instanceof Expression expression ? expression : null);
                }
            } else if (expr instanceof Parenthesis parenthesis) {
                walk(parenthesis.getExpression());
            } else if (expr instanceof SignedExpression signed) {
                walk(signed.getExpression());
            } else if (expr instanceof BinaryExpression binary) {
                walk(binary.getLeftExpression());
                walk(binary.getRightExpression());
            } else if (expr instanceof InExpression in) {
                walk(in.getLeftExpression());
                walk(in.getRightExpression());
            } else if (expr instanceof IsNullExpression isNull) {
                walk(isNull.getLeftExpression());
            } else if (expr instanceof Function function) {
                if (function.getParameters() != null) {
                    for (Object item : function.getParameters()) {
                        walk(item instanceof Expression expression ? expression : null);
                    }
                }
            }
        }
    }

    /** 表名取最后一段（{@code db.t} / {@code `db`.`t`} → {@code t}）并小写归一。 */
    private static String tableNameOf(@Nullable Table table) {
        return normalizeIdentifier(table != null ? table.getName() : null);
    }

    private static String normalizeIdentifier(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String stripped = raw.trim();
        stripped = stripAll(stripped, "`\"[] ");
        if (stripped.isEmpty()) {
            return null;
        }
        int dot = stripped.lastIndexOf('.');
        if (dot >= 0) {
            stripped = stripped.substring(dot + 1);
        }
        String result = stripAll(stripped, "`\"[] ").toLowerCase();
        return result.isEmpty() ? null : result;
    }

    private static String stripAll(String value, String chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (chars.indexOf(value.charAt(i)) < 0) {
                sb.append(value.charAt(i));
            }
        }
        return sb.toString();
    }

    /**
     * 最接近名建议（did-you-mean）：编辑距离 ≤ 2（且不超过名字长度的一半）取距离最小者；
     * 无候选返回 null（宁可不建议，不给错建议）。
     */
    public static String closestName(@NotNull String target, @NotNull Collection<String> candidates) {
        int maxDistance = Math.min(2, target.length() / 2);
        if (maxDistance <= 0) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = editDistance(target, candidate);
            if (d >= 1 && d <= maxDistance && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** 标准 Levenshtein 距离（滚动数组，O(min(n,m)) 空间）。 */
    private static int editDistance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
