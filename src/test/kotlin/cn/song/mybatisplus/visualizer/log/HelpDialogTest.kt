package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 帮助弹窗与工具栏帮助入口测试（T-M39）。
 *
 * 内容断言走 JEditorPane.getText() 返回的原始 HTML 源（非 ASCII 字符被解析器实体化，
 * 故只断言语言无关的 ASCII 字面与结构计数）；工具栏断言走 toolbarGroup
 * （面板测试风格——headless 不布局，update 手动触发，图标/文案从 event.presentation 读取）。
 */
class HelpDialogTest : BasePlatformTestCase() {

    fun testDialogRendersHelpContent() {
        val dialog = HelpDialog(project)
        val text = dialog.contentForTest().text
        assertTrue("帮助 HTML 渲染文本不应为空", text.isNotBlank())
        // 关键片段：核心功能 / 默认快捷键 / 反馈入口（语言无关字面）
        assertTrue(text, text.contains("Wrapper"))
        assertTrue(text, text.contains("Preparing"))
        assertTrue(text, text.contains("Alt+Shift+Q"))
        assertTrue(text, text.contains("Alt+Shift+J"))
        assertTrue(text, text.contains("gitee.com"))
        assertTrue(text, text.contains("Keymap"))
    }

    fun testDialogSectionsAllPresent() {
        val dialog = HelpDialog(project)
        val html = dialog.contentForTest().text
        // getText() 返回原始 HTML 源（非 ASCII 被解析器实体化，如 → 变 &#8594;）——
        // 断言结构与语言无关的 ASCII 片段；四节各应有 h3 标题 + ul 列表
        assertEquals("四节内容各应有 h3 标题", 4, Regex("<h3>").findAll(html).count())
        assertEquals("四节内容各应有 ul 列表", 4, Regex("<ul>").findAll(html).count())
        assertTrue("核心功能节：日志还原片段", html.contains("Preparing/Parameters"))
        assertTrue("使用方法节：右键/快捷键片段", html.contains("Convert to SQL"))
        assertTrue("快捷键节：macOS 绑定片段", html.contains("Cmd+Shift+J"))
        assertTrue("快捷键节：Keymap 修改提示（p 元素）", html.contains("<p>") && html.contains("Keymap"))
        assertTrue("其他节：Gitee Issues 反馈入口", html.contains("Gitee Issues"))
    }

    fun testToolbarGroupContainsHelpAction() {
        // 直接构造（对齐 SqlLogManagerBehaviorTest——测试环境 project.getService 不可用）
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            val children = panel.toolbarGroup.getChildren(null).toList()
            assertEquals("工具栏组应有 4 项（捕捉/清空/设置/帮助）", 4, children.size)
            // update 由工具栏生命周期驱动（headless 不布局不触发），手动走一次——
            // 图标/文案写入 event.presentation（2024.3 AnAction 无 getPresentation()）
            val events = children.map {
                AnActionEvent.createFromAnAction(it, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            }
            children.forEachIndexed { i, action -> action.update(events[i]) }
            val helpIndex = events.indexOfFirst { it.presentation.icon === AllIcons.General.ContextHelp }
            assertTrue("工具栏组应含帮助入口（? 图标，设置按钮旁）", helpIndex >= 0)
            assertEquals(
                MpSqlVisualizerBundle.message("log.panel.help"),
                events[helpIndex].presentation.text,
            )
        } finally {
            panel.dispose()
        }
    }

    fun testBundleKeysPresent() {
        assertTrue(MpSqlVisualizerBundle.message("log.panel.help").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.title").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.dialog.close").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.content.features").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.content.usage").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.content.shortcuts").isNotBlank())
        assertTrue(MpSqlVisualizerBundle.message("help.content.other").isNotBlank())
        // 内容键应解析为真实 HTML（缺失时 message 兜底返回 key 本身）
        assertTrue(MpSqlVisualizerBundle.message("help.content.features").contains("<h3>"))
        assertTrue(MpSqlVisualizerBundle.message("help.content.other").contains("gitee.com"))
    }
}
