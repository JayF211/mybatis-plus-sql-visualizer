// T-SyncK2JMaster309 自 master util/BatchPredicateMerge.kt 移植
package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.execute.SqlAstParser;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.delete.Delete;
import kotlin.Pair;

/**
 * 批量等值谓词 IN 合并预览（T-BatchInMerge / O-14，吸收 DBX `data_grid_sql.rs`
 * push_mysql_delete_batches / parse_mysql_equality_predicate 形态）：
 * foreach 批删日志还原后的几十条同构 `DELETE FROM t WHERE col = k` 生成一条
 * `DELETE FROM t WHERE col IN (k1, k2, …)` <b>参考形态</b>。
 *
 * <p>边界红线：<b>合并仅用于预览参考，执行文本永远是逐条原样</b>——等价性（NULL 语义/类型/
 * 方言函数差异）不由本插件背书；调用方文案必须双保险「仅预览参考，执行仍逐条」。
 *
 * <p>判定（全部满足才进合并集；任一不满足的单条语句留在集外，集外有语句时调用方按
 * 「N/M 条」诚实披露，绝不静默丢语句）：
 * <ul>
 *  <li>DELETE 语句（UPDATE 不合并——SET 不同构；其他类型不适用）；</li>
 *  <li>同表（表名忽略大小写）；</li>
 *  <li>WHERE 为单列等值谓词 `col = 字面量`（同一列；字面量 = 数值/字符串/十六进制/带符号数值，
 *      列对列、函数包裹、子查询、IS NULL、复合谓词任一命中即出局）；</li>
 *  <li>合并集 ≥ 2 条才产出（1 条无合并价值）。</li>
 * </ul>
 *
 * <p>双约束（DBX 对齐）：行数上限 {@link #MAX_ROWS}（默认 500）+ 字节预算 {@link #MAX_BYTES}（默认 8KB），
 * 超限截断计数（{@link MergePreview#hiddenValues()}）交调用方以「…等 N 条」披露。
 * 字面量<b>原样文本拼接</b>（不转义不再格式化——含引号值照抄显示，等价性不由展示形态承担）。
 *
 * <p>解析失败 / 空 / 无合并集 → null（回退原形态，绝不部分合并）。
 * 纯函数零 UI 依赖，普通 JUnit 可直接覆盖。
 */
public final class BatchPredicateMerge {

    private BatchPredicateMerge() {}

    /** 行数上限（DBX 批删合批对齐）。 */
    public static final int MAX_ROWS = 500;

    /** 合并产物字节预算（展示串超限截断计数）。 */
    public static final int MAX_BYTES = 8 * 1024;

    /**
     * 合并预览产物。
     *
     * @param table           目标表名（原样大小写）
     * @param column          等值谓词列名（原样大小写）
     * @param values          字面量渲染清单（双约束截断后的可见部分，≥1 条）
     * @param hiddenValues    因行数上限/字节预算被截断的值个数（0 = 无截断）
     * @param coveredCount    进入合并集的语句数
     * @param totalStatements 输入语句总数（&gt;coveredCount 时调用方按「N/M 条」披露异构语句在场）
     */
    public record MergePreview(
            /** 目标表名（原样大小写）。 */
            @NotNull String table,
            /** 等值谓词列名（原样大小写）。 */
            @NotNull String column,
            /** 字面量渲染清单（双约束截断后的可见部分，≥1 条）。 */
            @NotNull List<String> values,
            /** 因行数上限/字节预算被截断的值个数（0 = 无截断）。 */
            int hiddenValues,
            /** 进入合并集的语句数。 */
            int coveredCount,
            /** 输入语句总数。 */
            int totalStatements
    ) {
        /** 合并参考 SQL（不含截断后缀——后缀文案属 UI 层 i18n）。 */
        @NotNull
        public String mergedSql() {
            return "DELETE FROM " + table + " WHERE " + column + " IN (" + String.join(", ", values) + ")";
        }
    }

    /** 单条语句的解析结果：等值谓词或出局。 */
    private record ParsedDelete(
            /** 表名。 */
            @NotNull String table,
            /** 列名。 */
            @NotNull String column,
            /** 字面量原样文本。 */
            @NotNull String value
    ) {
    }

    /**
     * 生成合并预览；无可合并集（&lt;2 条同构等值删除）返回 null。
     * 输入建议为切分产物（util/SqlStatementSplitter.split）；注释与解析失败的单条出局。
     */
    @Nullable
    public static MergePreview mergePreview(@NotNull List<String> statements) {
        if (statements.size() < 2) return null;
        List<ParsedDelete> parsed = new ArrayList<>(statements.size());
        for (String statement : statements) {
            ParsedDelete p = parseEqualityDelete(statement);
            if (p != null) parsed.add(p);
        }
        if (parsed.size() < 2) return null;
        // 同表同列分组取最大组（组内全同构；异构组语句留在集外由计数披露）
        Map<Pair<String, String>, List<ParsedDelete>> groups = new LinkedHashMap<>();
        for (ParsedDelete p : parsed) {
            groups.computeIfAbsent(new Pair<>(p.table().toLowerCase(Locale.ROOT), p.column().toLowerCase(Locale.ROOT)),
                    k -> new ArrayList<>()).add(p);
        }
        List<ParsedDelete> best = null;
        for (List<ParsedDelete> g : groups.values()) {
            if (best == null || g.size() > best.size()) best = g;
        }
        if (best == null || best.size() < 2) return null;
        ParsedDelete first = best.get(0);
        List<String> rendered = new ArrayList<>(best.size());
        for (ParsedDelete p : best) rendered.add(p.value());
        // 行数上限
        List<String> visibleByRows = rendered.size() > MAX_ROWS ? rendered.subList(0, MAX_ROWS) : rendered;
        int hidden = rendered.size() - visibleByRows.size();
        // 字节预算：前缀 + 已选值逗号占位后逐值试探
        String prefix = "DELETE FROM " + first.table() + " WHERE " + first.column() + " IN (";
        int budget = MAX_BYTES - prefix.length() - 2; // 预留 ") " 与余量
        List<String> visible = new ArrayList<>(visibleByRows.size());
        for (int index = 0; index < visibleByRows.size(); index++) {
            String value = visibleByRows.get(index);
            int cost = value.length() + (index > 0 ? 2 : 0);
            if (!visible.isEmpty() && budget - cost < 0) break;
            budget -= cost;
            visible.add(value);
        }
        hidden += visibleByRows.size() - visible.size();
        return new MergePreview(
                first.table(),
                first.column(),
                visible,
                hidden,
                best.size(),
                statements.size());
    }

    /** 解析单条 `DELETE FROM t WHERE col = 字面量`；非该形态（含解析失败）返回 null。 */
    @Nullable
    private static ParsedDelete parseEqualityDelete(@NotNull String statement) {
        String cleaned = SqlCommentStripper.strip(statement);
        if (cleaned.isBlank()) return null;
        // T-PS7-03 收拢：走 SqlAstParser.parseSingle——方括号引用的 DELETE 不再判「不可合并」
        net.sf.jsqlparser.statement.Statement parsed;
        try {
            parsed = SqlAstParser.INSTANCE.parseSingle(cleaned);
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Delete)) return null;
        Delete delete = (Delete) parsed;
        if (delete.getTable() == null) return null;
        String table = delete.getTable().getFullyQualifiedName();
        if (!(delete.getWhere() instanceof EqualsTo)) return null;
        EqualsTo where = (EqualsTo) delete.getWhere();
        if (!(where.getLeftExpression() instanceof Column)) return null;
        Column column = (Column) where.getLeftExpression();
        if (!isPlainLiteral(where.getRightExpression())) return null;
        return new ParsedDelete(table, column.getColumnName(), renderLiteral(where.getRightExpression()));
    }

    /** 纯字面量判定：数值/字符串/十六进制/带符号数值；列/函数/子查询/NULL 一律出局。 */
    private static boolean isPlainLiteral(@NotNull Expression expr) {
        if (expr instanceof LongValue || expr instanceof DoubleValue
                || expr instanceof HexValue || expr instanceof StringValue) {
            return true;
        }
        if (expr instanceof SignedExpression) {
            Expression inner = ((SignedExpression) expr).getExpression();
            return inner instanceof LongValue || inner instanceof DoubleValue;
        }
        return false;
    }

    /** 字面量渲染：原样 toString（StringValue 含引号形态），不再转义。 */
    @NotNull
    private static String renderLiteral(@NotNull Expression expr) {
        if (expr instanceof SignedExpression) {
            return ((SignedExpression) expr).getSign() + renderLiteral(((SignedExpression) expr).getExpression());
        }
        return expr.toString();
    }
}
