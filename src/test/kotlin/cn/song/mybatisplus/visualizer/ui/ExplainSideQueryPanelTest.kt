package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame

/**
 * T-PreviewSideQuery P2：执行计划直送路径走只读旁路查询 + 非模态对话框，不改写预览编辑器。
 */
class ExplainSideQueryPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ExplainTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    private fun panel(sql: String): SqlPreviewPanel = SqlPreviewPanel(project).also {
        Disposer.register(testRootDisposable, it)
        it.showResult(PreviewResult(sql, sql, emptyList(), null, null, null, renderedDialect = MySqlDialect))
        it.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
        UIUtil.dispatchAllInvocationEvents()
    }

    fun testExplainSideQueryKeepsEditorAndShowsDialog() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(sql)
        val beforeDoc = p.nativeEditorDoc?.text
        val beforeConsole = p.embeddedConsole
        var captured: ExplainPlanDialog? = null
        explainTargetForTest = { ExplainTarget() }
        explainDialogSinkForTest = { captured = it }
        PreviewSideQuery.runnerForTest = { _, _, _, scalar, cb ->
            assertFalse("EXPLAIN 必须走表格旁路查询", scalar)
            cb(
                PreviewSideQuery.Result.Table(
                    McpExplainFormatter.RawResult(
                        listOf("id", "select_type"),
                        listOf(listOf("1", "SIMPLE")),
                    ),
                ),
            )
        }
        try {
            p.submitExplainDirect(
                project,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "MySQL",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )
            assertEquals("EXPLAIN 不得改写预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertEquals(sql, p.currentEditorSql())
            assertSame("EXPLAIN 不得换入控制台", beforeConsole, p.embeddedConsole)
            assertNotNull("EXPLAIN 结果必须经非模态对话框展示", captured)
            assertNotNull("对话框内容应已组装", captured!!.contentForTest())
        } finally {
            explainTargetForTest = null
            explainDialogSinkForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testExplainFailureDialogShowsLocalizedOpenConsoleButton() {
        val sql = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(sql)
        var captured: ExplainPlanDialog? = null
        explainTargetForTest = { ExplainTarget() }
        explainDialogSinkForTest = { captured = it }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb -> cb(PreviewSideQuery.Result.Failed("boom")) }
        try {
            p.submitExplainDirect(
                project,
                "EXPLAIN $sql",
                ExecutionDialectGate.Request(
                    renderMode = ExecutionDialectGate.RenderMode.AUTO,
                    renderedDialectName = "MySQL",
                    intent = ExecutionDialectGate.Intent.EXPLAIN,
                    canLosslesslyRerender = false,
                    userTextUntouched = true,
                ),
            )
            val button = buttons(captured!!.contentForTest()).single()
            assertEquals(
                "失败对话框必须显示本地化的「在控制台打开」",
                MpSqlVisualizerBundle.message("panel.explain.openConsole"),
                button.text,
            )
            assertNotEquals("未本地化的 key 不能直接显示", "panel.explain.openConsole", button.text)
            assertEquals(sql, p.currentEditorSql())
        } finally {
            explainTargetForTest = null
            explainDialogSinkForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    private fun buttons(component: java.awt.Component): List<javax.swing.JButton> =
        (if (component is javax.swing.JButton) listOf(component) else emptyList()) +
            (if (component is java.awt.Container) component.components.flatMap { buttons(it) } else emptyList())
}
