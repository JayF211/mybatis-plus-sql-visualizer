// T-SyncK2JMaster309 自 master util/ExplainIndexAdvisor.kt 移植
package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXPLAIN 计划「索引缺失」建议识别器（T-ExplainIndex T-EI-02，全景审计 N-23）。
 *
 * <p>把高频可判定模式提炼为结构化建议（命中规则/涉及对象/建议文案），只提示不执行不改 SQL；
 * 无命中返回空清单（不噪音）；方言不支持静默降级（调用方拿到空清单即与现状一致）。
 *
 * <p>落点定案（T-EI-01，实施核实）：IDE 内嵌原生控制台的结果行<b>无程序化读取面</b>（插件对结果区
 * 仅做页签选择/组件换入，网格数据由 DBN 托管，硬读需侵入平台内部网格模型）；MCP explain 链
 * 自持行列（{@code cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult}）——建议节挂在
 * 该输出（T-NoticeChannel notices 同款承载位）。IDE 面建议条挂账（硬做=备选 B 自渲染，成本
 * 收益不成立）。
 *
 * <p>两方言先行（计划口径）：MySQL 表格式（type=ALL 全表扫 / key=NULL 且 rows 超阈 /
 * Extra 含 Using filesort/temporary）、PG 文本树（大表 Seq Scan）。
 * 单源约定：规则集在本类，任何未来 plan-text 消费方（IDE 面备选 B、perf 工具扩展）
 * 都从这里取，不复制第二套（与 perf_advice 的静态 SQL 规则集是两个互补面，不互斥）。
 */
public final class ExplainIndexAdvisor {

    private ExplainIndexAdvisor() {}

    /** 全表扫描行数阈值（低于此不提示——小表全扫常是更优计划，阈值一刀切会噪音）。 */
    public static final int ROWS_THRESHOLD = 10_000;

    /** 单条建议（纯数据；advice 为英文短句——MCP 输出口径，不进 bundle）。 */
    public record Hint(
            /** 命中规则名（FULL_SCAN/NO_INDEX_USED/FILESORT/TEMPORARY_TABLE/SEQ_SCAN）。 */
            @NotNull String rule,
            /** 涉及对象（表名）。 */
            @NotNull String target,
            /** 建议文案（英文短句）。 */
            @NotNull String advice
    ) {
    }

    /** PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`。 */
    private static final Pattern SEQ_SCAN = Pattern.compile("Seq Scan on (\\w+)");

    private static final Pattern ROWS_N = Pattern.compile("rows=(\\d+)");

    /**
     * 从 EXPLAIN 结果识别索引缺失类模式。
     *
     * @param dialect 方言显示名（MySqlDialect/MariaDbDialect 走表格式；PostgreSQL/KingBase 走文本树）
     * @param headers 结果列名（表格式形态；文本树形态通常单列）
     * @param rows    结果行（每行与 headers 对齐；truncated 之前的部分）
     */
    @NotNull
    public static List<Hint> advise(@NotNull String dialect, @NotNull List<String> headers,
                                    @NotNull List<List<String>> rows) {
        List<Hint> out = new ArrayList<>();
        String lower = dialect.toLowerCase(Locale.ROOT);
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            // MySQL 家族：表格式（id/select_type/table/type/key/rows/Extra…）
            adviseMysql(headers, rows, out);
        } else if (lower.contains("postgres") || lower.contains("kingbase")) {
            // PG 家族：文本树（QUERY PLAN 单列多行）
            advisePlanText(rows, out);
        } else {
            // 不支持方言静默降级（含信创系）——空清单与无命中同形
        }
        return out;
    }

    private static void adviseMysql(@NotNull List<String> headers, @NotNull List<List<String>> rows,
                                    @NotNull List<Hint> out) {
        List<String> idx = new ArrayList<>(headers.size());
        for (String h : headers) {
            idx.add(h.toLowerCase(Locale.ROOT).trim());
        }
        int typeCol = idx.indexOf("type");
        int keyCol = idx.indexOf("key");
        int rowsCol = idx.indexOf("rows");
        int extraCol = idx.indexOf("extra");
        int tableCol = idx.indexOf("table");
        if (typeCol < 0 && keyCol < 0 && extraCol < 0) return; // 列名形态不符（如 FORMAT=TREE 变体）→ 不猜
        for (List<String> row : rows) {
            String table = tableCol >= 0 ? cell(row, tableCol) : "?";
            String type = typeCol >= 0 ? cell(row, typeCol) : "";
            String key = keyCol >= 0 ? cell(row, keyCol) : "";
            Long rowsN = rowsCol >= 0 ? toLongOrNull(cell(row, rowsCol)) : null;
            String extra = extraCol >= 0 ? cell(row, extraCol) : "";
            // type=ALL 全表扫（rows 超阈才提示——小表全扫噪音）
            if (type.equalsIgnoreCase("ALL") && (rowsN == null || rowsN >= ROWS_THRESHOLD)) {
                out.add(new Hint(
                        "FULL_SCAN",
                        table,
                        "type=ALL full table scan"
                                + (rowsN != null ? " over ~" + rowsN + " rows" : "")
                                + " — consider adding an index on the WHERE/JOIN columns"));
            }
            // key=NULL：没有可用索引被选中（与 ALL 互补——rows 不足阈值时不重复提示的兜底弱信号）
            if (key.equalsIgnoreCase("NULL") && !type.equalsIgnoreCase("ALL")) {
                out.add(new Hint(
                        "NO_INDEX_USED",
                        table,
                        "no index was used (key=NULL) — verify the query filters on an indexed column"));
            }
            if (extra.toLowerCase(Locale.ROOT).contains("using filesort")) {
                out.add(new Hint(
                        "FILESORT",
                        table,
                        "Using filesort — ORDER BY cannot use an index; consider a covering index"));
            }
            if (extra.toLowerCase(Locale.ROOT).contains("using temporary")) {
                out.add(new Hint(
                        "TEMPORARY_TABLE",
                        table,
                        "Using temporary — GROUP BY/DISTINCT spills to a temp table; consider an index"));
            }
        }
    }

    private static void advisePlanText(@NotNull List<List<String>> rows, @NotNull List<Hint> out) {
        for (List<String> row : rows) {
            for (String cell : row) {
                // PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`
                Matcher m = SEQ_SCAN.matcher(cell);
                if (!m.find()) continue;
                Long rowsN = null;
                Matcher rm = ROWS_N.matcher(cell);
                if (rm.find()) {
                    try {
                        rowsN = Long.parseLong(rm.group(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (rowsN == null || rowsN >= ROWS_THRESHOLD) {
                    out.add(new Hint(
                            "SEQ_SCAN",
                            m.group(1),
                            "Sequential scan"
                                    + (rowsN != null ? " over ~" + rowsN + " rows" : "")
                                    + " — consider adding an index on the filter columns"));
                }
            }
        }
    }

    /** 行内取单元格（越界/负列返回空串），trim 后返回。 */
    @NotNull
    private static String cell(@NotNull List<String> row, int col) {
        if (col < 0 || col >= row.size()) return "";
        String v = row.get(col);
        return v == null ? "" : v.trim();
    }

    /** Kotlin toLongOrNull 等价：无法整体解析为 Long 返回 null。 */
    private static Long toLongOrNull(@NotNull String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 建议节渲染（MCP explain 输出承载位；空清单返回空串——输出零差异硬门禁）。 */
    @NotNull
    public static String formatHints(@NotNull List<Hint> hints) {
        if (hints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nindex hints:");
        for (Hint hint : hints) {
            sb.append("\n- [").append(hint.rule()).append("] ")
                    .append(hint.target()).append(": ").append(hint.advice());
        }
        return sb.toString();
    }
}
