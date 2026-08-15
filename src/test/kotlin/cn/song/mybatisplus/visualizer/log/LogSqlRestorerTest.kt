package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日志参数还原引号规则单元测试（T-M31-02）。
 */
class LogSqlRestorerTest {

    private val restorer = LogSqlRestorer()

    private fun render(value: String, type: String? = null): String =
        restorer.renderParam(LogSqlParam(value, type))

    @Test
    fun `数值类型原样`() {
        assertEquals("1", render("1", "Long"))
        assertEquals("42", render("42", "Integer"))
        assertEquals("12.5", render("12.5", "Double"))
        assertEquals("100", render("100", "BigDecimal"))
        assertEquals("7", render("7", "Short"))
        assertEquals("3.14", render("3.14", "Float"))
        assertEquals("255", render("255", "Byte"))
    }

    @Test
    fun `布尔类型原样`() {
        assertEquals("true", render("true", "Boolean"))
        assertEquals("false", render("false", "boolean"))
    }

    @Test
    fun `字符串类型加引号并转义单引号`() {
        assertEquals("'abc'", render("abc", "String"))
        assertEquals("'O''Brien'", render("O'Brien", "String"))
        assertEquals("''", render("", "String"))
        assertEquals("'a''b''c'", render("a'b'c", "String"))
    }

    @Test
    fun `时间类型加引号`() {
        assertEquals("'2025-01-01 12:00:00.0'", render("2025-01-01 12:00:00.0", "Timestamp"))
        assertEquals("'2025-01-01'", render("2025-01-01", "LocalDate"))
        assertEquals("'12:00:00'", render("12:00:00", "Time"))
        assertEquals("'2025-01-01T12:00:00'", render("2025-01-01T12:00:00", "LocalDateTime"))
        assertEquals("'2025-01-01'", render("2025-01-01", "Date"))
    }

    @Test
    fun `null 字面量渲染为 NULL`() {
        assertEquals("NULL", restorer.renderParam(LogSqlParam(null, null)))
    }

    @Test
    fun `无类型标注启发式判定`() {
        assertEquals("1", render("1"))
        assertEquals("-2.5", render("-2.5"))
        assertEquals("1.0E10", render("1.0E10"))
        assertEquals("true", render("true"))
        assertEquals("false", render("false"))
        assertEquals("'abc'", render("abc"))
        assertEquals("'a, b'", render("a, b"))
        assertEquals("'2025-01-01'", render("2025-01-01"))
    }

    @Test
    fun `未知类型标注保守加引号`() {
        assertEquals("'ACTIVE'", render("ACTIVE", "StatusEnum"))
        assertEquals("'[B@1a2b3c'", render("[B@1a2b3c", "byte[]"))
        assertEquals("'dev'", render("dev", "String")) // 字符串列数字启发式在日志场景有类型标注时不生效
    }

    @Test
    fun `restore 按序替换多个占位符`() {
        assertEquals(
            "SELECT * FROM t WHERE id = 1 AND name = 'abc' AND ts = NULL",
            restorer.restore(
                "SELECT * FROM t WHERE id = ? AND name = ? AND ts = ?",
                listOf(
                    LogSqlParam("1", "Long"),
                    LogSqlParam("abc", "String"),
                    LogSqlParam(null, null),
                ),
            ),
        )
    }

    @Test
    fun `restore 无参数时原样返回`() {
        assertEquals("SELECT 1", restorer.restore("SELECT 1", emptyList()))
    }

    @Test
    fun `restore 值含问号不干扰后续替换`() {
        // 值本身含 `?`（字符串值）——替换从渲染文本之后继续扫描
        assertEquals(
            "SELECT * FROM t WHERE a = 'x?y' AND b = 2",
            restorer.restore(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                listOf(LogSqlParam("x?y", "String"), LogSqlParam("2", "Long")),
            ),
        )
    }

    @Test
    fun `IN 列表多占位符`() {
        assertEquals(
            "SELECT * FROM t WHERE id IN (1, 2, 3)",
            restorer.restore(
                "SELECT * FROM t WHERE id IN (?, ?, ?)",
                listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long"), LogSqlParam("3", "Long")),
            ),
        )
    }
}
