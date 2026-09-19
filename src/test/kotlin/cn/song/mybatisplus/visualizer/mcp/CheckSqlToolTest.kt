package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sqlv_check_sql 工具测试（T-McpToolset2 / T-MTS2-01）：有界/无界样例、发现清单英文文案、
 * 空输入拒绝。判定细节由 UnboundedWriteDetectorTest 覆盖，此处锁工具层输出契约。
 */
class CheckSqlToolTest {

    private fun check(sql: String) = SqlVisualizerCheckSqlTool().checkSql(sql)

    @Test
    fun `有界DELETE_无发现`() {
        val output = check("DELETE FROM t WHERE id = 1")
        assertTrue(output, output.contains("statements: 1"))
        assertTrue(output, output.contains("dangerous: false"))
        assertTrue(output, output.contains("findings: none"))
    }

    @Test
    fun `无WHERE的DELETE_危险并列出发现`() {
        val output = check("DELETE FROM t")
        assertTrue(output, output.contains("dangerous: true"))
        assertTrue(output, output.contains("MISSING_WHERE"))
    }

    @Test
    fun `多发现样例_OR恒真与LIKE纯通配`() {
        val output = check("DELETE FROM t WHERE name LIKE '%' OR 1 = 1")
        assertTrue(output, output.contains("LIKE_WILDCARD"))
        assertTrue(output, output.contains("OR_TAUTOLOGY"))
    }

    @Test
    fun `多语句逐条分类`() {
        val output = check("SELECT 1; DELETE FROM t")
        assertTrue(output, output.contains("statements: 2"))
        assertTrue(output, output.contains("QUERY"))
        assertTrue(output, output.contains("DML"))
    }

    @Test
    fun `空输入_预期错误`() {
        val error = runCatching { SqlVisualizerCheckSqlTool().checkSql("") }.exceptionOrNull()
        assertTrue(error is com.intellij.mcpserver.McpExpectedError)
    }

    @Test
    fun `DELIMITER脚本_界符残留不当语句`() {
        // T-DA-03 消费面③（O-39）：改前 `DELIMITER $$` 与 `SELECT 1$$` 各算一条（$$ 残留），
        // 现过程体整段一条 + 复位后普通语句一条
        val output = check(
            "DELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND$$\nDELIMITER ;\nSELECT 2;",
        )
        assertTrue(output, output.contains("statements: 2"))
        assertFalse("`$$` 残留不进核查输出", output.contains("$$"))
        assertTrue(output, output.contains("DDL"))
    }
}
