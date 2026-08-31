package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogSqlEntry.toLogText] 纯函数（internal）测试（F-44 续 · 补用例大波次）：
 * 条目回渲为 MyBatis 日志文本（`==>  Preparing:` + `==> Parameters:`），与
 * [MyBatisLogParser] 的 mixedPrefix 宽容匹配互逆——本测试锁定该渲染契约，
 * 防止「发送到日志转 SQL」链路错位。无 IntelliJ 依赖，纯 JUnit。
 */
class LogSqlEntryTest {

    @Test
    fun testToLogText_无参数_仅Preparing行() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        val text = entry.toLogText()
        assertTrue(text.startsWith("==>  Preparing: SELECT 1"))
        assertFalse("无参数不应包含 Parameters 行", text.contains("Parameters:"))
    }

    @Test
    fun testToLogText_含参数_渲染类型标注() {
        val entry = LogSqlEntry(
            rawSql = "select * from t where id=? and name=?",
            restoredSql = "select * from t where id=? and name=?",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("abc", "String")),
        )
        val text = entry.toLogText()
        assertTrue(text.contains("==>  Preparing: select * from t where id=? and name=?"))
        assertTrue(text.contains("==> Parameters: 1(Long), abc(String)"))
    }

    @Test
    fun testToLogText_null参数_渲染为null() {
        val entry = LogSqlEntry(
            rawSql = "select ?",
            restoredSql = "select ?",
            params = listOf(LogSqlParam(null, null)),
        )
        val text = entry.toLogText()
        assertTrue(text.contains("==> Parameters: null"))
    }

    @Test
    fun testToLogText_结构为Preparing加Parameters两行() {
        val entry = LogSqlEntry(
            rawSql = "insert into t(a) values(?)",
            restoredSql = "insert into t(a) values(?)",
            params = listOf(LogSqlParam("9", "Integer")),
        )
        val text = entry.toLogText()
        val lines = text.lines()
        assertEquals("应恰为 Preparing + Parameters 两行", 2, lines.size)
        assertTrue(lines[0].startsWith("==>  Preparing:"))
        assertTrue(lines[1].startsWith("==> Parameters:"))
    }
}
