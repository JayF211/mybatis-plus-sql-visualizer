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
    fun `解析双横线新版格式`() {
        // F-44-2：默认写作 `-- source: ... --`（两个 `-` 起结），Reader 必须正确解析
        val content = "-- source: src/main/java/UserMapper.java:42 | time: 5ms | 2026-08-31 17:30:00.123 --\nSELECT * FROM user WHERE id = 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("SELECT * FROM user WHERE id = 1", entry.restoredSql)
        assertEquals("src/main/java/UserMapper.java", entry.sourceFile)
        assertEquals(42, entry.sourceLine)
        assertEquals(5L, entry.executionTimeMs)
    }

    @Test
    fun `兼容三横线历史新版格式`() {
        // F-44-2 已停止生产三横线，但历史文件仍可能存在——Reader 必须向后兼容
        val content = "--- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        assertEquals("SELECT 1", entries[0].restoredSql)
        assertEquals("A.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
    }

    @Test
    fun `解析混合格式_F44-2与F43共存`() {
        // 验证新版本启用的双横线格式能与旧版时间戳格式共存
        val content = """
            -- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- 2026-08-31 09:00:00
            SELECT 2

            -- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:30:01.000 --
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
        assertNull(entries[1].sourceFile)
        assertEquals("OrderMapper.java", entries[2].sourceFile)
        assertEquals(10L, entries[2].executionTimeMs)
    }

    @Test
    fun `解析无源码行号时只返回文件`() {
        val content = "--- source: UserMapper.java | time: 5ms | 2026-08-25 10:00:00.123 ---\nSELECT 1\n\n"
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(1, entries.size)
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(0, entries[0].sourceLine)
    }

    // ---------- F-44-3：countInFile 轻量条数统计（一级文件列表数据源） ----------

    @Test
    fun `countInFile 统计新版双横线格式`() {
        val dir = Files.createTempDirectory("f443-count-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "a.sql")
        file.writeText("""
            -- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- source: B.java:2 | time: 2ms | 2026-08-31 17:30:01.000 --
            SELECT 2

            -- source: C.java:3 | time: 3ms | 2026-08-31 17:30:02.000 --
            SELECT 3

        """.trimIndent())
        assertEquals(3, SqlLogFileReader.countInFile(file))
        // 与 readFromFile 完整解析一致
        assertEquals(3, SqlLogFileReader.readFromFile(file).size)
    }

    @Test
    fun `countInFile 兼容三横线与旧版格式混合`() {
        val dir = Files.createTempDirectory("f443-count-mix-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "mix.sql")
        file.writeText("""
            --- source: A.java:1 | time: 3ms | 2026-08-31 17:30:00.000 ---
            SELECT 1

            -- 2026-08-25 09:00:00
            SELECT 2

            -- source: B.java:2 | time: 5ms | 2026-08-31 17:30:01.000 --
            SELECT 3

        """.trimIndent())
        assertEquals(3, SqlLogFileReader.countInFile(file))
        // 与完整解析结果一致
        assertEquals(3, SqlLogFileReader.readFromFile(file).size)
    }

    @Test
    fun `countInFile 空文件与不存在文件返回0`() {
        val dir = Files.createTempDirectory("f443-count-empty-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "empty.sql")
        file.writeText("")
        assertEquals(0, SqlLogFileReader.countInFile(file))
        assertEquals(0, SqlLogFileReader.countInFile(File(dir, "missing.sql")))
    }

    // ------------------------------------------------------------------
    // 格式 v2：参数持久化行（T-HistParam）
    // ------------------------------------------------------------------

    @Test
    fun `v2带参数行读回_rawSql与params还原_restoredSql为正文`() {
        val payload = SqlLogParamCodec.encode(
            "SELECT * FROM t WHERE a = ? AND b = ?",
            listOf(LogSqlParam("x", "String"), LogSqlParam(null, "BIGINT")),
        )
        val dir = Files.createTempDirectory("histparam-v2-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "v2.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT * FROM t WHERE a = 'x' AND b = NULL\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals(1, entries.size)
        assertEquals("rawSql 从载荷还原（含 ?）", "SELECT * FROM t WHERE a = ? AND b = ?", entries[0].rawSql)
        assertEquals("restoredSql 为正文（剥结尾分号）", "SELECT * FROM t WHERE a = 'x' AND b = NULL", entries[0].restoredSql)
        assertEquals(
            "params 从载荷还原",
            listOf(LogSqlParam("x", "String"), LogSqlParam(null, "BIGINT")),
            entries[0].params,
        )
        // 读回条目可直接走统一识别通道（与日志 tab 同参）
        assertTrue(entries[0].toRecognizedPreview().recognized)
    }

    @Test
    fun `v2损坏参数行降级为正文注释行`() {
        val dir = Files.createTempDirectory("histparam-bad-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "bad.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: !!!not-base64!!! --\n" +
                "SELECT 1\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals(1, entries.size)
        assertEquals("解码失败 pd 行留在正文（多一行注释无害）", "-- pd: !!!not-base64!!! --\nSELECT 1", entries[0].restoredSql)
        assertEquals("-- pd: !!!not-base64!!! --\nSELECT 1", entries[0].rawSql)
        assertTrue(entries[0].params.isEmpty())
    }

    @Test
    fun `v2参数行不参与头部计数与块切分`() {
        val payload = SqlLogParamCodec.encode("SELECT ?", listOf(LogSqlParam("1", "Long")))
        val dir = Files.createTempDirectory("histparam-count-").toFile()
        dir.deleteOnExit()
        val file = File(dir, "mixed.sql")
        file.writeText(
            "-- source: A.java:1 | time: 1ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT '1'\n\n" +
                "-- 2026-09-11 08:00:00\n" +
                "SELECT 2\n\n",
        )
        // pd 行以 `-- ` 起始且无 `source:`——不排除会被旧头部判据误判，计数翻倍且块被劈开
        assertEquals("countInFile 不计 pd 行", 2, SqlLogFileReader.countInFile(file))
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals("pd 行不劈块：v2 条目 + F-43 条目", 2, entries.size)
        assertEquals("SELECT '1'", entries[0].restoredSql)
        assertEquals("SELECT 2", entries[1].restoredSql)
    }

    // ---------- T-LogHistoryRowNo：文件内条目顺序号 ----------

    @Test
    fun `parseContent 注入文件内顺序号_连续`() {
        val content = """
            --- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---
            SELECT 1

            --- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---
            SELECT 2

            --- source: C.java:3 | time: 3ms | 2026-08-25 10:00:02.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.sequenceNumber })
    }

    @Test
    fun `parseContent 混合格式顺序号按文件解析顺序`() {
        // 新旧格式混合：序号按文件出现顺序连续（不按格式分组）
        val content = """
            -- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --
            SELECT 1

            -- 2026-08-31 09:00:00
            SELECT 2

            --- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:30:01.000 ---
            SELECT 3

        """.trimIndent()
        val entries = SqlLogFileReader.parseContent(content)
        assertEquals(3, entries.size)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.sequenceNumber })
    }

    @Test
    fun `readAll 多文件每文件内序号独立从1起`() {
        val dir = Files.createTempDirectory("loghistrow-").toFile()
        dir.deleteOnExit()
        File(dir, "a.sql").writeText(
            "--- source: A.java:1 | time: 1ms | 2026-08-25 10:00:00.000 ---\nSELECT 1\n\n" +
                "--- source: B.java:2 | time: 2ms | 2026-08-25 10:00:01.000 ---\nSELECT 2\n\n",
        )
        File(dir, "b.sql").writeText(
            "--- source: C.java:1 | time: 3ms | 2026-08-25 10:00:02.000 ---\nSELECT 3\n\n",
        )
        // 历史面板按单文件展示：每文件内序号各自 1..N（不跨文件连续）
        assertEquals(listOf(1L, 2L), SqlLogFileReader.readFromFile(File(dir, "a.sql")).map { it.sequenceNumber })
        assertEquals(listOf(1L), SqlLogFileReader.readFromFile(File(dir, "b.sql")).map { it.sequenceNumber })
    }

    @Test
    fun `空内容序号无影响`() {
        assertTrue(SqlLogFileReader.parseContent("").isEmpty())
    }
}
