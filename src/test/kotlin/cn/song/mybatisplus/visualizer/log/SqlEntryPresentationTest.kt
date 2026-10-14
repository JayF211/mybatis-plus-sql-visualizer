package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color

class SqlEntryPresentationTest : BasePlatformTestCase() {
    fun test共同元信息顺序与日志独有字段() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            timestamp = 1L,
            sequenceNumber = 17,
            executionTimeMs = 21,
            databaseName = "PostgreSQL",
            sourceFile = "src/main/OrderService.java",
            sourceLine = 42,
            updateCount = 2,
        )
        val settings = PluginSettings.getInstance()
        val old = settings.state.logTimestampEnabled
        try {
            settings.state.logTimestampEnabled = true
            val common = SqlEntryPresentation.metadata(entry, false, true, emptyMap())
            val log = SqlEntryPresentation.metadata(entry, false, true, emptyMap(), showLogDetails = true)
            val commonTexts = common.map { it.text }
            assertEquals("#17 ", commonTexts.first())
            assertTrue(commonTexts.indexOfFirst { it.contains("21ms") } < commonTexts.indexOfFirst { it.contains("PostgreSQL") })
            assertTrue(commonTexts.last().contains("OrderService.java:42"))
            assertEquals("共同片段顺序不得随日志独有字段改变", commonTexts.dropLast(1), log.map { it.text }.take(commonTexts.size - 1))
            assertTrue(log.any { it.text.contains("2 rows") })
        } finally {
            settings.state.logTimestampEnabled = old
        }
    }

    fun test子句概览最多三行且标记剩余行数() {
        val lines = (1..5).map { listOf(SqlTextFragment("line $it", SimpleTextAttributes.REGULAR_ATTRIBUTES)) }
        val preview = visibleSqlLines(lines, SqlListDisplayMode.KEYWORD_WRAP)
        assertEquals(3, preview.size)
        assertEquals("line 1", preview[0].single().text)
        assertEquals("line 2", preview[1].single().text)
        assertEquals("… (+3)", preview[2].single().text)
        assertSame(lines, visibleSqlLines(lines, SqlListDisplayMode.FORMATTED))
    }

    fun test宽度裁切保留片段属性与完整模型内容() {
        val attrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Color.BLUE)
        val entry = LogSqlEntry(rawSql = "SELECT 😀 FROM t", restoredSql = "SELECT 😀 FROM t", params = emptyList())
        val fragments = listOf(SqlTextFragment("SELECT ", attrs), SqlTextFragment("😀 FROM t", attrs))
        val clipped = clipSqlFragments(fragments, 90) { text, _ -> text.codePointCount(0, text.length) * 10 }
        val visible = clipped.joinToString("") { it.text }
        assertTrue(visible.endsWith("…"))
        assertFalse(visible.any { Character.isSurrogate(it) && !visible.contains("😀") })
        assertTrue(clipped.all { it.attributes === attrs })
        assertEquals("SELECT 😀 FROM t", entry.restoredSql)
    }
}
