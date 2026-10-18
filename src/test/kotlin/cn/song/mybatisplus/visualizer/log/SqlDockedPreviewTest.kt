package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JScrollPane

class SqlDockedPreviewTest : BasePlatformTestCase() {
    private fun entry(sql: String) = LogSqlEntry(sql, sql, emptyList())

    private class Fixture(val list: JList<LogSqlEntry>, val preview: SqlDockedPreview, val host: SqlListPreviewHost)

    private fun fixture(vararg sql: String): Fixture {
        val model = DefaultListModel<LogSqlEntry>()
        sql.forEach { model.addElement(entry(it)) }
        val list = JList(model).apply {
            fixedCellHeight = 20
            setSize(320, 200)
        }
        lateinit var host: SqlListPreviewHost
        val preview = SqlDockedPreview(project, list) { host.toggle() }
        host = SqlListPreviewHost(list, JScrollPane(list), preview, "mpvs.logs.preview.split", true,
            java.util.function.Consumer<Boolean> { })
        return Fixture(list, preview, host)
    }

    private fun click(list: JList<*>, row: Int, count: Int = 1, modifiers: Int = 0): MouseEvent =
        MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), modifiers,
            5, row * 20 + 10, count, false, MouseEvent.BUTTON1)

    fun test原生分隔条无白色默认皮肤_收合再开启仍挂着列表() {
        val f = fixture("SELECT 1")
        try {
            f.list.selectedIndex = 0
            f.host.toggle()
            val split = f.host.getComponent().components.filterIsInstance<com.intellij.ui.OnePixelSplitter>().single()
            assertEquals(com.intellij.util.ui.UIUtil.getPanelBackground(), split.background)
            assertTrue("展开后列表应仍挂在原生分隔组件中",
                javax.swing.SwingUtilities.isDescendingFrom(f.list, split))
            f.host.toggle()
            f.host.toggle()
            assertSame("重新展开须复用同一分隔条，保留用户拖拽的高度比例", split,
                f.host.getComponent().components.filterIsInstance<com.intellij.ui.OnePixelSplitter>().single())
            assertTrue("重新展开不能只显示空白上半区",
                javax.swing.SwingUtilities.isDescendingFrom(f.list, split))
        } finally {
            f.host.hideHint()
            f.preview.dispose()
        }
    }

    fun test单击只选中_显式开启后随列表选中更新() {
        val f = fixture("SELECT 1", "SELECT 2", "SELECT 3")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            val patched = before.copy().also {
                it.setSqlListPreviewHintSeen(true); it.setLogFormatSql(false)
            }
            settings.loadState(patched)
            f.list.selectedIndex = 0
            f.host.onRowClick(click(f.list, 0))
            assertFalse(f.host.getPreviewOpenForTest())
            assertNull(f.preview.getEditorForTest())
            f.host.toggle()
            assertTrue(f.host.getPreviewOpenForTest())
            assertEquals("SELECT 1;", f.preview.getDisplayedSqlForTest())
            val editor = f.preview.getEditorForTest()
            f.list.selectedIndex = 1
            assertSame("浏览下一行应复用编辑器", editor, f.preview.getEditorForTest())
            assertEquals("SELECT 2;", f.preview.getDisplayedSqlForTest())
            f.host.toggle()
            assertFalse(f.host.getPreviewOpenForTest())
            f.list.selectedIndex = 2
            f.host.toggle()
            assertEquals("SELECT 3;", f.preview.getDisplayedSqlForTest())
        } finally {
            f.host.hideHint()
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test编辑后切换不覆盖草稿_恢复后跟随当前选中() {
        val f = fixture("SELECT 1", "SELECT 2")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            val patched = before.copy().also { it.setLogFormatSql(false) }
            settings.loadState(patched)
            f.list.selectedIndex = 0
            f.host.toggle()
            WriteCommandAction.runWriteCommandAction(project) {
                f.preview.getEditorForTest()!!.document.setText("SELECT changed;")
            }
            assertTrue(f.preview.isPinnedForTest)
            f.list.selectedIndex = 1
            assertEquals("SELECT changed;", f.preview.getDisplayedSqlForTest())
            f.preview.restoreAndFollowForTest()
            assertFalse(f.preview.isPinnedForTest)
            assertEquals("SELECT 2;", f.preview.getDisplayedSqlForTest())
            f.list.selectedIndices = intArrayOf(0, 1)
            assertNull("多选不应展示任意单条 SQL", f.preview.getDisplayedSqlForTest())
        } finally {
            f.preview.dispose()
            settings.loadState(before)
        }
    }

    fun test日志面板真实鼠标监听只提示不弹窗_开启后显示当前选中() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val panel = SqlLogPanel(project, manager)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            val patched = before.copy().also {
                it.setSqlListPreviewHintSeen(false); it.setLogFormatSql(false)
            }
            settings.loadState(patched)
            manager.entries.addEntry(entry("SELECT 1"))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            panel.list.fixedCellHeight = 20
            panel.list.setSize(320, 200)
            panel.list.selectedIndex = 0
            val event = click(panel.list, 0)
            panel.list.mouseListeners.forEach { it.mouseClicked(event) }
            assertFalse(panel.getPreviewHostForTest().getPreviewOpenForTest())
            assertNull(panel.getDockedPreviewForTest().getEditorForTest())
            assertTrue("真实面板 MouseAdapter 应接入一次性引导", panel.getPreviewHostForTest().getHintScheduledForTest())
            panel.getPreviewHostForTest().showPendingHintForTest()
            assertTrue(panel.getPreviewHostForTest().getHintVisibleForTest())
            panel.getPreviewHostForTest().toggle()
            assertTrue("引导入口开启后预览图标应显选中态",
                com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.getTemplatePresentation()))
            assertEquals("SELECT 1;", panel.getDockedPreviewForTest().getDisplayedSqlForTest())
            val actionEvent = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                panel.previewToggleAction, null, com.intellij.openapi.actionSystem.ActionPlaces.TOOLBAR,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            )
            panel.previewToggleAction.setSelected(actionEvent, false)
            assertFalse("左侧预览图标应可关闭底部预览", panel.getPreviewHostForTest().getPreviewOpenForTest())
            assertFalse(com.intellij.openapi.actionSystem.Toggleable.isSelected(panel.previewToggleAction.getTemplatePresentation()))
            panel.previewToggleAction.setSelected(actionEvent, true)
            assertTrue("左侧预览图标应可重新开启", panel.getPreviewHostForTest().getPreviewOpenForTest())
        } finally {
            panel.dispose()
            manager.dispose()
            settings.loadState(before)
        }
    }

    fun test首次单击就地提示只出现一次_双击取消_两页共用标志() {
        val first = fixture("SELECT 1")
        val second = fixture("SELECT 2")
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            val patched = before.copy().also { it.setSqlListPreviewHintSeen(false) }
            settings.loadState(patched)
            first.list.selectedIndex = 0
            first.host.onRowClick(click(first.list, 0))
            assertTrue(first.host.getHintScheduledForTest())
            first.host.onRowClick(click(first.list, 0, count = 2))
            assertFalse(first.host.getHintScheduledForTest())
            assertFalse(first.host.getHintVisibleForTest())
            first.host.onRowClick(click(first.list, 0))
            first.host.showPendingHintForTest()
            assertTrue(first.host.getHintVisibleForTest())
            assertTrue(settings.state.sqlListPreviewHintSeen)
            first.host.toggle()
            assertFalse(first.host.getHintVisibleForTest())
            assertTrue(first.host.getPreviewOpenForTest())
            second.list.selectedIndex = 0
            second.host.onRowClick(click(second.list, 0))
            assertFalse(second.host.getHintScheduledForTest())
            assertFalse(second.host.getHintVisibleForTest())
        } finally {
            first.host.hideHint()
            second.host.hideHint()
            first.preview.dispose()
            second.preview.dispose()
            settings.loadState(before)
        }
    }
}
