package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.execute.ExplainPanelChannel
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-PreviewSideQuery P2：结构化 EXPLAIN 也走非模态对话框，不替换预览编辑器。
 */
class ExplainStructuredSideQueryPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ExplainTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    fun testStructuredExplainShowsDialogWithoutTouchingEditor() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, p)
        val settings = PluginSettings.getInstance().state
        val previousStructured = settings.explainStructuredRender
        val history = SqlHistoryManager.getInstance()
        history.clear()
        var captured: ExplainPlanDialog? = null
        try {
            p.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = MySqlDialect))
            p.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
            UIUtil.dispatchAllInvocationEvents()
            val beforeDoc = p.nativeEditorDoc?.text
            val beforeConsole = p.embeddedConsole

            settings.explainStructuredRender = true
            explainBoundDsForTest = { ExplainTarget() }
            explainDatabaseAvailableForTest = { true }
            explainDialogSinkForTest = { captured = it }
            ExplainPanelChannel.structuredRunnerForTest = { _, _, _, _, onPlan, _ ->
                onPlan(
                    ExplainPanelChannel.StructuredOutcome(
                        McpExplainFormatter.RawResult(
                            listOf("id", "select_type"),
                            listOf(listOf("1", "SIMPLE")),
                        ),
                        5,
                        "demo",
                    ),
                )
            }

            p.submitExplainOrStructured(
                project,
                MySqlDialect,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "MySQL",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )

            assertEquals("结构化 EXPLAIN 不得改写预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertEquals(sql, p.currentEditorSql())
            assertSame("结构化 EXPLAIN 不得换入控制台", beforeConsole, p.embeddedConsole)
            assertNotNull("结构化结果必须经非模态对话框展示", captured)
            assertTrue("对话框内容应为结构化计划视图", captured!!.contentForTest() is ExplainPlanResultView)
        } finally {
            explainBoundDsForTest = null
            explainDatabaseAvailableForTest = null
            explainDialogSinkForTest = null
            ExplainPanelChannel.structuredRunnerForTest = null
            settings.explainStructuredRender = previousStructured
            history.clear()
            p.clear()
        }
    }
}
