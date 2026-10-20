package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_render_logs 纯函数核心测试（T-McpToolset2 / T-MTS2-01）：三解析器装配与面板
 * `buildParsers` 同参同构；普通 JUnit 零 IDE fixture（解析器为纯类）。
 * 前缀/格式细节由 MyBatisLogParser/HibernateLogParser/JdbcTemplateLogParser 各自测试覆盖，
 * 此处锁工具层装配与条目元信息透出。
 */
class RenderLogsToolTest {

    @Test
    fun `MyBatis日志对还原为可执行SQL`() {
        val text = "==>  Preparing: SELECT * FROM t_user WHERE id = ?\n" +
            "==> Parameters: 1(Long)"
        val entries = SqlVisualizerRenderLogsTool.renderLogEntries(text)
        org.junit.Assert.assertEquals(1, entries.size)
        org.junit.Assert.assertEquals(cn.song.mybatisplus.visualizer.log.SqlSource.MYBATIS, entries[0].source)
        assertTrue(entries[0].restoredSql, entries[0].restoredSql.contains("id = 1"))
    }

    @Test
    fun `无匹配日志返回空集_合法结果`() {
        val entries = SqlVisualizerRenderLogsTool.renderLogEntries("hello world 业务日志，没有任何 SQL")
        org.junit.Assert.assertEquals(0, entries.size)
    }

    @Test
    fun `空文本返回空集`() {
        org.junit.Assert.assertEquals(0, SqlVisualizerRenderLogsTool.renderLogEntries("").size)
    }

    @Test
    fun `空输入走预期错误`() {
        val error = runCatching { SqlVisualizerRenderLogsTool().renderLogs("  ") }.exceptionOrNull()
        assertTrue("空输入应抛 SqlvExpectedToolException", error is SqlvExpectedToolException)
    }

    // ---------- R60（T-LaneB D96/D97）：MCP 面脱敏（独立选项，判别断言两态） ----------

    private val sensitiveLogText = "==>  Preparing: SELECT * FROM t_user WHERE phone = ?\n" +
        "==> Parameters: 13812345678(String)"

    @Test
    fun `MCP脱敏开_还原语句打码`() {
        val out = SqlVisualizerRenderLogsTool().renderLogs(sensitiveLogText, maskLiterals = true)
        assertTrue(out, out.contains("'***'"))
        org.junit.Assert.assertFalse(out, out.contains("13812345678"))
    }

    @Test
    fun `MCP脱敏关_原文透出`() {
        val out = SqlVisualizerRenderLogsTool().renderLogs(sensitiveLogText, maskLiterals = false)
        assertTrue(out, out.contains("13812345678"))
    }

    @Test
    fun `MCP脱敏开_非敏感值保留`() {
        val text = "==>  Preparing: SELECT * FROM t_user WHERE name = ?\n" +
            "==> Parameters: tom(String)"
        val out = SqlVisualizerRenderLogsTool().renderLogs(text, maskLiterals = true)
        assertTrue(out, out.contains("tom"))
    }
}
