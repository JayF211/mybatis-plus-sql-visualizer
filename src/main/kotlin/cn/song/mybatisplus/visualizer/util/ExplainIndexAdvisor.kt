package cn.song.mybatisplus.visualizer.util

/**
 * EXPLAIN 计划「索引缺失」建议识别器（T-ExplainIndex T-EI-02，全景审计 N-23）。
 *
 * 把高频可判定模式提炼为结构化建议（命中规则/涉及对象/建议文案），只提示不执行不改 SQL；
 * 无命中返回空清单（不噪音）；方言不支持静默降级（调用方拿到空清单即与现状一致）。
 *
 * 落点定案（T-EI-01，实施核实）：IDE 内嵌原生控制台的结果行**无程序化读取面**（插件对结果区
 * 仅做页签选择/组件换入，网格数据由 DBN 托管，硬读需侵入平台内部网格模型）；MCP explain 链
 * 自持行列（[cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult]）——建议节挂在
 * 该输出（T-NoticeChannel notices 同款承载位）。IDE 面建议条挂账（硬做=备选 B 自渲染，成本
 * 收益不成立）。
 *
 * 两方言先行（计划口径）：MySQL 表格式（type=ALL 全表扫 / key=NULL 且 rows 超阈 /
 * Extra 含 Using filesort/temporary）、PG 文本树（大表 Seq Scan）。
 * 单源约定：规则集在本 object，任何未来 plan-text 消费方（IDE 面备选 B、perf 工具扩展）
 * 都从这里取，不复制第二套（与 perf_advice 的静态 SQL 规则集是两个互补面，不互斥）。
 */
object ExplainIndexAdvisor {

    /** 全表扫描行数阈值（低于此不提示——小表全扫常是更优计划，阈值一刀切会噪音）。 */
    const val ROWS_THRESHOLD = 10_000

    /** 单条建议（纯数据；advice 为英文短句——MCP 输出口径，不进 bundle）。 */
    data class Hint(val rule: String, val target: String, val advice: String)

    /**
     * 从 EXPLAIN 结果识别索引缺失类模式。
     *
     * @param dialect   方言显示名（MySqlDialect/MariaDbDialect 走表格式；PostgreSQL/KingBase 走文本树）
     * @param headers   结果列名（表格式形态；文本树形态通常单列）
     * @param rows      结果行（每行与 headers 对齐；truncated 之前的部分）
     */
    fun advise(dialect: String, headers: List<String>, rows: List<List<String>>): List<Hint> {
        val out = mutableListOf<Hint>()
        val lower = dialect.lowercase()
        when {
            // MySQL 家族：表格式（id/select_type/table/type/key/rows/Extra…）
            lower.contains("mysql") || lower.contains("mariadb") -> adviseMysql(headers, rows, out)
            // PG 家族：文本树（QUERY PLAN 单列多行）
            lower.contains("postgres") || lower.contains("kingbase") -> advisePlanText(rows, out)
            else -> Unit // 不支持方言静默降级（含信创系）——空清单与无命中同形
        }
        return out
    }

    private fun adviseMysql(headers: List<String>, rows: List<List<String>>, out: MutableList<Hint>) {
        val idx = headers.map { it.lowercase().trim() }
        val typeCol = idx.indexOf("type")
        val keyCol = idx.indexOf("key")
        val rowsCol = idx.indexOf("rows")
        val extraCol = idx.indexOf("extra")
        val tableCol = idx.indexOf("table")
        if (typeCol < 0 && keyCol < 0 && extraCol < 0) return // 列名形态不符（如 FORMAT=TREE 变体）→ 不猜
        for (row in rows) {
            fun cell(col: Int): String = row.getOrNull(col)?.trim().orEmpty()
            val table = if (tableCol >= 0) cell(tableCol) else "?"
            val type = if (typeCol >= 0) cell(typeCol) else ""
            val key = if (keyCol >= 0) cell(keyCol) else ""
            val rowsN = if (rowsCol >= 0) cell(rowsCol).toLongOrNull() else null
            val extra = if (extraCol >= 0) cell(extraCol) else ""
            // type=ALL 全表扫（rows 超阈才提示——小表全扫噪音）
            if (type.equals("ALL", ignoreCase = true) && (rowsN == null || rowsN >= ROWS_THRESHOLD)) {
                out += Hint(
                    rule = "FULL_SCAN",
                    target = table,
                    advice = "type=ALL full table scan" +
                        (rowsN?.let { " over ~$it rows" } ?: "") +
                        " — consider adding an index on the WHERE/JOIN columns",
                )
            }
            // key=NULL：没有可用索引被选中（与 ALL 互补——rows 不足阈值时不重复提示的兜底弱信号）
            if (key.equals("NULL", ignoreCase = true) && !type.equals("ALL", ignoreCase = true)) {
                out += Hint(
                    rule = "NO_INDEX_USED",
                    target = table,
                    advice = "no index was used (key=NULL) — verify the query filters on an indexed column",
                )
            }
            if (extra.contains("Using filesort", ignoreCase = true)) {
                out += Hint(
                    rule = "FILESORT",
                    target = table,
                    advice = "Using filesort — ORDER BY cannot use an index; consider a covering index",
                )
            }
            if (extra.contains("Using temporary", ignoreCase = true)) {
                out += Hint(
                    rule = "TEMPORARY_TABLE",
                    target = table,
                    advice = "Using temporary — GROUP BY/DISTINCT spills to a temp table; consider an index",
                )
            }
        }
    }

    private fun advisePlanText(rows: List<List<String>>, out: MutableList<Hint>) {
        for (row in rows) {
            for (cell in row) {
                // PG 文本树：`Seq Scan on big_table  (cost=… rows=…)`
                val m = Regex("""Seq Scan on (\w+)""").find(cell) ?: continue
                val rowsN = Regex("""rows=(\d+)""").find(cell)?.groupValues?.get(1)?.toLongOrNull()
                if (rowsN == null || rowsN >= ROWS_THRESHOLD) {
                    out += Hint(
                        rule = "SEQ_SCAN",
                        target = m.groupValues[1],
                        advice = "Sequential scan" +
                            (rowsN?.let { " over ~$it rows" } ?: "") +
                            " — consider adding an index on the filter columns",
                    )
                }
            }
        }
    }

    /** 建议节渲染（MCP explain 输出承载位；空清单返回空串——输出零差异硬门禁）。 */
    fun formatHints(hints: List<Hint>): String {
        if (hints.isEmpty()) return ""
        return buildString {
            append("\n\nindex hints:")
            hints.forEach { append("\n- [").append(it.rule).append("] ").append(it.target).append(": ").append(it.advice) }
        }
    }
}
