package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SQL 日志文件读取器单元测试（F-44，纯 JUnit，无平台依赖）。
 */
class SqlLogFileReaderTest {

    @Test
    fun `解析新版格式`() {
        val content = "--- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT * FROM user WHERE id = 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql)
        assertEquals("src/main/java/com/example/mapper/UserMapper.java", entry.sourceFile)
        assertEquals(42, entry.sourceLine)
        assertEquals(5L, entry.executionTimeMs)
        // 时区无关：仅验证 timestamp > 0
        assertTrue("Timestamp should be positive: ${entry.timestamp}", entry.timestamp > 0)
    }

    @Test
    fun `解析新版格式_unknown source`() {
        val content = "--- source: unknown | time: — | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertNull(entry.sourceFile)
        assertEquals(0, entry.sourceLine)
        assertNull(entry.executionTimeMs)
    }

    @Test
    fun `解析旧版格式`() {
        val content = "-- 2026-08-25 10:00:00\nSELECT * FROM user\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user", entry.restoredSql)
        assertNull(entry.sourceFile)
        assertEquals(0, entry.sourceLine)
    }

    @Test
    fun `解析混合格式`() {
        val content = """
            --- source: UserMapper.java:10 | time: 3ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            -- 2026-08-25 09:00:00
            SELECT 2

            --- source: OrderMapper.java:20 | time: 10ms | 2026-08-25 11:00:00.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        // 按文件中出现顺序：新版 → 旧版 → 新版
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertNull(entries[1].sourceFile)
        assertEquals("SELECT 3", entries[2].restoredSql)
        assertEquals("OrderMapper.java", entries[2].sourceFile)
    }

    @Test
    fun `解析多条新版格式`() {
        val content = """
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertEquals("SELECT 3", entries[2].restoredSql)
    }

    @Test
    fun `空内容返回空列表`() {
        assertTrue(SqlLogFileReader.parseContent("").isEmpty())
        assertTrue(SqlLogFileReader.parseContent("   \n\n   ").isEmpty())
    }

    @Test
    fun `损坏格式返回空列表`() {
        val content = "这不是有效的格式\nSELECT 1\n"
        assertTrue(SqlLogFileReader.parseContent(content).isEmpty())
    }

    @Test
    fun `读取文件并排序`() {
        val dir = Files.createTempDirectory("f44-reader-test-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "test.sql")
        file.writeText("""
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

        """.trimIndent())
        val entries = SqlLogFileReader.readFromFile(file).sortedBy { it.timestamp }
        assertEquals(3, entries.size)
        // 按时间戳排序
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
        assertEquals("SELECT 3", entries[2].restoredSql)
    }

    @Test
    fun `读取目录`() {
        val dir = Files.createTempDirectory("f44-reader-dir-").toFile()
        dir.deleteOnExit()
        File(dir, "a.sql").writeText("--- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---\nSELECT 1\n\n")
        File(dir, "b.sql").writeText("--- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---\nSELECT 2\n\n")
        // 非 .sql 文件应被忽略
        File(dir, "ignore.txt").writeText("ignore me")

        val entries = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals(2, entries.size)
    }

    @Test
    fun `无源码行号时只返回文件`() {
        val content = "--- source: UserMapper.java | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(0, entries[0].sourceLine)
    }
}
