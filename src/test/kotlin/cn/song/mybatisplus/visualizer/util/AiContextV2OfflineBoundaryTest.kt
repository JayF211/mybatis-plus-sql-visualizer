package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Source-level side-effect boundary, complementary to the real headless clipboard tests. */
class AiContextV2OfflineBoundaryTest {
    private val root = Path.of("src/main/kotlin/cn/song/mybatisplus/visualizer")
    private fun source(path: String) = Files.readString(root.resolve(path))
    private fun between(text: String, start: String, end: String) = text.substringAfter(start).substringBefore(end)

    @Test fun realCopyAndProjectionClosureHaveNoAcquisitionCalls() {
        val execution = source("ui/SqlPreviewPanelExecution.kt")
        val copy = between(execution, "internal fun SqlPreviewPanel.copyAsAiContext(", "internal fun SqlPreviewPanel.exportPortableSql()")
        assertTrue(copy.contains("AiContextExport.buildV2("))
        assertTrue(copy.contains("matchingExplainPlans(it)"))
        assertTrue(copy.contains("UiTextClipboard.copy(result.block)"))
        assertFalse(copy.contains("AiContextExport.build("))
        val export = between(source("util/AiContextExport.kt"), "fun buildV2(", "/** 头部来源声明")
        val closure = copy + export + source("util/AiContextPlanSummary.kt")
        listOf("executeStructured(", "submitExplain", "SqlExecutor.", "McpDbExecutor.", "DatabaseSchemaResolver",
            "HttpRequests", "HttpClient", "openConnection(", "getConnection(", "DriverManager", "Socket(",
            "URL(", "ToolCall", "executeTool(", "rememberedDataSource", "getAllDataSources(",
            "invokeLater(", "executeOnPooledThread(", "lastStructuredExplain", "formatNotices(").forEach {
            assertFalse("offline export must not call $it", closure.contains(it))
        }
        // Current-target liveness lookup may read a bound object, never use the remembered roster.
        val liveness = between(execution, "= captureExplainPlanHistory(history, planKey, target) {", "internal fun SqlPreviewPanel.captureExplainPlanHistory(")
        assertTrue(liveness.contains("boundConsoleDataSource()"))
        assertFalse(liveness.contains("remembered"))
        val selector = between(source("execute/DataSourceSelector.kt"), "fun boundConsoleDataSource(): Any?", "/**")
        assertFalse(selector.contains("getConnection"))
        assertFalse(selector.contains("execute"))
    }
}
