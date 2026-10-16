package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.xml.PagesColumn
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable

/** H03 生产接线：只操作组件，不启动窗口、原生图或数据库请求。 */
class SqlPreviewPanelTabLifecycleTest : BasePlatformTestCase() {
    private fun columns(id: String) = PreviewResult(
        sql = "SELECT $id", compactSql = "SELECT $id", unresolvedParams = emptyList(),
        paramTable = null, wrapperType = null, tableName = null,
        pagesColumns = listOf(PagesColumn(id, id, "text", true, false)),
    )

    fun testColumnsThenJoinCanBeAddedAndRemovedIndependently() {
        val panel = SqlPreviewPanel(null)
        try {
            val sql = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
            panel.updateColumnInfoTab(columns("first"))
            val columnPage = panel.contentTabPane!!.getComponentAt(1)
            panel.contentTabPane!!.selectedComponent = columnPage
            panel.ensureTabbedContent()
            assertEquals(3, panel.contentTabPane!!.tabCount)
            assertSame(columnPage, panel.contentTabPane!!.selectedComponent)
            assertTrue(panel.contentTabPane!!.indexOfComponent(panel.diagramLoadingHost) >= 0)
            panel.removeDiagramTab()
            assertEquals(2, panel.contentTabPane!!.tabCount)
            assertSame(columnPage, panel.contentTabPane!!.selectedComponent)
            panel.removeColumnInfoTab()
            assertNull(panel.contentTabPane)
            assertSame(sql, (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testColumnRefreshReplacesRowsWithoutLosingOtherPagesOrSelection() {
        val panel = SqlPreviewPanel(null)
        try {
            panel.ensureTabbedContent()
            panel.updateColumnInfoTab(columns("first"))
            panel.contentTabPane!!.selectedComponent = panel.previewTabs.component("columns")
            panel.updateColumnInfoTab(columns("second"))
            val columnPage = panel.previewTabs.component("columns") as JScrollPane
            assertEquals("second", (columnPage.viewport.view as JTable).getValueAt(0, 0))
            assertSame(columnPage, panel.contentTabPane!!.selectedComponent)
            assertEquals(3, panel.contentTabPane!!.tabCount)
            panel.removeColumnInfoTab()
            assertEquals(2, panel.contentTabPane!!.tabCount)
            assertTrue(panel.contentTabPane!!.indexOfComponent(panel.diagramLoadingHost) >= 0)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testJoinRemovalReleasesNativeResourceOnceAndKeepsAnalysisPage() {
        val panel = SqlPreviewPanel(null)
        var releases = 0
        try {
            panel.ensureTabbedContent()
            val native = JPanel().apply {
                // NativeJoinDiagramSupport 的真实 clientProperty 协议，不实例化可选图框架。
                putClientProperty("mpsql.native.join.editor", com.intellij.openapi.Disposable { releases++ })
            }
            panel.currentNativeDiagram = native
            panel.diagramTabHost.add(native)
            val analysis = JPanel()
            panel.previewTabs.put("analysis", "analysis", analysis)
            panel.contentTabPane!!.selectedComponent = analysis
            panel.removeDiagramTab()
            assertEquals(1, releases)
            assertNull(panel.currentNativeDiagram)
            assertSame(analysis, panel.contentTabPane!!.selectedComponent)
            panel.removeDiagramTab()
            assertEquals(1, releases)
        } finally {
            Disposer.dispose(panel)
        }
        assertEquals(1, releases)
    }

    fun testProductionRefreshGateRejectsSupersededRemovedAndDisposedCallbacks() {
        val panel = SqlPreviewPanel(null)
        var writes = 0
        try {
            val first = columns("first")
            panel.current = first
            panel.ensureTabbedContent()
            val old = panel.previewTabs.beginRefresh("join")!!
            val latest = panel.previewTabs.beginRefresh("join")!!
            assertFalse(panel.applyDiagramRefresh(first, old) { writes++ })
            assertTrue(panel.applyDiagramRefresh(first, latest) { writes++ })
            panel.removeDiagramTab()
            panel.ensureTabbedContent()
            assertFalse(panel.applyDiagramRefresh(first, latest) { writes++ })
            val reopened = panel.previewTabs.beginRefresh("join")!!
            panel.current = columns("second")
            assertFalse(panel.applyDiagramRefresh(first, reopened) { writes++ })
            val beforeDispose = panel.previewTabs.beginRefresh("join")!!
            val second = panel.current!!
            Disposer.dispose(panel)
            assertFalse(panel.applyDiagramRefresh(second, beforeDispose) { writes++ })
            assertEquals(1, writes)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testClearAndDisposeReleaseExampleAnalysisAndInvalidatePendingJoin() {
        val panel = SqlPreviewPanel(null)
        var released = 0
        var writes = 0
        try {
            panel.current = columns("first")
            panel.ensureTabbedContent()
            panel.updateColumnInfoTab(panel.current!!)
            panel.previewTabs.put("analysis", "analysis", JPanel(), onRemove = { released++ })
            val old = panel.previewTabs.beginRefresh("join")!!
            panel.removeDiagramTab()
            panel.ensureTabbedContent()
            assertFalse(panel.previewTabs.applyIfCurrent(old) { writes++ })
            val beforeClear = panel.previewTabs.beginRefresh("join")!!
            panel.clear()
            assertEquals(1, released)
            assertNull(panel.contentTabPane)
            assertFalse(panel.previewTabs.applyIfCurrent(beforeClear) { writes++ })
            panel.ensureTabbedContent()
            panel.previewTabs.put("analysis", "analysis", JPanel(), onRemove = { released++ })
            val beforeDispose = panel.previewTabs.beginRefresh("join")!!
            Disposer.dispose(panel)
            assertFalse(panel.previewTabs.applyIfCurrent(beforeDispose) { writes++ })
            panel.ensureTabbedContent()
            panel.updateColumnInfoTab(columns("late"))
            assertNull(panel.contentTabPane)
            assertEquals(2, released)
            assertEquals(0, writes)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
