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
 * 帮助按钮已改为 HTMLEditorProvider.openEditor 在编辑器标签页打开内嵌浏览器；
 * 测试聚焦：(1) 工具栏组含帮助入口；(2) 保留 bundle key 存在；(3) 废弃 key 已移除。
 */
class HelpDialogTest : BasePlatformTestCase() {

    fun testToolbarGroupContainsHelpAction() {
        // 直接构造（对齐 SqlLogManagerBehaviorTest——测试环境 project.getService 不可用）
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            val children = panel.toolbarGroup.getChildren(null).toList()
            assertEquals("工具栏组应有 5 项（捕捉/清空/设置/搜索/帮助）", 5, children.size)
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
    }

    fun testRemovedBundleKeysAbsent() {
        // 废弃 key：help.dialog.close 已移除
        assertFalse(
            "help.dialog.close 应已移除",
            MpSqlVisualizerBundle.message("help.dialog.close").isNotBlank() &&
                MpSqlVisualizerBundle.message("help.dialog.close") != "help.dialog.close",
        )
        // 内容 key 已迁移到在线文档
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
