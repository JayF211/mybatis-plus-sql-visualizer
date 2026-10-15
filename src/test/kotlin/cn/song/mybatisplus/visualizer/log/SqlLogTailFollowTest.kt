package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

class SqlLogTailFollowTest : BasePlatformTestCase() {
    fun test只有滚底动作能从手动暂停恢复跟随() {
        val state = TailFollowState()
        assertEquals(TailFollowMode.FOLLOWING, state.mode)
        state.onUserScroll()
        assertEquals(TailFollowMode.PAUSED, state.mode)
        assertFalse(state.shouldFollow)
        state.onScrollToEnd()
        assertTrue(state.shouldFollow)
        state.onUserScroll()
        state.onClear()
        assertTrue(state.shouldFollow)
    }

    fun test滚轮滚动条与键盘暂停_程序性滚回底部不恢复() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val wheel = MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1L, 0, 10, 10, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
            scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
            assertEquals("一格上滚也须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            panel.resumeTailFollowForTest()
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            val bar = scroll.verticalScrollBar
            val press = MouseEvent(bar, MouseEvent.MOUSE_PRESSED, 2L, 0, 2, 2, 1, false)
            bar.mouseListeners.forEach { it.mousePressed(press) }
            assertEquals("拖动或点击滚动条须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            bar.value = bar.maximum
            assertEquals("即使回到底部也不得自动恢复", TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            panel.resumeTailFollowForTest()
            val key = KeyEvent(panel.list, KeyEvent.KEY_PRESSED, 3L, 0, KeyEvent.VK_PAGE_UP, KeyEvent.CHAR_UNDEFINED)
            panel.list.keyListeners.forEach { it.keyPressed(key) }
            assertEquals("键盘导航也须暂停", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test暂停后新SQL不抢走实际滚动视口() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.setSize(460, 420)
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            for (index in 1..80) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val bar = scroll.verticalScrollBar
            assertTrue("夹具必须有真实可滚动内容", bar.maximum > bar.visibleAmount + 100)
            panel.resumeTailFollowForTest()
            bar.value = 80
            val wheel = MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 4L, 0, 5, 5, 0,
                false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)
            scroll.mouseWheelListeners.forEach { it.mouseWheelMoved(wheel) }
            val anchored = bar.value
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            manager.entries.addEntry(LogSqlEntry("SELECT 81", "SELECT 81", emptyList(), timestamp = 81L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("新增条目后用户视口不能被拉回底部", anchored, bar.value)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test滚底动作对象能从暂停态恢复并真正贴底() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            panel.setSize(460, 420)
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            for (index in 1..80) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            layout(panel)
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            val bar = scroll.verticalScrollBar
            assertTrue("夹具必须有真实可滚动内容", bar.maximum > bar.visibleAmount + 100)
            bar.value = 0
            scroll.mouseWheelListeners.forEach {
                it.mouseWheelMoved(MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 9L, 0, 5, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
            }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)

            val action = panel.scrollToEndAction
            action.actionPerformed(
                AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT),
            )
            assertEquals("滚底动作应把状态恢复为跟随", TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
            assertTrue(
                "滚底动作应真正把视口贴到列表底（value=${bar.value} max=${bar.maximum} extent=${bar.visibleAmount}）",
                bar.value + bar.visibleAmount >= bar.maximum - 1,
            )
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test程序性列表刷新不改变用户暂停状态() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val scroll = requireNotNull(panel.listScrollPaneForTest)
            scroll.mouseWheelListeners.forEach {
                it.mouseWheelMoved(MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL, 1L, 0, 5, 5, 0,
                    false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1))
            }
            assertEquals(TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            for (index in 1..32) {
                manager.entries.addEntry(LogSqlEntry("SELECT $index", "SELECT $index", emptyList(), timestamp = index.toLong()))
            }
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertEquals("追加和刷新不得改变暂停意图", TailFollowMode.PAUSED, panel.tailFollowModeForTest)
            panel.resumeTailFollowForTest()
            assertEquals(TailFollowMode.FOLLOWING, panel.tailFollowModeForTest)
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }
}
