package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.StatementType
import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MCP 执行链落历史测试（T-McpAuditTrail T-MAT-01/02；计划 §验收 用例 ①②④⑥ 的无 DB 半边）。
 *
 * **覆盖边界**：`mpsv_explain` 的真实执行（打真数据源、驱动返回计划文本）本机不可自动化——
 * 需 Database 插件 + 已保存密码的数据源。此处覆盖执行**之后**的落库链：条目构造契约
 * （[McpToolsetSupportKt.mcpHistoryEntry]）+ 入既有入库路径（[McpToolsetSupportKt.recordMcpExecution]
 * → [SqlLogManager] buffer）。
 * 执行链本身（onExecuted 回调的成功/失败两条路径）留真机走查 §五-1。
 *
 * 钉住的三条口径：
 *  - T-MAT-02：条目文本恒为**原始 selectSql**，绝不落 `EXPLAIN <select>` 包装句；
 *  - T-MAT-02 染色面：条目的 statementType 恒为 SELECT 而非 OTHER（落包装句则无类型染色）；
 *  - T-MAT-01：落库走既有 [SqlLogManager] 入库路径（面板可见 + 捕获序号分配）。
 *
 * k2j 适配：顶层函数门面 `McpToolsetSupportKt` 须显式限定类名（同包不自动可见）；Java 方法无
 * 具名实参与默认参数，`mcpHistoryEntry` / `recordMcpExecution` 一律位置实参。
 */
class McpHistoryIngestTest : BasePlatformTestCase() {

    /** T-TestIsolation：本类经 [McpToolsetSupportKt.recordMcpExecution] 往**项目服务级**共享 buffer 入队条目
     *  （light 项目片内跨类复用同一 SqlLogManager 实例）——存初值供 tearDown 清回。 */
    private lateinit var serviceState: SqlLogManagerProjectState.Snapshot

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
        )
    }

    override fun tearDown() {
        SqlLogManagerProjectState.restore(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
            serviceState,
        )
        super.tearDown()
    }

    fun testMCP条目_落原始SQL不落EXPLAIN包装句() {
        val entry = McpToolsetSupportKt.mcpHistoryEntry(
            "  SELECT id FROM user WHERE id = 1  ",
            "demo-mysql",
            12L,
            null,
            1_700_000_000_000L,
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
        val entry = McpToolsetSupportKt.mcpHistoryEntry(
            "SELECT * FROM no_such_table",
            "demo-mysql",
            3L,
            "SQLSyntaxErrorException: Table 'no_such_table' doesn't exist",
        )
        assertEquals("SQLSyntaxErrorException: Table 'no_such_table' doesn't exist", entry.errorText)
        assertEquals(StatementType.SELECT, entry.statementType)
    }

    fun test记录MCP执行_进既有历史入库路径() {
        val manager =
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "test.进既有历史入库路径")
        val before = manager.entries.size()
        McpToolsetSupportKt.recordMcpExecution(project, "SELECT id FROM user", "demo-mysql", 8L, null)

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
        val manager =
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "test.去重窗口内折叠")
        // 前提自证（合流收口实测）：去重开关是项目服务级状态，light 测试项目在同一 JVM 内跨类复用，
        // 前置测试类（如 SqlConsoleBridgeFilterProviderTest 的 setUp）关掉它会让本用例假红——
        // 本用例断言的就是「去重开启时」的语义，前提在此显式声明，不依赖环境残留。
        manager.entries.dedupeEnabled = true
        McpToolsetSupportKt.recordMcpExecution(project, "SELECT 1 FROM dual", "demo", 1L, null)
        val afterFirst = manager.entries.size()
        McpToolsetSupportKt.recordMcpExecution(project, "SELECT 1 FROM dual", "demo", 1L, null)
        assertEquals("去重窗口内同 SQL 不重复入列", afterFirst, manager.entries.size())
    }
}
