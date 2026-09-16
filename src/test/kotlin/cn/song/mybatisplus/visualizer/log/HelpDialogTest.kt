package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 帮助入口测试（T-M39）。
 *
 * 帮助按钮已改为 JBCefBrowser.loadHTML() 在弹窗中打开本地 HTML；
 * 测试聚焦：(1) 工具栏组含帮助入口；(2) 弹窗可构造；(3) 保留 bundle key 存在。
 */
class HelpDialogTest : BasePlatformTestCase() {

    fun testToolbarGroupContainsHelpAction() {
        // 直接构造（对齐 SqlLogManagerBehaviorTest——测试环境 project.getService 不可用）
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            val children = panel.toolbarGroup.getChildren(null).toList()
            // T-UiNative T-UN-11：捕捉开关迁工具窗标题栏，工具栏组余 4 项（清空/设置/搜索/帮助）
            assertEquals("工具栏组应有 4 项（清空/设置/搜索/帮助）", 4, children.size)
            // update 由工具栏生命周期驱动（headless 不布局不触发），手动走一次——
            // 图标/文案写入 event.presentation（2024.3 AnAction 无 getPresentation()）
            val events = children.map {
                AnActionEvent.createFromAnAction(it, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            }
            children.forEachIndexed { i, action -> action.update(events[i]) }
            val helpIndex = events.indexOfFirst { it.presentation.icon === AllIcons.Actions.Help }
            assertTrue("工具栏组应含帮助入口（? 图标，设置按钮旁）", helpIndex >= 0)
            assertEquals(
                MpSqlVisualizerBundle.message("log.panel.help"),
                events[helpIndex].presentation.text,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testRetainedBundleKeysPresent() {
        // 保留 key：仍被 HelpAction 引用
        assertTrue(MpSqlVisualizerBundle.message("log.panel.help").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.title").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.close").isNotBlank())
    }

    fun testRemovedBundleKeysAbsent() {
        // 内容 key 已迁移到本地 HTML 文件
        assertFalse(
            "help.content.features 应已移除",
            MpSqlVisualizerBundle.message("help.content.features").contains("<h3>"),
        )
        assertFalse(
            "help.content.usage 应已移除",
            MpSqlVisualizerBundle.message("help.content.usage").contains("<h3>"),
        )
        assertFalse(
            "help.content.shortcuts 应已移除",
            MpSqlVisualizerBundle.message("help.content.shortcuts").contains("<h3>"),
        )
        assertFalse(
            "help.content.other 应已移除",
            MpSqlVisualizerBundle.message("help.content.other").contains("<h3>"),
        )
    }
}
