package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.StatementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 执行链落历史测试（T-McpAuditTrail T-MAT-01/02；计划 §验收 用例 ①②④⑥ 的无 DB 半边）。
 *
 * **覆盖边界**：`mpsv_explain` 的真实执行（打真数据源、驱动返回计划文本）本机不可自动化——
 * 需 Database 插件 + 已保存密码的数据源。此处覆盖执行**之后**的落库链：条目构造契约
 * （[mcpHistoryEntry]）+ 入既有入库路径（[recordMcpExecution] → [SqlLogManager] buffer）。
 * 执行链本身（onExecuted 回调的成功/失败两条路径）留真机走查 §五-1。
 *
 * 钉住的三条口径：
 *  - T-MAT-02：条目文本恒为**原始 selectSql**，绝不落 `EXPLAIN <select>` 包装句；
 *  - T-MAT-02 染色面：条目的 statementType 恒为 SELECT 而非 OTHER（落包装句则无类型染色）；
 *  - T-MAT-01：落库走既有 [SqlLogManager] 入库路径（面板可见 + 捕获序号分配）。
 */
class McpHistoryIngestTest : BasePlatformTestCase() {

    fun testMCP条目_落原始SQL不落EXPLAIN包装句() {
        val entry = mcpHistoryEntry(
            selectSql = "  SELECT id FROM user WHERE id = 1  ",
            dataSourceName = "demo-mysql",
            elapsedMs = 12L,
            errorText = null,
            timestamp = 1_700_000_000_000L,
        )
        assertEquals(SqlSource.MCP, entry.source)
        assertEquals("原始 selectSql（首尾空白 trim）", "SELECT id FROM user WHERE id = 1", entry.restoredSql)
        assertEquals("SELECT id FROM user WHERE id = 1", entry.rawSql)
        assertFalse("不得落 EXPLAIN 包装句: ${entry.restoredSql}", entry.restoredSql.contains("EXPLAIN"))
        assertEquals(12L, entry.executionTimeMs)
        assertEquals("demo-mysql", entry.databaseName)
        assertTrue("MCP 条目无参数（参数已在 agent 侧落地）", entry.params.isEmpty())
        assertEquals("用例④：MCP 条目必须可染色（OTHER 在 SqlListTypeColor 返回 null）", StatementType.SELECT, entry.statementType)
        assertEquals(1_700_000_000_000L, entry.timestamp)
    }

    fun testMCP条目_失败路径落errorText() {
        val entry = mcpHistoryEntry(
            selectSql = "SELECT * FROM no_such_table",
            dataSourceName = "demo-mysql",
            elapsedMs = 3L,
            errorText = "SQLSyntaxErrorException: Table 'no_such_table' doesn't exist",
        )
        assertEquals("SQLSyntaxErrorException: Table 'no_such_table' doesn't exist", entry.errorText)
        assertEquals(StatementType.SELECT, entry.statementType)
    }

    fun test记录MCP执行_进既有历史入库路径() {
        val manager = project.getService(SqlLogManager::class.java)
        val before = manager.entries.size()
        recordMcpExecution(project, "SELECT id FROM user", "demo-mysql", 8L, null)

        val entries = manager.entries.snapshot()
        assertEquals("落库一条", before + 1, entries.size)
        val last = entries.last()
        assertEquals(SqlSource.MCP, last.source)
        assertEquals("SELECT id FROM user", last.restoredSql)
        assertEquals(8L, last.executionTimeMs)
        assertEquals("demo-mysql", last.databaseName)
        assertTrue("入队分配捕获序号（列表行号 #N）", last.sequenceNumber > 0L)
    }

    fun test记录MCP执行_同一条SQL连续两次_去重窗口内折叠() {
        // 既有 buffer 语义（本任务不另开口子）：去重开启时窗口内同 SQL 折叠为一条；
        // 每次调用仍有 idea.log 的 [MCP-AUDIT] 行兜底留痕（见 recordMcpExecution KDoc）
        val manager = project.getService(SqlLogManager::class.java)
        recordMcpExecution(project, "SELECT 1 FROM dual", "demo", 1L, null)
        val afterFirst = manager.entries.size()
        recordMcpExecution(project, "SELECT 1 FROM dual", "demo", 1L, null)
        assertEquals("去重窗口内同 SQL 不重复入列", afterFirst, manager.entries.size())
    }
}
