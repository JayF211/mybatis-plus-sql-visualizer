package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mpsv_render_logs 纯函数核心测试（T-McpToolset2 / T-MTS2-01）：三解析器装配与面板
 * `buildParsers` 同参同构；普通 JUnit 零 IDE fixture（解析器为纯类）。
 * 前缀/格式细节由 MyBatisLogParser/HibernateLogParser/JdbcTemplateLogParser 各自测试覆盖，
 * 此处锁工具层装配与条目元信息透出。
 */
class RenderLogsToolTest {

    @Test
    fun `MyBatis日志对还原为可执行SQL`() {
        val text = "==>  Preparing: SELECT * FROM t_user WHERE id = ?\n" +
            "==> Parameters: 1(Long)"
        val entries = RenderLogsTool.renderLogEntries(text)
        org.junit.Assert.assertEquals(1, entries.size)
        org.junit.Assert.assertEquals(cn.song.mybatisplus.visualizer.log.SqlSource.MYBATIS, entries[0].source)
        assertTrue(entries[0].restoredSql, entries[0].restoredSql.contains("id = 1"))
    }

    @Test
    fun `无匹配日志返回空集_合法结果`() {
        val entries = RenderLogsTool.renderLogEntries("hello world 业务日志，没有任何 SQL")
        org.junit.Assert.assertEquals(0, entries.size)
    }

    @Test
    fun `空文本返回空集`() {
        org.junit.Assert.assertEquals(0, RenderLogsTool.renderLogEntries("").size)
    }

    @Test
    fun `空输入走预期错误`() {
        val error = runCatching { RenderLogsTool().renderLogs("  ") }.exceptionOrNull()
        assertTrue("空输入应抛 McpExpectedError", error is com.intellij.mcpserver.McpExpectedError)
    }
}
