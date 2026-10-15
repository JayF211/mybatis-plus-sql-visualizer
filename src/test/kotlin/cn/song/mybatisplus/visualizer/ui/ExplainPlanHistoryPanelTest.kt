package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.history.*
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 直接调用生产提交捕获及成功收口函数；不替换 JDBC 回调，不声称真实网络执行。 */
class ExplainPlanHistoryPanelTest : BasePlatformTestCase() {
    private fun preview(sql: String) = PreviewResult(sql, sql, emptyList(), null, null, null)
    private val key = ExplainPlanKey.create("project", "eb03ba8b-a414-4453-b6e0-5bf55b34fed2", "MySQL", ExplainOutputFormat.TABLE, "EXPLAIN SELECT 1")
    private val raw = RawResult(listOf("id", "table", "rows"), listOf(listOf("1", "t", "5")))
    private lateinit var before: PluginSettings.State

    override fun setUp() {
        super.setUp()
        before = PluginSettings.getInstance().state.copy()
        PluginSettings.getInstance().loadState(PluginSettings.State(explainStructuredRender = true))
    }
    override fun tearDown() {
        try { PluginSettings.getInstance().loadState(before) } finally { super.tearDown() }
    }
    private fun enable() { PluginSettings.getInstance().state.explainPlanRetentionEnabled = true }

    fun test默认不存且旧渲染开启仍仅会话比较() {
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            panel.current = preview("SELECT 1")
            val target = Any()
            val first = panel.captureExplainPlanHistory(manager, key, target)
            assertEquals(ExplainPlanSaveResult.RETENTION_DISABLED, panel.completeExplainPlanHistory(manager, first, raw).saved)
            val second = panel.captureExplainPlanHistory(manager, key, target)
            assertEquals(listOf(raw), panel.completeExplainPlanHistory(manager, second, raw).baselines.map { it.raw })
            assertTrue(manager.entries.isEmpty())
            assertTrue(panel.current!!.historyEntryId.isEmpty())
        } finally { Disposer.dispose(panel) }
    }

    fun test显式启用可存且回调不跟随新预览() {
        enable()
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            val original = preview("SELECT 1")
            panel.current = original
            val request = panel.captureExplainPlanHistory(manager, key, Any())
            assertFalse(original.historyEntryId.isBlank())
            panel.current = preview("SELECT 2")
            val result = panel.completeExplainPlanHistory(manager, request, raw)
            assertEquals(ExplainPlanSaveResult.SAVED, result.saved)
            assertEquals(1, manager.findById(original.historyEntryId)!!.explainPlans.size)
            assertEquals("SELECT 1", manager.entries.single().sql)
        } finally { Disposer.dispose(panel) }
    }

    fun test中途关闭重新读取设置拒绝保存() {
        enable()
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            panel.current = preview("SELECT 1")
            val request = panel.captureExplainPlanHistory(manager, key, Any())
            // 替换整个 State；故不能只捕获旧 State 引用再读属性。
            PluginSettings.getInstance().loadState(PluginSettings.State(explainStructuredRender = true))
            assertEquals(ExplainPlanSaveResult.RETENTION_DISABLED, panel.completeExplainPlanHistory(manager, request, raw).saved)
            assertTrue(manager.entries.all { it.explainPlans.isEmpty() })
        } finally { Disposer.dispose(panel) }
    }

    fun test提交时关闭即使回调前开启也不追认() {
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            panel.current = preview("SELECT 1")
            val request = panel.captureExplainPlanHistory(manager, key, Any())
            enable()
            assertEquals(ExplainPlanSaveResult.RETENTION_DISABLED, panel.completeExplainPlanHistory(manager, request, raw).saved)
            assertTrue(manager.entries.isEmpty())
        } finally { Disposer.dispose(panel) }
    }

    fun test清空拒绝迟到回写且删除不复活() {
        enable()
        val panel = SqlPreviewPanel(project)
        try {
            val manager = SqlHistoryManager()
            panel.current = preview("SELECT 1")
            val pending = panel.captureExplainPlanHistory(manager, key, Any())
            manager.clear()
            assertEquals(ExplainPlanSaveResult.CLEARED_OR_REMOVED, panel.completeExplainPlanHistory(manager, pending, raw).saved)
            val after = panel.captureExplainPlanHistory(manager, key, Any())
            panel.completeExplainPlanHistory(manager, after, raw)
            assertTrue(manager.entries.isEmpty())
            panel.current = preview("SELECT 2")
            val unknown = panel.captureExplainPlanHistory(manager, key.copy(dataSourceUuid = ""), Any())
            assertEquals(ExplainPlanSaveResult.SESSION_ONLY, panel.completeExplainPlanHistory(manager, unknown, raw).saved)
            assertTrue(manager.entries.isEmpty())
        } finally { Disposer.dispose(panel) }
    }
}
