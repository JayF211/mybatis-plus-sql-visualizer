package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat

/** Uses an existing result; it never runs EXPLAIN or ANALYZE and never labels plan cost as elapsed time. */
object ExplainPlanInsights {
    data class Operator(
        val label: String,
        val explanationKey: String,
        val estimatedRows: Long?,
        val actualRowsPerLoop: Double?,
        val actualLoops: Double?,
        val estimatedCost: Double?,
        val actualTimePerLoopMs: Double?,
    )
    data class Report(val operators: List<Operator>, val limited: Boolean, val hasActualMeasurements: Boolean)

    fun inspect(raw: McpExplainFormatter.RawResult, format: ExplainOutputFormat): Report? {
        if (format != ExplainOutputFormat.JSON || raw.truncated) return null
        if (raw.rows.sumOf { row -> row.sumOf { it.length.toLong() } } > 256 * 1024) return null
        val nodes = ExplainJsonPlanTree.flattenPlanNodes(raw.rows)
        if (nodes.isEmpty()) return null
        val operators = nodes.take(200).map { node ->
            val key = when (node.nodeType) {
                "Seq Scan", "Parallel Seq Scan" -> "sequentialScan"
                "Index Scan", "Index Only Scan", "Bitmap Index Scan", "Bitmap Heap Scan" -> "indexAccess"
                "Nested Loop" -> "nestedLoop"
                "Hash Join" -> "hashJoin"
                "Merge Join" -> "mergeJoin"
                "Sort", "Incremental Sort" -> "sort"
                "Aggregate", "Group", "HashAggregate", "GroupAggregate" -> "aggregate"
                "Limit" -> "limit"
                else -> "other"
            }
            Operator(node.label, key, node.estimatedRows,
                node.actualRowsPerLoop, node.extras["Actual Loops"]?.toDoubleOrNull(),
                node.cost, node.extras["Actual Total Time"]?.toDoubleOrNull())
        }
        return Report(operators, nodes.size > 200, operators.any { it.actualTimePerLoopMs != null || it.actualRowsPerLoop != null })
    }
}
