package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 自动保存历史 SQL 写入器单元测试（F-43，纯 JUnit，无平台依赖）。
 */
class SqlLogAutoSaverTest {

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1724567890000L, // 固定时间戳方便断言
    )

    private fun tempDir(): String {
        val dir = Files.createTempDirectory("f43-test-").toFile()
        dir.deleteOnExit()
        return dir.absolutePath
    }

    @Test
    fun `写入单条生成正确格式`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure("", "test-\$yyyy-MM-dd\$.sql", dir)
        saver.write(entry("SELECT 1"))
        val file = saver.currentFile()
        assertNotNull("File should be created", file)
        // 读取文件内容前不 close（close 会置 null）
        val content = file!!.readText()
        // F-44-2：新格式 -- source: xxx | time: xxx | timestamp --（标准 SQL 单行注释）
        assertTrue("Should contain source header: $content", content.contains("source: unknown"))
        assertTrue("Should contain duration: $content", content.contains("time: —"))
        assertTrue("Should contain timestamp: $content", content.matches(Regex("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*")))
        assertTrue("Should contain SQL: $content", content.contains("SELECT 1"))
        saver.close()
    }

    @Test
    fun `写入头使用双横线注释`() {
        // F-44-2：注释头部必须为 `--` 起结（标准 SQL 单行注释），不再用 `---`
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure("", "test.sql", dir)
        saver.write(entry("SELECT 1"))
        val file = saver.currentFile()!!
        val content = file.readText()
        // 起结行 -- source: ... -- （中间可能有内容，end 必须是 ` --`，不能再用 ` ---`）
        val firstLine = content.lineSequence().first()
        assertTrue("首行应以 `--` 起始: $firstLine", firstLine.startsWith("-- source:"))
        assertTrue("首行应以 ` --` 结尾: $firstLine", firstLine.endsWith(" --"))
        assertFalse("首行不应再有 `---`: $firstLine", firstLine.startsWith("---"))
        assertFalse("首行不应再以 `---` 结尾: $firstLine", firstLine.endsWith("---"))
        saver.close()
    }

    @Test
    fun `多条追加不覆盖`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure("", "test.sql", dir)
        saver.write(entry("SELECT 1"))
        saver.write(entry("SELECT 2"))
        saver.write(entry("SELECT 3"))
        val file = saver.currentFile()
        assertNotNull(file)
        val content = file!!.readText()
        // F-44-2：新格式以 "-- source:" 开头（标准 SQL 单行注释，两个 `-`）
        val count = content.split("-- source:").size - 1
        assertEquals(3, count)
        saver.close()
    }

    @Test
    fun `文件名模板解析`() {
        val saver = SqlLogAutoSaver()
        saver.configure("", "sql-history-\$yyyy-MM-dd\$.sql", "/tmp")
        val resolved = saver.resolveFileName()
        // resolveFileName 使用当前时间，验证格式匹配即可
        assertTrue("Expected pattern like sql-history-YYYY-MM-DD.sql, got: $resolved",
            resolved.matches(Regex("sql-history-\\d{4}-\\d{2}-\\d{2}\\.sql")))
        // 验证各占位符都被替换（不应再包含 $） {
        assertFalse("All placeholders should be replaced: $resolved", resolved.contains("$"))
    }

    @Test
    fun `文件名模板解析_冒号替换为横线`() {
        val saver = SqlLogAutoSaver()
        saver.configure("", "log-\$HH:mm:ss\$.sql", "/tmp")
        val resolved = saver.resolveFileName()
        assertFalse("':' should be replaced with '-': $resolved", resolved.contains(":"))
        assertTrue("Expected pattern like log-HH-mm-ss.sql, got: $resolved",
            resolved.matches(Regex("log-\\d{2}-\\d{2}-\\d{2}\\.sql")))
    }

    @Test
    fun `文件名模板解析_未知占位符原样保留`() {
        val saver = SqlLogAutoSaver()
        saver.configure("", "log-\$unknown\$-\$yyyy\$.sql", "/tmp")
        val resolved = saver.resolveFileName()
        assertTrue("Unknown placeholder should be preserved: $resolved",
            resolved.startsWith("log-\$unknown\$-"))
    }

    @Test
    fun `默认目录解析`() {
        val saver = SqlLogAutoSaver()
        saver.configure("", "test.sql", "/home/user/myproject")
        val resolved = saver.resolveFileName()
        assertEquals("test.sql", resolved)
    }

    @Test
    fun `自定义路径`() {
        val saver = SqlLogAutoSaver()
        val customDir = tempDir()
        saver.configure(customDir, "custom.sql", "/other/path")
        saver.write(entry("SELECT 1"))
        val file = saver.currentFile()
        assertNotNull(file)
        assertEquals(customDir, file!!.parentFile.absolutePath)
        assertEquals("custom.sql", file.name)
        saver.close()
    }

    @Test
    fun `无效路径不抛异常`() {
        val saver = SqlLogAutoSaver()
        // 使用包含空字符的路径（Windows/macOS 均不允许路径含 \0）
        saver.configure("invalid${Char(0)}path", "test.sql", null)
        // write 不应抛出异常
        saver.write(entry("SELECT 1"))
        // currentFile 应该为 null（因为文件无法创建）
        assertNull(saver.currentFile())
        saver.close()
    }

    @Test
    fun `close 后写入安全`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure("", "test.sql", dir)
        saver.write(entry("SELECT 1"))
        saver.close()
        // close 后再 write，不应抛异常（懒打开新文件）
        saver.write(entry("SELECT 2"))
        saver.close()
    }

    @Test
    fun `configure 关闭旧文件重置状态`() {
        val saver = SqlLogAutoSaver()
        val dir1 = tempDir()
        val dir2 = tempDir()
        saver.configure(dir1, "file1.sql", null)
        saver.write(entry("SELECT 1"))
        assertNotNull(saver.currentFile())
        // 切换目录
        saver.configure(dir2, "file2.sql", null)
        // 写入后应在 dir2 创建文件
        saver.write(entry("SELECT 2"))
        val file = saver.currentFile()
        assertNotNull(file)
        assertEquals(dir2, file!!.parentFile.absolutePath)
        assertEquals("file2.sql", file.name)
        saver.close()
    }
}
