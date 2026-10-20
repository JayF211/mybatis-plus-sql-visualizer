package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R61：EXPLAIN 执行计划跨次对比纯逻辑单测（JSON 档＝PG/KingBase/openGauss；TABLE 档＝MySQL 家族）。
 *
 * 对齐语义（V113①）：节点按 `nodeType on relation#出现序` 配对——兄弟增删不产生假变化；
 * rows/cost 给增量、其余标量键给字段级变化；增删节点入 added/removed。诚实降级：非结构化档 /
 * 解析不出结构 / 空行 → null。
 */
class ExplainPlanDiffTest {

    private fun pgJson(vararg nodes: String) = """[{"Plan": {"Node Type": "Limit", "Total Cost": 10.0, "Plans": [${nodes.joinToString(",")}]}}]"""

    private fun seqScan(relation: String, rows: Long, cost: Double? = null, filter: String? = null): String {
        val sb = StringBuilder("""{"Node Type": "Seq Scan", "Relation Name": "$relation", "Plan Rows": $rows""")
        cost?.let { sb.append(""", "Total Cost": $it""") }
        filter?.let { sb.append(", \"Filter\": \"$it\"") }
        sb.append("}")
        return sb.toString()
    }

    private fun rawOf(vararg cells: String) = McpExplainFormatter.RawResult(listOf("QUERY PLAN"), cells.map { listOf(it) })

    // ---------- JSON 档 ----------

    @Test
    fun `json_行数与成本变化给增量`() {
        val old = rawOf(pgJson(seqScan("t", 100, 10.0)))
        val new = rawOf(pgJson(seqScan("t", 250, 14.5)))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.JSON)!!
        assertEquals(1, d.changed.size)
        val change = d.changed.single { it.matchKey == "Seq Scan on t#0" }
        assertEquals(100L, change.rowsDelta!!.old)
        assertEquals(250L, change.rowsDelta.new)
        assertEquals(10.0, change.costDelta!!.old, 0.0001)
        assertEquals(14.5, change.costDelta.new, 0.0001)
        assertTrue(d.added.isEmpty())
        assertTrue(d.removed.isEmpty())
    }

    @Test
    fun `json_节点增删`() {
        val old = rawOf(pgJson(seqScan("t", 1), seqScan("u", 2)))
        val new = rawOf(pgJson(seqScan("t", 1), seqScan("u", 2), seqScan("v", 3)))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.JSON)!!
        assertEquals(1, d.added.size)
        assertTrue(d.added.single().contains("Seq Scan on v"))
        assertTrue(d.removed.isEmpty())

        val shrink = ExplainPlanDiff.diff(new, old, ExplainOutputFormat.JSON)!!
        assertEquals(1, shrink.removed.size)
        assertTrue(shrink.removed.single().contains("Seq Scan on v"))
    }

    @Test
    fun `json_兄弟增删不产生假变化_同键按出现序配对`() {
        // 旧：t, t, u；新：t, u（第一个 t 被删）——同键 t 两个按出现序配对，删除的是 t#1
        val old = rawOf(pgJson(seqScan("t", 1), seqScan("t", 2), seqScan("u", 3)))
        val new = rawOf(pgJson(seqScan("t", 1), seqScan("u", 3)))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.JSON)!!
        assertEquals("配对节点无值变（rows 相同）", 0, d.changed.count { it.matchKey.startsWith("Seq Scan on t") })
        assertEquals(1, d.removed.size)
        assertTrue(d.removed.single().contains("Seq Scan on t"))
    }

    @Test
    fun `json_其余标量键字段级变化`() {
        val old = rawOf(pgJson(seqScan("t", 100, filter = "(a > 1)")))
        val new = rawOf(pgJson(seqScan("t", 100, filter = "(a > 2)")))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.JSON)!!
        val change = d.changed.single()
        assertNull(change.rowsDelta)
        assertEquals(listOf(ExplainPlanDiff.FieldChange("Filter", "(a > 1)", "(a > 2)")), change.fieldChanges)
    }

    @Test
    fun `json_计划等价返回空diff`() {
        val old = rawOf(pgJson(seqScan("t", 100, 10.0)))
        val new = rawOf(pgJson(seqScan("t", 100, 10.0)))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.JSON)!!
        assertTrue(d.isEmpty)
    }

    // ---------- TABLE 档（MySQL 家族） ----------

    private val mysqlHeaders = listOf("id", "select_type", "table", "type", "key", "rows", "Extra")

    private fun mysqlRow(id: String, table: String, type: String, key: String, rows: String, extra: String) =
        listOf(id, "SIMPLE", table, type, key, rows, extra)

    @Test
    fun `table_按id与table配对_行数增量与列变化`() {
        val old = McpExplainFormatter.RawResult(
            mysqlHeaders,
            listOf(mysqlRow("1", "tb_user", "ALL", "NULL", "99500", "Using where")),
        )
        val new = McpExplainFormatter.RawResult(
            mysqlHeaders,
            listOf(mysqlRow("1", "tb_user", "ALL", "NULL", "500", "Using where")),
        )
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.TABLE)!!
        assertEquals(1, d.changed.size)
        val change = d.changed.single()
        assertEquals(99500L, change.rowsDelta!!.old)
        assertEquals(500L, change.rowsDelta.new)
        assertTrue(change.fieldChanges.isEmpty())
    }

    @Test
    fun `table_索引生效场景_type与key列变化`() {
        val old = McpExplainFormatter.RawResult(
            mysqlHeaders,
            listOf(mysqlRow("1", "tb_user", "ALL", "NULL", "99500", "")),
        )
        val new = McpExplainFormatter.RawResult(
            mysqlHeaders,
            listOf(mysqlRow("1", "tb_user", "ref", "idx_name", "10", "Using index")),
        )
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.TABLE)!!
        val change = d.changed.single()
        val names = change.fieldChanges.map { it.name }
        assertTrue("type 列变化应登记", names.contains("type"))
        assertTrue("key 列变化应登记", names.contains("key"))
        assertTrue("extra 列变化应登记（normalizeHeader 小写化）", names.contains("extra"))
    }

    @Test
    fun `table_行增删与不可比降级`() {
        val old = McpExplainFormatter.RawResult(mysqlHeaders, listOf(mysqlRow("1", "a", "ALL", "NULL", "1", "")))
        val new = McpExplainFormatter.RawResult(
            mysqlHeaders,
            listOf(mysqlRow("1", "a", "ALL", "NULL", "1", ""), mysqlRow("1", "b", "ALL", "NULL", "2", "")),
        )
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.TABLE)!!
        assertEquals(1, d.added.size)
        assertTrue(d.added.single().contains("table=b"))

        // 空行 / 非结构化档 → null（不硬比）
        assertNull(ExplainPlanDiff.diff(McpExplainFormatter.RawResult(mysqlHeaders, emptyList()), new, ExplainOutputFormat.TABLE))
        assertNull(ExplainPlanDiff.diff(old, new, ExplainOutputFormat.TEXT))
        assertNull(ExplainPlanDiff.diff(old, new, ExplainOutputFormat.XML))
    }

    @Test
    fun `table_缺id与table列_退行序配对`() {
        val headers = listOf("plan_step", "details")
        val old = McpExplainFormatter.RawResult(headers, listOf(listOf("step-1", "full scan")))
        val new = McpExplainFormatter.RawResult(headers, listOf(listOf("step-1", "index scan")))
        val d = ExplainPlanDiff.diff(old, new, ExplainOutputFormat.TABLE)!!
        val change = d.changed.single()
        assertEquals(listOf(ExplainPlanDiff.FieldChange("details", "full scan", "index scan")), change.fieldChanges)
    }

    // ---------- JSON 解析失败降级 ----------

    @Test
    fun `json_解析失败_无可比`() {
        val bad = McpExplainFormatter.RawResult(listOf("QUERY PLAN"), listOf(listOf("truncated cell...")))
        val good = rawOf(pgJson(seqScan("t", 1)))
        assertNull(ExplainPlanDiff.diff(bad, good, ExplainOutputFormat.JSON))
        assertNull(ExplainPlanDiff.diff(good, bad, ExplainOutputFormat.JSON))
    }
}
