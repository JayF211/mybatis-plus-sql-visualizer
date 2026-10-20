// 自 master ui/annotate/PreviewUnknownColumnPlanner.kt 移植（UI025：错列标注纯逻辑规划器）
package cn.song.mybatisplus.visualizer.ui.annotate;

import cn.song.mybatisplus.visualizer.execute.SqlAstParser;
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 错列标注的<b>纯逻辑规划器</b>：把 {@link SchemaValidator} 的发现映射到 SQL 文本里的真实列 token。
 *
 * <h2>为什么偏移必须取自 AST</h2>
 *
 * 需求红线是「标注落在真实列 token 上」且「不用字符串 indexOf 猜」。本对象用 JSqlParser 5.3
 * 的 AST token 位置（{@code SimpleNode.jjtGetFirstToken()/jjtGetLastToken()} 的
 * {@code beginLine/beginColumn/endLine/endColumn}）换算文档偏移：位置由<b>解析器</b>给出，不依赖列名
 * 在文本里首次出现的位置——SQL 里同名列出现在注释、字符串字面量、别名或别的表里时都不会标错位。
 *
 * <h2>与校验器的关系（宁缺勿错）</h2>
 *
 * 发现清单由 {@link SchemaValidator} 产出（单表、无 JOIN/CTE/子查询、只认裸列），本对象只做
 * 「发现 → token」的定位，不扩大校验范围：
 * <ul>
 *  <li>只收集 UPDATE 的 SET 列/值/WHERE、DELETE 的 WHERE、单表 SELECT 的投影/WHERE——与
 *      {@code SchemaValidator.refsOf} 的遍历面逐处对齐；其它子句（ORDER BY 等）即使含同名列也不会
 *      被选中（校验器没有对它下结论，规划器也不替它下结论）；</li>
 *  <li>限定列（{@code t.a}）不参与匹配（校验器不校验限定列）；</li>
 *  <li>每个 issue 只标<b>首个</b>匹配 token（需求「同一 issue 只标一次」）；</li>
 *  <li>token 文本归一化后必须与列名相等，否则放弃该条（解析器位置与文本不一致时不猜）。</li>
 * </ul>
 *
 * <p>纯逻辑：只依赖 JSqlParser + {@link SchemaValidator.Issue}，无 IDE 类型，可在普通 JUnit 直测。</p>
 */
public final class PreviewUnknownColumnPlanner {

    private PreviewUnknownColumnPlanner() {
    }

    /** 一条可渲染的标注（偏移为文档绝对偏移，{@code endOffset} 开区间）。 */
    public record Mark(int startOffset, int endOffset, String column, String table,
                       @Nullable String suggestion) {
    }

    /**
     * 把发现清单映射为标注清单。
     *
     * @param sql    与校验器<b>同一份</b>文档文本
     * @param issues 校验器发现（只消费 {@link SchemaValidator.UnknownColumn}）
     * @return 按发现顺序的标注；解析失败/位置不可证一律返回空清单（fail-open，不误报）
     */
    @NotNull
    public static List<Mark> plan(@NotNull String sql, @NotNull List<SchemaValidator.Issue> issues) {
        List<SchemaValidator.UnknownColumn> unknown = new ArrayList<>();
        for (SchemaValidator.Issue issue : issues) {
            if (issue instanceof SchemaValidator.UnknownColumn unknownColumn) {
                unknown.add(unknownColumn);
            }
        }
        if (unknown.isEmpty() || sql.isBlank()) {
            return List.of();
        }
        List<Occurrence> occurrences;
        try {
            occurrences = collectOccurrences(sql);
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            occurrences = List.of();
        }
        if (occurrences.isEmpty()) {
            return List.of();
        }
        int[] starts = lineStarts(sql);
        java.util.Set<String> emitted = new HashSet<>();
        List<Mark> marks = new ArrayList<>(unknown.size());
        for (SchemaValidator.UnknownColumn issue : unknown) {
            // 同一 issue（表 + 列）只标一次：校验器本身已去重，这里再挡一道，防调用方传入重复清单。
            if (!emitted.add(issue.table() + "\u0000" + issue.column())) {
                continue;
            }
            Occurrence hit = null;
            for (Occurrence occurrence : occurrences) {
                if (occurrence.table() == null
                        && occurrence.name().equalsIgnoreCase(issue.column())) {
                    hit = occurrence;
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            int[] range = offsetRange(sql, starts, hit);
            if (range == null) {
                continue;
            }
            marks.add(new Mark(range[0], range[1], issue.column(), issue.table(), issue.suggestion()));
        }
        return marks;
    }

    // ------------------------------------------------------------------
    // AST 遍历（与 SchemaValidator.refsOf 的可判定语句形态逐处对齐）
    // ------------------------------------------------------------------

    private record Occurrence(String name, @Nullable String table,
                              int beginLine, int beginColumn, int endLine, int endColumn) {
    }

    @NotNull
    private static List<Occurrence> collectOccurrences(@NotNull String sql) throws Exception {
        List<Occurrence> out = new ArrayList<>();
        for (Statement statement : SqlAstParser.INSTANCE.parseStatements(sql)) {
            collectStatement(statement, out);
        }
        return out;
    }

    private static void collectStatement(@NotNull Statement statement,
                                         @NotNull List<Occurrence> out) {
        statement.accept(new StatementVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Update update, S context) {
                if (update.getUpdateSets() != null) {
                    for (net.sf.jsqlparser.statement.update.UpdateSet set : update.getUpdateSets()) {
                        if (set.getColumns() != null) {
                            for (Column column : set.getColumns()) {
                                addColumn(column, out);
                            }
                        }
                        if (set.getValues() != null) {
                            for (Expression value : set.getValues()) {
                                walk(value, out);
                            }
                        }
                    }
                }
                walk(update.getWhere(), out);
                return null;
            }

            @Override
            public <S> Void visit(Delete delete, S context) {
                walk(delete.getWhere(), out);
                return null;
            }

            @Override
            public <S> Void visit(Select select, S context) {
                select.accept(new SelectVisitorAdapter<Void>() {
                    @Override
                    public <S2> Void visit(PlainSelect plain, S2 context) {
                        if (plain.getSelectItems() != null) {
                            for (net.sf.jsqlparser.statement.select.SelectItem<?> item : plain.getSelectItems()) {
                                walk(item.getExpression(), out);
                            }
                        }
                        walk(plain.getWhere(), out);
                        return null;
                    }
                }, null);
                return null;
            }
        }, null);
    }

    private static void walk(@Nullable Expression expression, @NotNull List<Occurrence> out) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                addColumn(column, out);
                return null;
            }
        }, null);
    }

    private static void addColumn(@Nullable Column column, @NotNull List<Occurrence> out) {
        if (column == null) {
            return;
        }
        String name = normalize(column.getColumnName());
        if (name == null) {
            return;
        }
        String table = column.getTable() != null ? normalize(column.getTable().getName()) : null;
        net.sf.jsqlparser.parser.SimpleNode node = column.getASTNode();
        if (node == null) {
            return;
        }
        net.sf.jsqlparser.parser.Token first = node.jjtGetFirstToken();
        net.sf.jsqlparser.parser.Token last = node.jjtGetLastToken();
        if (first == null || last == null) {
            return;
        }
        out.add(new Occurrence(name, table,
                first.beginLine, first.beginColumn, last.endLine, last.endColumn));
    }

    // ------------------------------------------------------------------
    // 位置换算
    // ------------------------------------------------------------------

    /**
     * 每行起始偏移（0-based 行号）；{@code \n}、{@code \r\n}、单独 {@code \r} 都算一次换行（与 JSqlParser 同口径）。
     */
    @NotNull
    private static int[] lineStarts(@NotNull String sql) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\r') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\n') {
                    i++;
                }
                starts.add(i + 1);
            } else if (c == '\n') {
                starts.add(i + 1);
            }
            i++;
        }
        int[] result = new int[starts.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = starts.get(k);
        }
        return result;
    }

    /**
     * AST 的 1-based 行列 → 文档绝对偏移（{@code endColumn} 是<b>闭区间</b>，故开区间上界为 +1）。
     *
     * <p>越界或 token 文本与列名不一致 → null（不猜）。返回 {@code int[2]}：{@code [0]=start，
     * [1]=endExclusive}。</p>
     */
    @Nullable
    private static int[] offsetRange(@NotNull String sql, @NotNull int[] lineStarts,
                                     @NotNull Occurrence occurrence) {
        Integer beginLineStart = lineStartAt(lineStarts, occurrence.beginLine() - 1);
        Integer endLineStart = lineStartAt(lineStarts, occurrence.endLine() - 1);
        if (beginLineStart == null || endLineStart == null) {
            return null;
        }
        int start = beginLineStart + occurrence.beginColumn() - 1;
        int endExclusive = endLineStart + occurrence.endColumn();
        if (start < 0 || start >= endExclusive || endExclusive > sql.length()) {
            return null;
        }
        String token = sql.substring(start, endExclusive);
        if (!java.util.Objects.equals(normalize(token), occurrence.name())) {
            return null;
        }
        return new int[]{start, endExclusive};
    }

    @Nullable
    private static Integer lineStartAt(@NotNull int[] lineStarts, int index) {
        if (index < 0 || index >= lineStarts.length) {
            return null;
        }
        return lineStarts[index];
    }

    /** 与 {@link SchemaValidator} 的归一化同口径：去空白/成对引用符、取最后一段、小写。 */
    @Nullable
    private static String normalize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String stripped = trimChars(raw.trim(), QUOTE_CHARS);
        if (stripped.isEmpty()) {
            return null;
        }
        int dot = stripped.lastIndexOf('.');
        String lastSegment = dot >= 0 ? stripped.substring(dot + 1) : stripped;
        String result = trimChars(lastSegment, QUOTE_CHARS).toLowerCase(Locale.ROOT);
        return result.isEmpty() ? null : result;
    }

    /** Kotlin {@code String.trim(vararg chars)} 等价：去两端出现的任一给定字符。 */
    private static final String QUOTE_CHARS = "`\"[] ";

    @NotNull
    private static String trimChars(@NotNull String raw, @NotNull String chars) {
        int begin = 0;
        int end = raw.length();
        while (begin < end && chars.indexOf(raw.charAt(begin)) >= 0) {
            begin++;
        }
        while (end > begin && chars.indexOf(raw.charAt(end - 1)) >= 0) {
            end--;
        }
        return raw.substring(begin, end);
    }
}
