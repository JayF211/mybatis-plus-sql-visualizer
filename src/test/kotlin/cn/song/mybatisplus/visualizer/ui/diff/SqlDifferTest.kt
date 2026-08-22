package cn.song.mybatisplus.visualizer.ui.diff

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer.DiffType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-4：SQL 行级 diff 算法测试（SqlDiffer.computeDiff）。
 *
 * 验证：完全相同、完全不同、部分变更、空 SQL、单行插入/删除。
 */
class SqlDifferTest {

    @Test
    fun `computeDiff_完全相同_无差异`() {
        val sql = "SELECT id\nFROM tbl\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(sql, sql)
        assertTrue("相同 SQL 应无差异", diff.isEmpty())
    }

    @Test
    fun `computeDiff_完全不同_全部变更`() {
        val old = "SELECT id\nFROM tbl"
        val new = "SELECT name\nFROM users"
        val diff = SqlDiffer.computeDiff(old, new)
        // 2 行删除 + 2 行新增 = 4 个变更条目
        assertEquals("完全不同应产生 4 个变更", 4, diff.size)
        val changed = diff.filter { it.type != DiffType.EQUAL }
        assertEquals(4, changed.size)
    }

    @Test
    fun `computeDiff_部分变更_中间行不同`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM users\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        // 中间行从 "FROM tbl" 变为 "FROM users"：1 行删除 + 1 行新增
        val changed = diff.filter { it.type != DiffType.EQUAL }
        assertEquals("中间行变更应产生 2 个变更条目", 2, changed.size)
        val deleted = changed.filter { it.type == DiffType.DELETE }
        val inserted = changed.filter { it.type == DiffType.INSERT }
        assertEquals(1, deleted.size)
        assertEquals(1, inserted.size)
        assertEquals("FROM tbl", deleted.first().oldLine)
        assertEquals("FROM users", inserted.first().newLine)
    }

    @Test
    fun `computeDiff_新增行`() {
        val old = "SELECT id\nFROM tbl"
        val new = "SELECT id\nFROM tbl\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        val added = diff.filter { it.type == DiffType.INSERT }
        assertEquals("应检测到 1 行新增", 1, added.size)
        assertEquals("WHERE id = 1", added.first().newLine)
    }

    @Test
    fun `computeDiff_删除行`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM tbl"
        val diff = SqlDiffer.computeDiff(old, new)
        val deleted = diff.filter { it.type == DiffType.DELETE }
        assertEquals("应检测到 1 行删除", 1, deleted.size)
        assertEquals("WHERE id = 1", deleted.first().oldLine)
    }

    @Test
    fun `computeDiff_空旧SQL_全部新增`() {
        val old = ""
        val new = "SELECT id\nFROM tbl"
        val diff = SqlDiffer.computeDiff(old, new)
        val added = diff.filter { it.type == DiffType.INSERT }
        assertEquals("空旧 SQL 应全部新增", 2, added.size)
    }

    @Test
    fun `computeDiff_空新SQL_全部删除`() {
        val old = "SELECT id\nFROM tbl"
        val new = ""
        val diff = SqlDiffer.computeDiff(old, new)
        val deleted = diff.filter { it.type == DiffType.DELETE }
        assertEquals("空新 SQL 应全部删除", 2, deleted.size)
    }

    @Test
    fun `computeDiff_两者皆空_无差异`() {
        val diff = SqlDiffer.computeDiff("", "")
        assertTrue("两者皆空应无差异", diff.isEmpty())
    }

    @Test
    fun `computeDiff_行号正确`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM users\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        // 删除行的旧行号应为 2（FROM tbl 在旧 SQL 第 2 行）
        val deleted = diff.first { it.type == DiffType.DELETE }
        assertEquals(2, deleted.oldLineNumber)
        // 新增行的新行号应为 2（FROM users 在新 SQL 第 2 行）
        val inserted = diff.first { it.type == DiffType.INSERT }
        assertEquals(2, inserted.newLineNumber)
    }

    @Test
    fun `computeDiff_性能验证_1000行100ms内`() {
        val oldLines = (1..1000).map { "line_$it" }
        val newLines = (1..1000).map { if (it == 500) "line_changed" else "line_$it" }
        val old = oldLines.joinToString("\n")
        val new = newLines.joinToString("\n")
        val start = System.currentTimeMillis()
        val diff = SqlDiffer.computeDiff(old, new)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 行 diff 应在 100ms 内完成（实际 ${elapsed}ms）", elapsed < 100)
        val changed = diff.filter { it.type != DiffType.EQUAL }
        // 1 行变更 = 1 删除 + 1 新增 = 2 个条目
        assertEquals("仅 1 行变更（2 个条目）", 2, changed.size)
    }
}
