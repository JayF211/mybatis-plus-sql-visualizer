package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.history.*
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 调用生产上下文捕获函数，不替换 JDBC 回调、不声称真实数据库执行。 */
class ExplainPlanHistoryPanelTest : BasePlatformTestCase() {
    private fun preview(sql: String) = PreviewResult(sql, sql, emptyList(), null, null, null)
    private val key = ExplainPlanKey.create("project", "eb03ba8b-a414-4453-b6e0-5bf55b34fed2", "MySQL", ExplainOutputFormat.TABLE, "EXPLAIN SELECT 1")
    private val raw = RawResult(listOf("id", "table", "rows"), listOf(listOf("1", "t", "5")))

    fun test纯文本首次建条目且回调不跟随新预览() {
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            val original = preview("SELECT 1")
            panel.current = original
            val request = panel.captureExplainPlanHistory(manager, key, Any())
            assertFalse(original.historyEntryId.isBlank())
            panel.current = preview("SELECT 2")
            val result = panel.explainPlanHistorySession.complete(manager, request, raw)
            assertEquals(ExplainPlanSaveResult.SAVED, result.saved)
            assertEquals(1, manager.findById(original.historyEntryId)!!.explainPlans.size)
            assertEquals("SELECT 1", manager.entries.single().sql)
        } finally { Disposer.dispose(panel) }
    }

    fun test已删除条目不复活_未知UUID不新建() {
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            val original = preview("SELECT 1")
            panel.current = original
            panel.captureExplainPlanHistory(manager, key, Any())
            manager.clear()
            val pending = panel.captureExplainPlanHistory(manager, key, Any())
            panel.explainPlanHistorySession.complete(manager, pending, raw)
            assertTrue(manager.entries.isEmpty())
            panel.current = preview("SELECT 2")
            val unknown = panel.captureExplainPlanHistory(manager, key.copy(dataSourceUuid = ""), Any())
            assertEquals(ExplainPlanSaveResult.SESSION_ONLY, panel.explainPlanHistorySession.complete(manager, unknown, raw).saved)
            assertTrue(manager.entries.isEmpty())
        } finally { Disposer.dispose(panel) }
    }
}
