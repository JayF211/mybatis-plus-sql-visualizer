package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** R91: an allowlist projection of already acquired data, never the UI tree's label/extras/raw fallback. */
internal object AiContextPlanSummary {
    private val nodeTypes = setOf(
        "Seq Scan", "Index Scan", "Index Only Scan", "Bitmap Heap Scan", "Bitmap Index Scan",
        "Nested Loop", "Hash Join", "Merge Join", "Hash", "Aggregate", "Group", "Sort", "Incremental Sort",
        "Limit", "Result", "Append", "Merge Append", "Gather", "Gather Merge", "Materialize", "Memoize",
        "Unique", "WindowAgg", "Subquery Scan", "CTE Scan", "Function Scan", "Values Scan", "SetOp",
        "LockRows", "ModifyTable", "Recursive Union", "WorkTable Scan", "BitmapAnd", "BitmapOr",
    )
    private val accessTypes = setOf("system", "const", "eq_ref", "ref", "fulltext", "ref_or_null",
        "index_merge", "unique_subquery", "index_subquery", "range", "index", "ALL")
    private val number = Regex("[0-9]{1,16}(?:\\.[0-9]{1,8})?(?:[eE][+-]?[0-9]{1,3})?")

    fun summarize(raw: RawResult, format: ExplainOutputFormat): String? {
        // An incomplete acquisition cannot be presented as a complete plan.
        if (raw.truncated || raw.rows.size > 512) return null
        val lines = when (format) {
            ExplainOutputFormat.TABLE -> raw.rows.mapNotNull { row ->
                fun value(name: String) = raw.headers.indexOfFirst { it.equals(name, true) }
                    .takeIf { it >= 0 }?.let { row.getOrNull(it) }
                listOfNotNull(
                    value("type")?.takeIf { it in accessTypes }?.let { "type=$it" },
                    numeric(value("rows"))?.let { "rows=$it" },
                    numeric(value("cost"))?.let { "cost=$it" },
                    numeric(value("filtered"))?.let { "filteredPercent=$it" },
                ).takeIf { it.isNotEmpty() }?.joinToString("; ")
            }
            ExplainOutputFormat.JSON -> {
                if (raw.rows.sumOf { row -> row.sumOf { it.length.toLong() } } > 131_072) return null
                val output = mutableListOf<String>()
                fun visit(element: JsonElement, depth: Int) {
                    require(depth <= 64 && output.size <= 512)
                    if (element.isJsonArray) element.asJsonArray.forEach { visit(it, depth + 1) }
                    if (!element.isJsonObject) return
                    val obj = element.asJsonObject
                    fun value(name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString
                    if (obj.has("Node Type")) {
                        val fields = listOfNotNull(
                            value("Node Type")?.takeIf { it in nodeTypes }?.let { "node=$it" },
                            numeric(value("Plan Rows"))?.let { "rows=$it" },
                            numeric(value("Total Cost"))?.let { "cost=$it" },
                        )
                        if (fields.isNotEmpty()) output += fields.joinToString("; ")
                        obj.get("Plans")?.let { visit(it, depth + 1) }
                    } else obj.get("Plan")?.let { visit(it, depth + 1) }
                }
                try {
                    raw.rows.forEach { row -> row.forEach { visit(JsonParser.parseString(it), 0) } }
                } catch (_: Exception) { return null }
                output
            }
            else -> return null
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun numeric(value: String?): String? = value?.takeIf {
        number.matches(it) && it.toDoubleOrNull()?.isFinite() == true
    }
}
