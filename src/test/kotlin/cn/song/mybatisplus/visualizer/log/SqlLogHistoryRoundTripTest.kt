package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动保存 → 历史读取 回环测试（F-44-2/F-44-3 真实场景）：
 *
 * 模拟真实链路——日志面板捕获 SQL（带源码位置与耗时）→ buffer 淘汰触发 [SqlLogAutoSaver.write]
 * 写入 `.idea/sql-visualizer/sql-history/` → 用户打开历史面板经 [SqlLogFileReader] 读回。
 * 断言写读两端字段完全一致（SQL 文本、源码位置、耗时、时间戳、条数），防止格式回归。
 */
class SqlLogHistoryRoundTripTest {

    /** 构造带完整元数据的真实条目（对齐 FilterProxyHandler 组装的 entry）。 */
    private fun capturedEntry(
        sql: String,
        sourceFile: String? = "src/main/java/com/example/mapper/UserMapper.java",
        sourceLine: Int = 42,
        executionTimeMs: Long? = 5L,
        timestamp: Long = 1724567890000L,
    ) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = timestamp,
        executionTimeMs = executionTimeMs,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
    )

    private fun tempDir(): File {
        val dir = Files.createTempDirectory("roundtrip-").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun timestampStr(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).format(Date(ms))

    @Test
    fun `真实链路_捕获条目自动保存后读取回环_字段全一致`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // 真实捕获条目：带源码位置 + 5ms 耗时
        val captured = capturedEntry(
            sql = "SELECT id, username, password FROM user WHERE id = 1",
            sourceFile = "src/main/java/com/example/mapper/UserMapper.java",
            sourceLine = 42,
            executionTimeMs = 5L,
        )
        saver.write(captured)
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals("应生成 1 个文件", 1, files.size)
        // 文件头部应为标准 SQL 注释 `--`（F-44-2 双横线，非 `---`）
        val firstLine = files[0].readText().lineSequence().first()
        assertEquals("-- source: src/main/java/com/example/mapper/UserMapper.java:42 | time: 5ms | ${timestampStr(captured.timestamp)} --", firstLine)

        // 用户打开历史面板读取
        val entries = SqlLogFileReader.readFromFile(files[0])
        assertEquals("条数一致", 1, entries.size)
        val read = entries[0]
        assertEquals("SQL 文本一致", captured.restoredSql, read.restoredSql)
        assertEquals("源码文件一致", captured.sourceFile, read.sourceFile)
        assertEquals("源码行号一致", captured.sourceLine, read.sourceLine)
        assertEquals("耗时一致", captured.executionTimeMs, read.executionTimeMs)
        assertEquals("时间戳一致", captured.timestamp, read.timestamp)
    }

    @Test
    fun `真实链路_多文件多条目_文件条数统计与读取一致`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // 模拟两天的历史：今天 3 条、昨天 2 条
        saver.write(capturedEntry("SELECT 1", sourceFile = "A.java", sourceLine = 1, executionTimeMs = 1L))
        saver.write(capturedEntry("SELECT 2", sourceFile = "A.java", sourceLine = 2, executionTimeMs = 2L))
        saver.write(capturedEntry("SELECT 3", sourceFile = "A.java", sourceLine = 3, executionTimeMs = 3L))
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals("同一天应写入 1 个文件", 1, files.size)
        // 一级文件列表显示的条数统计（轻量 countInFile）
        assertEquals("countInFile 条数 = 3", 3, SqlLogFileReader.countInFile(files[0]))
        // 二级读取的完整解析
        assertEquals("readFromFile 条数 = 3", 3, SqlLogFileReader.readFromFile(files[0]).size)

        // 第二个文件（模拟另一天：固定文件名，避免与当天文件同名被覆盖——newBufferedWriter 默认 TRUNCATE）
        val saver2 = SqlLogAutoSaver()
        saver2.configure(dir.absolutePath, "sql-history-2026-08-30.sql", null)
        saver2.write(capturedEntry("SELECT A", sourceFile = "B.java", sourceLine = 10, executionTimeMs = null, timestamp = 1724567000000L))
        saver2.write(capturedEntry("SELECT B", sourceFile = "B.java", sourceLine = 11, executionTimeMs = null, timestamp = 1724567000001L))
        saver2.close()
        assertEquals("两天共 2 个文件", 2, dir.listFiles { f -> f.extension == "sql" }!!.size)

        // 无耗时条目：文件写 `—`，读回 null
        val all = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals("两文件共 5 条", 5, all.size)
        val noDuration = all.filter { it.restoredSql.startsWith("SELECT A") || it.restoredSql.startsWith("SELECT B") }
        assertEquals(2, noDuration.size)
        noDuration.forEach { assertNull("无耗时条目读回 null", it.executionTimeMs) }
    }

    @Test
    fun `真实链路_旧F43格式文件仍可读取`() {
        // 升级前遗留的 F-43 文件（无源码位置、无耗时），历史面板应能展示
        val dir = tempDir()
        val legacy = File(dir, "sql-history-2026-08-20.sql")
        legacy.writeText(
            "-- 2026-08-20 10:00:00\nSELECT * FROM user WHERE id = 1\n\n" +
                "-- 2026-08-20 10:00:01\nUPDATE user SET name = 'x' WHERE id = 2\n\n",
        )
        val entries = SqlLogFileReader.readAll(dir.absolutePath)
        assertEquals(2, entries.size)
        assertEquals("SELECT * FROM user WHERE id = 1", entries[0].restoredSql)
        assertEquals("UPDATE user SET name = 'x' WHERE id = 2", entries[1].restoredSql)
        assertNull("旧格式无源码位置", entries[0].sourceFile)
        assertNull("旧格式无耗时", entries[0].executionTimeMs)
        assertEquals("旧格式条数统计一致", 2, SqlLogFileReader.countInFile(legacy))
    }

    @Test
    fun `真实链路_混合格式单文件_新老格式条目共存`() {
        // 升级过渡期：同文件内既有 F-44-2 双横线、又有历史 F-43 时间戳头
        val dir = tempDir()
        val file = File(dir, "sql-history-2026-08-31.sql")
        file.writeText(
            "-- source: UserMapper.java:10 | time: 3ms | 2026-08-31 17:30:00.000 --\n" +
                "SELECT 1\n\n" +
                "-- 2026-08-31 09:00:00\n" +
                "SELECT 2\n\n" +
                "-- source: OrderMapper.java:20 | time: 10ms | 2026-08-31 17:31:00.000 --\n" +
                "SELECT 3\n\n",
        )
        val entries = SqlLogFileReader.readFromFile(file) // 保持文件内顺序（readAll 会按 timestamp 重排）
        assertEquals(3, entries.size)
        // 新格式：源码 + 耗时
        assertEquals("UserMapper.java", entries[0].sourceFile)
        assertEquals(3L, entries[0].executionTimeMs)
        // 旧格式：无源码无耗时
        assertNull(entries[1].sourceFile)
        // 新格式
        assertEquals("OrderMapper.java", entries[2].sourceFile)
        assertEquals(10L, entries[2].executionTimeMs)
        assertEquals("轻量计数与完整解析一致", 3, SqlLogFileReader.countInFile(file))
    }

    @Test
    fun `真实链路_空历史目录_返回空列表`() {
        val dir = tempDir()
        assertEquals(0, SqlLogFileReader.readAll(dir.absolutePath).size)
        assertEquals(0, SqlLogFileReader.countInFile(File(dir, "not-exists.sql")))
    }

    @Test
    fun `真实链路_带参数条目写读回环_历史页参数识别与日志页一致`() {
        // T-HistParam：捕获条目（rawSql + params）→ 自动落盘 → 历史面板读回 →
        // 统一识别通道（toPreviewResult）产出与日志页相同的参数形态
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM user WHERE id = ? AND name = ?",
                restoredSql = "SELECT * FROM user WHERE id = 42 AND name = 'neo'",
                params = listOf(LogSqlParam("42", "Long"), LogSqlParam("neo", "String")),
                timestamp = 1724567890000L,
                executionTimeMs = 5L,
                sourceFile = "src/main/java/com/example/mapper/UserMapper.java",
                sourceLine = 42,
            ),
        )
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals(1, files.size)
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("rawSql 回环", "SELECT * FROM user WHERE id = ? AND name = ?", read.rawSql)
        assertEquals("restoredSql 回环", "SELECT * FROM user WHERE id = 42 AND name = 'neo'", read.restoredSql)
        assertEquals(2, read.params.size)

        // 历史页预览构建（formatSql=false 现状）与日志页识别通道同源
        val result = read.toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals(listOf("param1", "param2"), result.unresolvedParams)
        assertEquals(mapOf("param1" to "42", "param2" to "neo"), result.initialParamValues)
        assertEquals(listOf(false, true), result.paramHints.map { it.quote })
        assertNull(result.warning)
    }

    // ---------- M56 T-M56-00：扩展字段（mapperStatement/errorText/requestTag/updateCount）回环 ----------

    @Test
    fun `真实链路_扩展字段条目写读回环_四字段不丢`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        // agent 通道形态：无参数、无占位符，但扩展字段齐备——pd 行须因扩展字段在场而写入
        val captured = LogSqlEntry(
            rawSql = "UPDATE user SET name = 'x' WHERE id = 7",
            restoredSql = "UPDATE user SET name = 'x' WHERE id = 7",
            params = emptyList(),
            timestamp = 1724567890000L,
            executionTimeMs = 2L,
            mapperStatement = "com.example.mapper.UserMapper.updateName",
            errorText = null,
            requestTag = "req-88",
            updateCount = 1L,
        )
        saver.write(captured)
        saver.close()

        val files = dir.listFiles { f -> f.extension == "sql" }!!
        assertEquals(1, files.size)
        val read = SqlLogFileReader.readFromFile(files[0]).single()
        assertEquals("mapperStatement 回环", captured.mapperStatement, read.mapperStatement)
        assertEquals("requestTag 回环", captured.requestTag, read.requestTag)
        assertEquals("updateCount 回环", captured.updateCount, read.updateCount)
        assertNull("无报错条目回环 null", read.errorText)
    }

    @Test
    fun `真实链路_报错条目写读回环_errorText不丢`() {
        val dir = tempDir()
        val saver = SqlLogAutoSaver()
        saver.configure(dir.absolutePath, "sql-history-\$yyyy-MM-dd\$.sql", null)
        saver.write(
            LogSqlEntry(
                rawSql = "SELECT * FROM no_such_table",
                restoredSql = "SELECT * FROM no_such_table",
                params = emptyList(),
                timestamp = 1724567890001L,
                errorText = "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
            ),
        )
        saver.close()
        val read = SqlLogFileReader.readFromFile(dir.listFiles { f -> f.extension == "sql" }!![0]).single()
        assertEquals(
            "errorText 回环",
            "BadSqlGrammarException: Table 'no_such_table' doesn't exist",
            read.errorText,
        )
        assertNull(read.mapperStatement)
    }
}
