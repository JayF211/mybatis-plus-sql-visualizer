package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * 工具栏图标语义守卫（T-ToolbarIconSemantics）。
 *
 * 四页签共用 [MpvsIcons]，图形统一克隆为插件资源，不再引用 `AllIcons` 字段——
 * 后者在 243/262 会映射到经典/expui 两套不同形态。本测试锁三件事：
 *  1. 每个注册表图标都能加载且为 16×16（资源缺失会退化成 0×0，直接变红）；
 *  2. 原本互相干扰的语义不再同形（预览 vs 搜索、屏蔽 vs 类型过滤、滚底 vs 排序）；
 *  3. 四个面板不再出现裸 `e.presentation.icon = AllIcons...`，防止新动作绕开注册表。
 */
class ToolbarIconSemanticsTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    fun test注册表图标全部可加载且为16px() {
        val icons = mapOf(
            "CaptureStart" to MpvsIcons.CaptureStart,
            "CaptureStop" to MpvsIcons.CaptureStop,
            "AgentPause" to MpvsIcons.AgentPause,
            "AgentResume" to MpvsIcons.AgentResume,
            "Clear" to MpvsIcons.Clear,
            "Settings" to MpvsIcons.Settings,
            "Suppress" to MpvsIcons.Suppress,
            "Search" to MpvsIcons.Search,
            "DockedPreview" to MpvsIcons.DockedPreview,
            "ScrollToEnd" to MpvsIcons.ScrollToEnd,
            "Sort" to MpvsIcons.Sort,
            "Community" to MpvsIcons.Community,
            "Help" to MpvsIcons.Help,
            "TypeFilter" to MpvsIcons.TypeFilter,
            "Convert" to MpvsIcons.Convert,
            "Execute" to MpvsIcons.Execute,
            "Paste" to MpvsIcons.Paste,
            "Copy" to MpvsIcons.Copy,
            "Format" to MpvsIcons.Format,
            "Export" to MpvsIcons.Export,
            "Back" to MpvsIcons.Back,
            "Refresh" to MpvsIcons.Refresh,
            "Diff" to MpvsIcons.Diff,
            "EditSource" to MpvsIcons.EditSource,
            "MapperXml" to MpvsIcons.MapperXml,
        )
        icons.forEach { (name, icon) ->
            assertEquals("$name 宽度", 16, icon.iconWidth)
            assertEquals("$name 高度", 16, icon.iconHeight)
        }
    }

    fun test关键歧义语义不再同形() {
        assertNotSame("预览与搜索不能同为放大镜", MpvsIcons.Search, MpvsIcons.DockedPreview)
        assertNotSame("屏蔽与类型过滤不能同为漏斗", MpvsIcons.Suppress, MpvsIcons.TypeFilter)
        assertNotSame("滚动到底与排序不能同形", MpvsIcons.ScrollToEnd, MpvsIcons.Sort)
        assertNotSame("捕捉开始与增强暂停不能同形", MpvsIcons.CaptureStart, MpvsIcons.AgentPause)
        assertSame("转换/执行与捕捉开始同形（同一绿色三角）", MpvsIcons.CaptureStart, MpvsIcons.Convert)
    }

    fun testSQL日志工具栏动作update后均带16px图标() {
        val panel = SqlLogPanel(project, manager)
        try {
            val actions = panel.toolbarGroup.getChildren(null).toList()
            assertTrue("工具栏动作数应不少于 11", actions.size >= 11)
            actions.forEach { action ->
                val event = AnActionEvent.createFromAnAction(
                    action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
                )
                action.update(event)
                // 增强暂停在关闭态置 isVisible=false 且不设图标（不会渲染），只校验实际可见动作
                if (!event.presentation.isVisible) return@forEach
                val icon = event.presentation.icon
                assertNotNull("动作「${action.templatePresentation.text}」必须带图标", icon)
                assertEquals("动作「${action.templatePresentation.text}」图标宽度", 16, icon!!.iconWidth)
                assertEquals("动作「${action.templatePresentation.text}」图标高度", 16, icon.iconHeight)
            }
        } finally {
            panel.dispose()
        }
    }

    fun test四页签不再出现裸AllIcons图标赋值() {
        val files = listOf(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/HistorySqlLogPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlToJavaPanel.kt",
        )
        val pattern = Regex("""e\.presentation\.icon\s*=\s*(com\.intellij\.icons\.)?AllIcons\.""")
        files.forEach { relative ->
            val text = File(repoRoot(), relative).readText()
            val hit = pattern.find(text)
            assertNull(
                "工具栏图标必须走 MpvsIcons，禁止裸 AllIcons 赋值：${relative} -> ${hit?.value}",
                hit,
            )
        }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）")
    }
}
