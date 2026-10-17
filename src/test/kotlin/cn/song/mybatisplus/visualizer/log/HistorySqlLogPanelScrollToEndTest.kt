package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.io.File
import javax.swing.JViewport
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/** 直接调用实际导航工具栏动作，既验证批量排空，也验证真实列表视口到达末项。 */
class HistorySqlLogPanelScrollToEndTest : BasePlatformTestCase() {
    private lateinit var panel: HistorySqlLogPanel
    private lateinit var files: File

    override fun setUp() {
        super.setUp()
        files = File(project.basePath!!, "history-bottom-test").apply { mkdirs() }
        panel = HistorySqlLogPanel(project)
        Disposer.register(testRootDisposable, panel)
    }

    private fun history(name: String, count: Int): HistoryFileEntry {
        val file = File(files, "$name.sql")
        file.writeText((1..count).joinToString("\n\n", postfix = "\n\n") { id ->
            "-- source: History.java:$id | time: 1ms | 2026-10-16 09:00:00.000 --\n" +
                "SELECT * FROM $name WHERE id = $id AND bucket = ${id % 10}"
        })
        return HistoryFileEntry(file, count)
    }

    private fun event(): AnActionEvent = AnActionEvent.createFromAnAction(
        panel.scrollToEndAction, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
    )

    private fun clickBottom() {
        // 从实际 ActionToolbar 的组取回动作，不绕过 actionPerformed 直测辅助函数。
        val action = panel.navigationActionsForTest.single { it === panel.scrollToEndAction }
        val event = event()
        action.update(event)
        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event)
    }

    private fun viewport(): JViewport = panel.sqlListForTest.parent as JViewport

    private fun layoutList() {
        val list = panel.sqlListForTest
        list.fixedCellHeight = 20
        list.fixedCellWidth = 300
        viewport().extentSize = Dimension(300, 100)
        list.setSize(300, list.model.size * 20)
        viewport().viewPosition = java.awt.Point(0, 0)
    }

    private fun assertAtLastRow() {
        val list = panel.sqlListForTest
        val bounds = list.getCellBounds(list.model.size - 1, list.model.size - 1)
        assertTrue("末行必须实际进入视口，而不只是模型加载完毕", viewport().viewRect.contains(bounds))
    }

    fun testActionIsDirectAndReusesLogIconTextAndEdtUpdate() {
        assertFalse(panel.navigationActionsForTest.any { it is DefaultActionGroup })
        assertEquals(1, panel.navigationActionsForTest.count { it === panel.scrollToEndAction })
        val event = event()
        panel.scrollToEndAction.update(event)
        assertSame(AllIcons.General.ArrowDown, event.presentation.icon)
        assertEquals(MpSqlVisualizerBundle.message("log.panel.scrollBottom"), event.presentation.text)
        assertEquals(MpSqlVisualizerBundle.message("log.panel.scrollBottom"), event.presentation.description)
        assertEquals(ActionUpdateThread.EDT, panel.scrollToEndAction.actionUpdateThread)
        assertFalse("文件列表隐藏到底动作", event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
        panel.scrollToEndAction.actionPerformed(event)
        assertTrue(panel.sqlListEntries.isEmpty())
    }

    fun testMoreThanFirstBatchAndTenThousandRowsReachTrueEndInOneModelEvent() {
        for (count in listOf(31, 10000)) {
            panel.openFileForTest(history("rows$count", count))
            assertEquals(30, panel.sqlListEntries.size)
            layoutList()
            val additions = mutableListOf<Pair<Int, Int>>()
            val model = panel.sqlListForTest.model
            val listener = object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent) { additions.add(e.index0 to e.index1) }
                override fun intervalRemoved(e: ListDataEvent) = Unit
                override fun contentsChanged(e: ListDataEvent) = Unit
            }
            model.addListDataListener(listener)
            try {
                clickBottom()
                assertEquals(count, model.size)
                assertEquals(listOf(30 to count - 1), additions)
                assertEquals((1..count).toList(), panel.sqlListEntries.map { it.sourceLine })
                assertAtLastRow()
                clickBottom()
                panel.appendPendingForTest()
                assertEquals("重复点击不重复追加", 1, additions.size)
            } finally {
                model.removeListDataListener(listener)
            }
        }
    }

    fun testFilteredThousandRowsEndAtOriginalIdTenThousandWithoutChangingSearch() {
        panel.openFileForTest(history("filtered", 10000))
        panel.setSearchForTest("bucket = 0")
        assertEquals(30, panel.sqlListEntries.size)
        layoutList()
        clickBottom()
        assertEquals("bucket = 0", panel.searchTextForTest())
        assertEquals(1000, panel.sqlListEntries.size)
        assertEquals((10..10000 step 10).toList(), panel.sqlListEntries.map { it.sourceLine })
        assertEquals("SELECT * FROM filtered WHERE id = 10000 AND bucket = 0", panel.sqlListEntries.last().restoredSql)
        assertAtLastRow()
        panel.setSearchForTest("")
        assertEquals("清过滤仍按原有首屏策略", 30, panel.sqlListEntries.size)
        clickBottom()
        assertEquals(10000, panel.sqlListEntries.size)
    }

    fun testEmptyFileAndEmptyFilterDisableAction() {
        panel.openFileForTest(history("empty", 0))
        val event = event()
        panel.scrollToEndAction.update(event)
        assertTrue(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
        panel.scrollToEndAction.actionPerformed(event)
        panel.openFileForTest(history("nonempty", 100))
        panel.setSearchForTest("not-present")
        panel.scrollToEndAction.update(event)
        assertFalse(event.presentation.isEnabled)
        panel.scrollToEndAction.actionPerformed(event)
        panel.appendPendingForTest()
        assertTrue(panel.sqlListEntries.isEmpty())
    }

    fun testFileSwitchAndRefilterDoNotReceiveLateRowsOrLateScroll() {
        val oldFile = history("old_rows", 1000)
        val newFile = history("new_rows", 100)
        panel.openFileForTest(oldFile)
        layoutList()
        clickBottom()
        panel.openFileForTest(newFile)
        layoutList()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("新文件仍只首屏，不被旧到底请求排空", 30, panel.sqlListEntries.size)
        assertEquals(0, viewport().viewPosition.y)
        clickBottom()
        assertEquals(100, panel.sqlListEntries.size)
        assertTrue(panel.sqlListEntries.all { it.restoredSql.contains("new_rows") })
        panel.setSearchForTest("bucket = 0")
        layoutList()
        UIUtil.dispatchAllInvocationEvents()
        panel.appendPendingForTest()
        assertEquals((10..100 step 10).toList(), panel.sqlListEntries.map { it.sourceLine })
        assertEquals("新过滤不得被上一动作迟到滚动", 0, viewport().viewPosition.y)
        clickBottom()
        assertAtLastRow()
        panel.backToFilesForTest()
        panel.scrollToEndAction.actionPerformed(event())
        panel.openFileForTest(oldFile)
        layoutList()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(30, panel.sqlListEntries.size)
        assertEquals(0, viewport().viewPosition.y)
    }
}
