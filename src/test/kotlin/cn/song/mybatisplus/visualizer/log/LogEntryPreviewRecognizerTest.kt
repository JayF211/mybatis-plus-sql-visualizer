package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志条目 → 预览参数识别单元测试（T-M42）：`?` 数 == 参数数 ⇔ 匹配识别；
 * 参数行来源按序 `param1..N`、值预填日志参数值、类型 → 引号提示；其余回退现状。
 */
class LogEntryPreviewRecognizerTest {

    private fun entry(
        rawSql: String,
        params: List<LogSqlParam>,
        restoredSql: String = "",
    ) = LogSqlEntry(rawSql = rawSql, restoredSql = restoredSql.ifEmpty { rawSql }, params = params)

    // ------------------------------------------------------------------
    // 匹配识别
    // ------------------------------------------------------------------

    @Test
    fun `占位符数与参数数一致时识别成功`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE username = ? AND id = ?",
            params = listOf(LogSqlParam("admin", "String"), LogSqlParam("10", "Long")),
        ).toRecognizedPreview()
        assertTrue("应识别成功", recognized.recognized)
        assertEquals("参数来源按 `?` 出现顺序", listOf("param1", "param2"), recognized.unresolvedParams)
        assertEquals("值按序预填", mapOf("param1" to "admin", "param2" to "10"), recognized.initialValues)
        assertEquals("sql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.sql)
        assertEquals("compactSql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.compactSql)
    }

    @Test
    fun `类型映射为引号提示`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
            params = listOf(
                LogSqlParam("abc", "String"),
                LogSqlParam("10", "Long"),
                LogSqlParam("true", "Boolean"),
            ),
        ).toRecognizedPreview()
        assertEquals(
            "String 引号 / 数值·布尔原样",
            listOf(true, false, false),
            recognized.paramHints.map { it.quote },
        )
    }

    @Test
    fun `无类型标注提示为 null 走面板启发式`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("abc", null)),
        ).toRecognizedPreview()
        assertEquals(listOf(null as Boolean?), recognized.paramHints.map { it.quote })
    }

    @Test
    fun `时间等未知类型标注保守引号`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(
                LogSqlParam("2026-08-16 12:00:00.0", "Timestamp"),
                LogSqlParam("ACTIVE", "StatusEnum"),
            ),
        ).toRecognizedPreview()
        assertEquals(listOf(true, true), recognized.paramHints.map { it.quote })
    }

    // ------------------------------------------------------------------
    // 回退（未识别）
    // ------------------------------------------------------------------

    @Test
    fun `参数为空时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = emptyList(),
        ).toRecognizedPreview()
        assertFalse("参数为空应回退", recognized.recognized)
        assertTrue(recognized.unresolvedParams.isEmpty())
        assertTrue(recognized.initialValues.isEmpty())
        assertTrue(recognized.paramHints.isEmpty())
    }

    @Test
    fun `参数多于占位符时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")),
        ).toRecognizedPreview()
        assertFalse("参数多于占位符应回退", recognized.recognized)
    }

    @Test
    fun `参数少于占位符时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(LogSqlParam("1", "Long")),
        ).toRecognizedPreview()
        assertFalse("参数少于占位符应回退", recognized.recognized)
    }

    @Test
    fun `无占位符无参数纯静态 SQL 回退`() {
        val recognized = entry(
            rawSql = "SELECT 1 FROM t",
            params = emptyList(),
        ).toRecognizedPreview()
        assertFalse("纯静态 SQL 应回退", recognized.recognized)
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `null 参数值预填空串`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam(null, null)),
        ).toRecognizedPreview()
        assertTrue(recognized.recognized)
        assertEquals(mapOf("param1" to ""), recognized.initialValues)
        assertEquals(listOf(null as Boolean?), recognized.paramHints.map { it.quote })
    }

    @Test
    fun `字符串字面量内问号计数与还原语义一致`() {
        // rawSql 中 `'what?'` 的 `?` 同语义计数（LogSqlRestorer 裸 indexOf 替换）——
        // 3 个 `?` 配 3 个参数 ⇔ 还原无残留，识别成功
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE note = 'what?' AND a = ? AND b = ?",
            params = listOf(LogSqlParam("x?", "String"), LogSqlParam("1", "Long"), LogSqlParam("2", "Long")),
        ).toRecognizedPreview()
        assertTrue("字面量内 ? 与占位符同语义计数", recognized.recognized)
        assertEquals(listOf("param1", "param2", "param3"), recognized.unresolvedParams)
        assertEquals(
            mapOf("param1" to "x?", "param2" to "1", "param3" to "2"),
            recognized.initialValues,
        )
    }

    @Test
    fun `IN 列表多占位符按序识别`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE id IN (?, ?, ?)",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long"), LogSqlParam("3", "Long")),
        ).toRecognizedPreview()
        assertTrue(recognized.recognized)
        assertEquals(listOf("param1", "param2", "param3"), recognized.unresolvedParams)
        assertEquals(
            mapOf("param1" to "1", "param2" to "2", "param3" to "3"),
            recognized.initialValues,
        )
        assertTrue(recognized.paramHints.all { it.quote == false })
    }
}
