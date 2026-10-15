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
