package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-McpToolset schema 限幅/紧凑格式化与 explain 文本格式化单测（纯逻辑层）。
 */
class McpSchemaFormatterTest {

    private fun table(name: String, columns: Int = 2) = McpSchemaFormatter.TableEntry(
        name = name,
        comment = if (name == "annotated") "表注释" else null,
        columns = (1..columns).map { i ->
            McpSchemaFormatter.ColumnLine("col$i", "varchar(64)", if (i == 1) "第一列" else null, i == 1)
        },
    )

    // ------------------------------------------------------------------
    // max_tables 限幅（DBX 口径：默认 8、上限 20）
    // ------------------------------------------------------------------

    @Test
    fun `限幅_默认8上限20`() {
        assertEquals(8, McpSchemaFormatter.clampMaxTables(null))
        assertEquals(8, McpSchemaFormatter.clampMaxTables(0))
        assertEquals(8, McpSchemaFormatter.clampMaxTables(-3))
        assertEquals(1, McpSchemaFormatter.clampMaxTables(1))
        assertEquals(15, McpSchemaFormatter.clampMaxTables(15))
        assertEquals(20, McpSchemaFormatter.clampMaxTables(20))
        assertEquals(20, McpSchemaFormatter.clampMaxTables(100))
    }

    // ------------------------------------------------------------------
    // 表名清单校验
    // ------------------------------------------------------------------

    @Test
    fun `表名清单_去重保序与空名剔除`() {
        assertEquals(
            listOf("a", "b", "c"),
            McpSchemaFormatter.sanitizeTableNames(listOf("a", " A ", "b", "", "c", "B")),
        )
        assertNull("空清单应拒", McpSchemaFormatter.sanitizeTableNames(emptyList()))
        assertNull("全空白应拒", McpSchemaFormatter.sanitizeTableNames(listOf(" ", "")))
        assertNull(
            "超 ${McpSchemaFormatter.MAX_TABLE_NAMES} 条应拒",
            McpSchemaFormatter.sanitizeTableNames((1..McpSchemaFormatter.MAX_TABLE_NAMES + 1).map { "t$it" }),
        )
        assertEquals(
            McpSchemaFormatter.MAX_TABLE_NAMES,
            McpSchemaFormatter.sanitizeTableNames((1..McpSchemaFormatter.MAX_TABLE_NAMES).map { "t$it" })?.size,
        )
    }

    // ------------------------------------------------------------------
    // 紧凑格式化与截断提示
    // ------------------------------------------------------------------

    @Test
    fun `格式化_表列注释与NOT_NULL标记`() {
        val text = McpSchemaFormatter.format(listOf(table("annotated")), 8, 1)
        assertTrue("应含表名: $text", text.startsWith("annotated (表注释)"))
        assertTrue("应含列注释: $text", text.contains("col1 varchar(64) NOT NULL -- 第一列"))
        assertTrue("无注释列只出名与类型: $text", text.contains("col2 varchar(64)"))
        assertFalse("未截断不应有提示: $text", text.contains("TRUNCATED"))
    }

    @Test
    fun `格式化_超限截断提示`() {
        val text = McpSchemaFormatter.format(listOf(table("t1"), table("t2"), table("t3")), 2, 3)
        assertTrue("只出前 2 张: $text", text.contains("t1") && text.contains("t2") && !text.contains("t3"))
        assertTrue("应含截断提示: $text", text.contains("[TRUNCATED] showing 2 of 3 requested tables"))
        assertTrue("提示应含上限口径: $text", text.contains("limit ${McpSchemaFormatter.HARD_MAX_TABLES}"))
    }

    // ------------------------------------------------------------------
    // EXPLAIN 文本格式化（预算与截断）
    // ------------------------------------------------------------------

    @Test
    fun `explain格式化_列头行与截断`() {
        val raw = McpExplainFormatter.RawResult(
            headers = listOf("id", "select_type"),
            rows = listOf(listOf("1", "SIMPLE"), listOf("1", "PRIMARY")),
        )
        val text = McpExplainFormatter.format(raw)
        assertTrue("应含列头: $text", text.startsWith("id | select_type"))
        assertTrue("应含行: $text", text.contains("1 | SIMPLE"))
        assertFalse("未截断无提示: $text", text.contains("TRUNCATED"))
    }

    @Test
    fun `explain格式化_截断标记与单元格预算`() {
        val longCell = "x".repeat(McpExplainFormatter.MAX_CELL_CHARS + 100)
        val raw = McpExplainFormatter.RawResult(
            headers = listOf("plan"),
            rows = List(McpExplainFormatter.MAX_ROWS) { listOf(longCell) },
            truncated = true,
        )
        val text = McpExplainFormatter.format(raw)
        assertTrue("应含截断提示: $text", text.contains("[TRUNCATED] output limited to ${McpExplainFormatter.MAX_ROWS} rows"))
        assertTrue(
            "单元格应裁剪到预算: $text",
            text.contains("x".repeat(McpExplainFormatter.MAX_CELL_CHARS) + "…"),
        )
        assertFalse(
            "不应包含超出预算的完整单元格: $text",
            text.contains(longCell),
        )
    }
}
