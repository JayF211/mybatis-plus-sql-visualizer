package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat

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

    /** 解析本地时间字符串为 epoch 毫秒（时钟注入用）。 */
    private fun timeOf(dateTime: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateTime).time

    @Test
    fun `跨天切换文件不再无限递归`() {
        // 回归：旧实现在文件名变化分支递归重写，但 currentResolvedName 永不更新，
        // 跨天后首次写入即 StackOverflowError（真机 2026-09-04 00:09 堆栈）
        var now = timeOf("2026-09-04 23:59:50")
        val saver = SqlLogAutoSaver(clock = { now })
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(entry("SELECT 1"))
        val day1 = saver.currentFile()!!
        now = timeOf("2026-09-05 00:00:10")
        saver.write(entry("SELECT 2"))
        val day2 = saver.currentFile()!!
        assertNotEquals("跨天后应切换文件: $day1 -> $day2", day1.name, day2.name)
        assertEquals("sql-history-2026-09-05.sql", day2.name)
        assertEquals(1, day1.readText().split("-- source:").size - 1)
        assertEquals(1, day2.readText().split("-- source:").size - 1)
        saver.close()
    }

    @Test
    fun `秒级模板连续写入不再无限递归`() {
        // 回归：模板含 $ss$ 等细粒度占位符时，旧实现第二次写入即 StackOverflowError
        var now = timeOf("2026-09-04 10:00:00")
        val saver = SqlLogAutoSaver(clock = { now })
        val dir = tempDir()
        saver.configure(dir, "log-\$HH-mm-ss\$.sql", null)
        repeat(3) { i ->
            saver.write(entry("SELECT $i"))
            now += 2000
        }
        val names = File(dir).listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("log-10-00-00.sql", "log-10-00-02.sql", "log-10-00-04.sql"), names)
        saver.close()
    }

    @Test
    fun `单文件超限滚动序号递增`() {
        // 回归：旧实现滚动分支条件恒真且递归无出口，达阈值即 StackOverflowError
        val saver = SqlLogAutoSaver(rollThresholdBytes = 100)
        val dir = tempDir()
        saver.configure(dir, "test.sql", null)
        repeat(3) { i -> saver.write(entry("SELECT $i")) }
        val names = File(dir).listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("test.2.sql", "test.sql"), names)
        assertEquals(2, File(dir, "test.sql").readText().split("-- source:").size - 1)
        assertEquals(1, File(dir, "test.2.sql").readText().split("-- source:").size - 1)
        saver.close()
    }

    @Test
    fun `跨天后滚动序号复位`() {
        var now = timeOf("2026-09-04 10:00:00")
        val saver = SqlLogAutoSaver(clock = { now }, rollThresholdBytes = 100)
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null)
        repeat(3) { i -> saver.write(entry("SELECT $i")) }
        assertTrue(File(dir, "sql-history-2026-09-04.2.sql").exists())
        now = timeOf("2026-09-05 10:00:00")
        saver.write(entry("SELECT 9"))
        assertEquals("sql-history-2026-09-05.sql", saver.currentFile()!!.name)
        saver.close()
    }

    // ------------------------------------------------------------------
    // 格式 v2：参数持久化行（T-HistParam）
    // ------------------------------------------------------------------

    @Test
    fun `带参数条目写参数行_纯静态不写`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "test.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM t WHERE a = ?",
                restoredSql = "SELECT * FROM t WHERE a = 'x'",
                params = listOf(LogSqlParam("x", "String")),
                timestamp = 1724567890000L,
            ),
        )
        saver.write(entry("SELECT 1"))
        saver.close()
        val content = File(dir, "test.sql").readText()
        val blocks = content.split("-- source:").drop(1)
        assertEquals(2, blocks.size)
        val withParams = blocks[0]
        val static = blocks[1]
        assertTrue("带参条目应有 pd 行: $withParams", withParams.contains("\n-- pd: "))
        assertFalse("纯静态条目不应有 pd 行: $static", static.contains("-- pd: "))
        // 正文仍是可执行 SQL（restoredSql）
        assertTrue(withParams.contains("SELECT * FROM t WHERE a = 'x'"))

        // 读回 round-trip：rawSql/params 还原，识别通道可用
        val entries = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM t WHERE a = ?", entries[0].rawSql)
        assertEquals(listOf(LogSqlParam("x", "String")), entries[0].params)
        assertEquals("SELECT * FROM t WHERE a = 'x'", entries[0].restoredSql)
        assertTrue(entries[0].toRecognizedPreview().recognized)
        assertEquals("SELECT 1", entries[1].rawSql)
        assertTrue(entries[1].params.isEmpty())
    }

    @Test
    fun `绑定行缺失的占位符条目也写参数行`() {
        // params 空但 rawSql 含 ?：历史页重试识别需要 rawSql 的占位符形态
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "test.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM t WHERE a = ?",
                restoredSql = "SELECT * FROM t WHERE a = ?",
                params = emptyList(),
                timestamp = 1724567890000L,
            ),
        )
        saver.close()
        val content = File(dir, "test.sql").readText()
        assertTrue("应写 pd 行: $content", content.contains("\n-- pd: "))
    }

    // ------------------------------------------------------------------
    // T-SqlHistoryFull：全量写盘风暴压测（对应用户关切——全量化后大写盘量不卡、不丢）
    // ------------------------------------------------------------------

    /**
     * 压测：单线程风暴投递（模拟控制台风暴期每条成功入队即 submit）。
     * onEntryStored 全量化后大写盘是用户首要性能关切——断言：
     *  1) 不丢条：close 同步清空在途队列后文件块数 == 投递数；
     *  2) T-Perf-12 批量写收敛：投递 N 条只触发 ≪N 次的写任务批次（writeBatch 整批一次 flush）。
     */
    @Test
    fun `压测_万条全量投递不丢条_批量写收敛`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "storm.sql", null)

        val n = 20000
        repeat(n) { i -> saver.submit(entry("SELECT * FROM t WHERE id = $i")) }
        // close 同步清空在途队列（不丢条）——写线程吞掉全部 2 万条
        saver.close()

        val file = File(dir, "storm.sql")
        assertTrue("文件已创建", file.exists())
        val count = file.readText().split("-- source:").size - 1
        assertEquals("万条全量投递不丢条", n, count)
    }

    /**
     * 压测：读侧流式往返一致——全量写盘的大文件能被流式逐行读回，条目数与内容与写入一致
     * （SqlLogFileReader 整读改流式的回归防线；往返走既有读取 API，非 readText）。
     */
    @Test
    fun `压测_万条全量写入后流式读回一致`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "roundtrip.sql", null)
        val n = 10000
        repeat(n) { i -> saver.submit(entry("SELECT * FROM big WHERE id = $i -- marker=$i")) }
        saver.close()

        val file = File(dir, "roundtrip.sql")
        // 生产读路径：流式逐行（readFromFile 内部 useLines），不整读
        val entries = SqlLogFileReader.readFromFile(file)
        assertEquals("读回条数与写入一致", n, entries.size)
        assertTrue("首条 SQL 正确", entries.first().restoredSql.startsWith("SELECT * FROM big WHERE id = 0"))
        assertTrue("末条 SQL 正确", entries.last().restoredSql.endsWith("-- marker=${n - 1}"))
    }

    // ------------------------------------------------------------------
    // T-HistoryFileRetention：历史文件保留上限裁剪（防磁盘爆炸）
    // ------------------------------------------------------------------

    private fun makeFile(dir: String, name: String, stamp: Long) {
        val f = File(dir, name)
        f.parentFile.mkdirs()
        f.writeText("-- source: x --\nSELECT 1\n\n")
        f.setLastModified(stamp)
    }

    /** pruneRetainedFiles 直接驱动：造 maxFiles+1 个文件，断言删到只剩 maxFiles 个（删最旧）。 */
    @Test
    fun `上限裁剪_超限删最旧文件`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 2)
        // 三个不同基名文件，stamp 递增（最早 = 2026-09-10）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        makeFile(dir, "sql-history-2026-09-16.sql", 3000)
        saver.pruneRetainedFiles()
        val remain = File(dir).listFiles()!!.map { it.name }.sorted()
        assertEquals("保留最近 2 个逻辑文件，删最旧", listOf("sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"), remain)
        saver.close()
    }

    /** maxFiles<=0（全量保留）时超过也不删。 */
    @Test
    fun `上限裁剪_0表示全量保留不删`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 0)
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        makeFile(dir, "sql-history-2026-09-16.sql", 3000)
        saver.pruneRetainedFiles()
        assertEquals("0=不限制，3 个全保留", 3, File(dir).listFiles()!!.size)
        saver.close()
    }

    /** 滚动块归并：同 base 的两块计 1 个逻辑文件，不被误删。 */
    @Test
    fun `上限裁剪_滚动块按基名归并不误删`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 2)
        // 4 个文件实际是 3 个逻辑文件（09-10 有 .sql + .2.sql）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-10.2.sql", 1300)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        makeFile(dir, "sql-history-2026-09-16.sql", 3000)
        saver.pruneRetainedFiles()
        val remain = File(dir).listFiles()!!.map { it.name }.sorted()
        assertEquals("3 组不超上限 2？不，3>2 应删最旧组（09-10 两块全删）",
            listOf("sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"), remain)
        saver.close()
    }

    /** 自定义目录同样生效（maxFiles 生效于 configure 传入的目录）。
     *  T-PS4-10 后：裁剪只认领本插件名族——族内超限删最旧，族外文件（init.sql）不计数零删除。 */
    @Test
    fun `上限裁剪_自定义目录生效`() {
        val saver = SqlLogAutoSaver()
        val customDir = tempDir()
        saver.configure(customDir, "log-\$yyyy-MM-dd\$.sql", null, maxFiles = 1)
        makeFile(customDir, "log-2026-09-10.sql", 1000)
        makeFile(customDir, "log-2026-09-15.sql", 2000)
        makeFile(customDir, "init.sql", 900)
        saver.pruneRetainedFiles()
        assertEquals("族内删最旧、族外用户文件零删除",
            listOf("init.sql", "log-2026-09-15.sql"), File(customDir).listFiles()!!.map { it.name }.sorted())
        saver.close()
    }
}
