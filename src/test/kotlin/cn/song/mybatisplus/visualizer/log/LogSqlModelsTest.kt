package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日志条目 → 日志文本渲染（T-M38）：`toLogText` 输出与 [MyBatisLogParser] 宽容匹配互逆
 * （round-trip——渲染文本重新解析可得等价条目），供「发送到日志转 SQL」联动复用。
 */
class LogSqlModelsTest {

    @Test
    fun `无参数条目只渲染 Preparing 行`() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        assertEquals("==>  Preparing: SELECT 1", entry.toLogText())
    }

    @Test
    fun `带类型标注参数渲染为 value 括号 type 形态`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ? AND name = ?",
            restoredSql = "SELECT * FROM t WHERE id = 1 AND name = '张三'",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("张三", "String")),
        )
        assertEquals(
            "==>  Preparing: SELECT * FROM t WHERE id = ? AND name = ?\n==> Parameters: 1(Long), 张三(String)",
            entry.toLogText(),
        )
    }

    @Test
    fun `null 参数渲染为 null 字面量`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = NULL",
            params = listOf(LogSqlParam(null, null)),
        )
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: null", entry.toLogText())
    }

    @Test
    fun `无类型标注参数直出值`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = 'x'",
            params = listOf(LogSqlParam("x", null)),
        )
        assertEquals("==>  Preparing: SELECT * FROM t WHERE a = ?\n==> Parameters: x", entry.toLogText())
    }

    @Test
    fun `渲染文本可被解析器重新解析为等价条目`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ? AND name = ? AND age > ?",
            restoredSql = "SELECT * FROM t WHERE id = 1 AND name = 'a, b' AND age > 3",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("a, b", "String"), LogSqlParam("3", "Integer")),
        )
        val parser = MyBatisLogParser()
        entry.toLogText().lineSequence().forEach { parser.feed(it) }
        parser.closePending()
        val parsed = parser.takeEntries().single()
        assertEquals(entry.rawSql, parsed.rawSql)
        assertEquals(entry.params, parsed.params)
        assertEquals(entry.restoredSql, parsed.restoredSql)
        assertNull("round-trip 后不应残留未闭合条目", parser.closePending())
    }

    // ---------- T-LogRowNumber：捕获序号字段 ----------

    @Test
    fun `sequenceNumber 默认为 0 表示无行号`() {
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList())
        assertEquals(0L, entry.sequenceNumber)
    }

    @Test
    fun `sequenceNumber 显式赋值与 copy 语义`() {
        val entry = LogSqlEntry(
            rawSql = "SELECT 1",
            restoredSql = "SELECT 1",
            params = emptyList(),
            sequenceNumber = 7L,
        )
        assertEquals(7L, entry.sequenceNumber)
        // copy 不带参保留序号；显式覆盖生效（buffer 入队分配即走 copy）
        assertEquals(7L, entry.copy().sequenceNumber)
        assertEquals(8L, entry.copy(sequenceNumber = 8L).sequenceNumber)
        assertEquals("SELECT 1", entry.copy(sequenceNumber = 8L).restoredSql)
    }
}
