package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * T-PreviewSideQuery P1：预估影响行数走只读旁路查询，不改写预览编辑器/不换入控制台。
 */
class DmlImpactSideQueryPanelTest : BasePlatformTestCase() {

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class ImpactTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    private fun panel(sql: String): SqlPreviewPanel = SqlPreviewPanel(project).also {
        Disposer.register(testRootDisposable, it)
        it.showResult(
            PreviewResult(sql, sql, emptyList(), null, null, "t_order", renderedDialect = MySqlDialect),
        )
        it.nativeEditorDoc = EditorFactory.getInstance().createDocument(sql)
        UIUtil.dispatchAllInvocationEvents()
    }

    fun testEstimateUsesSideQueryAndKeepsEditor() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        val beforeDoc = p.nativeEditorDoc?.text
        val beforeConsole = p.embeddedConsole
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, scalar, cb ->
            assertTrue("预估影响行数必须走单值旁路查询", scalar)
            cb(
                PreviewSideQuery.Result.Scalar(
                    7,
                    McpExplainFormatter.RawResult(listOf("COUNT(*)"), listOf(listOf("7"))),
                ),
            )
        }
        try {
            p.estimateDmlImpact()
            assertEquals("只读试算不得改写预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertEquals(sql, p.currentEditorSql())
            assertSame("只读试算不得换入控制台", beforeConsole, p.embeddedConsole)
            val label = p.impactResultLabel!!
            assertTrue("内联结果应显示行数：${label.text}", label.text.contains("7"))
            assertEquals("内联结果应为灰色说明", UIUtil.getContextHelpForeground(), label.foreground)
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }

    fun testEstimateFailureShowsInlineErrorWithoutTouchingEditor() {
        val sql = "UPDATE t_order SET status = 1 WHERE id = 1111"
        val p = panel(sql)
        dmlImpactTargetForTest = { ImpactTarget() }
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb -> cb(PreviewSideQuery.Result.Failed("boom")) }
        try {
            p.estimateDmlImpact()
            assertEquals(sql, p.currentEditorSql())
            val label = p.impactResultLabel!!
            assertTrue("失败原因应内联展示：${label.text}", label.text.contains("boom"))
            assertEquals(com.intellij.ui.JBColor.RED, label.foreground)
        } finally {
            dmlImpactTargetForTest = null
            PreviewSideQuery.runnerForTest = null
            p.clear()
        }
    }
}
