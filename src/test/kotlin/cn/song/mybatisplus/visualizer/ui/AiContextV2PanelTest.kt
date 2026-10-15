package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** R91: exercise the actual menu callback and platform clipboard, not a parallel exporter. */
class AiContextV2PanelTest : BasePlatformTestCase() {
    fun testExactHistoryAndTargetChangesUseRealCopy() {
        val panel = SqlPreviewPanel(project)
        val history = cn.song.mybatisplus.visualizer.history.SqlHistoryManager.getInstance()
        history.clear()
        try {
            val sql = "SELECT private_column FROM private_table WHERE id = 42"
            panel.current = PreviewResult(sql, sql, emptyList(), null, null, "private_table", renderedDialect = MySqlDialect)
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            val wrapped = (cn.song.mybatisplus.visualizer.sql.ExplainGate.planExplainSubmit(MySqlDialect, sql)
                as cn.song.mybatisplus.visualizer.sql.ExplainGate.SubmitPlan.Submit).statement
            val key = cn.song.mybatisplus.visualizer.history.ExplainPlanKey.create(
                cn.song.mybatisplus.visualizer.history.ExplainPlanKey.projectIdentity(project.basePath),
                "eb03ba8b-a414-4453-b6e0-5bf55b34fed2", "MySQL", cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat.TABLE, wrapped)
            var sameTarget = true
            val request = panel.captureExplainPlanHistory(history, key, Any()) { sameTarget }
            panel.explainPlanHistorySession.complete(history, request, cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                listOf("type", "rows", "Extra"), listOf(listOf("ALL", "77", "private_extra"))))
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("rows=77"))
            // In-memory acquisition also works without any persisted plan.
            history.entries.single().explainPlans.clear()
            panel.captureAiContextPlan(history, key, cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                listOf("type", "rows"), listOf(listOf("ALL", "88"))))
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("rows=88"))
            sameTarget = false
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("EXPLAIN: not obtained"))
            sameTarget = true
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql.replace("42", "43"))
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("EXPLAIN: not obtained"))
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            history.clear()
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("EXPLAIN: not obtained"))
            panel.current = panel.current!!.copy(renderedDialect = cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect)
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("EXPLAIN: not obtained"))
        } finally { history.clear(); Disposer.dispose(panel) }
    }

    fun testEditedSqlCannotBorrowStaticSchemaAndLegacyPlan() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.current = PreviewResult("SELECT old_column FROM old_table", "", emptyList(), null, null,
                "old_table", columns = listOf("old_column"), renderedDialect = MySqlDialect)
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument("SELECT new_column FROM new_table")
            panel.lastStructuredExplain = "SELECT new_column FROM new_table" to cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                listOf("rows"), listOf(listOf("999")))
            panel.copyAsAiContext()
            val copied = UiTextClipboard.readText()!!
            assertTrue(copied.contains("EXPLAIN: not obtained"))
            assertTrue(copied.contains("Static schema: not obtained"))
            assertFalse(copied.contains("999"))
        } finally { Disposer.dispose(panel) }
    }

    @Suppress("DEPRECATION")
    fun testCopyPerformsNoSocketOrJdbcAcquisitionWithLiveCanaries() {
        val panel = SqlPreviewPanel(project)
        val previous = System.getSecurityManager()
        var armed = false
        var networkCalls = 0
        var jdbcCalls = 0
        val thread = Thread.currentThread()
        val guard = object : SecurityManager() {
            override fun checkPermission(permission: java.security.Permission) { previous?.checkPermission(permission) }
            override fun checkConnect(host: String, port: Int) {
                if (armed && Thread.currentThread() === thread) {
                    networkCalls++
                    throw SecurityException("R91 network canary")
                }
                previous?.checkConnect(host, port)
            }
        }
        val driver = object : java.sql.Driver {
            override fun acceptsURL(url: String?) = url?.startsWith("jdbc:r91:") == true
            override fun connect(url: String?, properties: java.util.Properties?): java.sql.Connection? {
                if (!acceptsURL(url)) return null
                jdbcCalls++
                throw java.sql.SQLException("R91 JDBC canary")
            }
            override fun getPropertyInfo(url: String?, properties: java.util.Properties?) = emptyArray<java.sql.DriverPropertyInfo>()
            override fun getMajorVersion() = 1
            override fun getMinorVersion() = 0
            override fun jdbcCompliant() = false
            override fun getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
        }
        try {
            panel.current = PreviewResult("SELECT 1", "SELECT 1", emptyList(), null, null, null, renderedDialect = MySqlDialect)
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument("SELECT 1")
            java.sql.DriverManager.registerDriver(driver)
            System.setSecurityManager(guard)
            armed = true
            // Positive controls prove that these are live tripwires, not unavailable APIs.
            assertTrue(runCatching { java.net.Socket("127.0.0.1", 9).close() }.exceptionOrNull() is SecurityException)
            assertTrue(runCatching { java.sql.DriverManager.getConnection("jdbc:r91:probe") }.exceptionOrNull() is java.sql.SQLException)
            assertTrue(networkCalls > 0)
            assertEquals(1, jdbcCalls)
            networkCalls = 0
            jdbcCalls = 0
            panel.copyAsAiContext()
            assertTrue(UiTextClipboard.readText()!!.contains("AI context v2"))
            assertEquals(0, networkCalls)
            assertEquals(0, jdbcCalls)
        } finally {
            armed = false
            System.setSecurityManager(previous)
            java.sql.DriverManager.deregisterDriver(driver)
            Disposer.dispose(panel)
        }
    }

    fun testRealCopyUsesV2StrictProjectionAndStaticMetadata() {
        val panel = SqlPreviewPanel(project)
        try {
            val sql = "SELECT private_column FROM private_table WHERE token = 'private_value' /* private_path */"
            panel.current = PreviewResult(sql, sql, emptyList(), null, null, "private_table",
                columns = listOf("private_column", "token"), renderedDialect = MySqlDialect)
            panel.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            panel.copyAsAiContext()
            val copied = UiTextClipboard.readText()!!
            assertTrue(copied, copied.contains("AI context v2"))
            assertFalse(copied, copied.contains("private_"))
            assertTrue(copied, copied.contains("tables=1; columns=2"))
            assertTrue(copied, copied.contains("EXPLAIN: not obtained"))
        } finally { Disposer.dispose(panel) }
    }
}
