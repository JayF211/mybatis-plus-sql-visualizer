package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * UI024：SQL 日志页「自动保存」说明区右侧三个本地数据操作按钮在默认设置窗口下被右缘裁切。
 *
 * 判别口径（不是“控件存在”冒烟）：
 *  - 根面板 preferred width 必须落在真实滚动宿主的可视宽度内（否则 Settings 出横向滚动条）；
 *  - 三个按钮布局后必须有正尺寸、可见，且整体矩形完全落在 viewport 可视范围内；
 *  - 按钮所在行容器的 preferred width 必须 <= 保守内容宽度 700px（该行自身不得再撑宽页面）。
 *
 * 宽度口径：默认 Settings 对话框 998×742，右侧内容区减去左侧导航树/边距后约 870~890px；
 * 本测试取 880px 复现 UI024（修复前整页 preferred=894，溢出 17px；修复后=857，不再溢出）。
 * 另保留 998px 纯内容宽度回归。
 *
 * 语言说明：英文 UI 下同页还有独立的 sourceFrameSkipPrefixes.hint 单行说明把整页撑到 1116px
 * （与 UI024 无关，本次未动），故英文用例只断言「按钮完整可见 + 本行 preferred 宽度」，
 * 不断言整页无横向滚动；中文用例同时断言无横向滚动。
 */
class StorageRowLayoutTest : BasePlatformTestCase() {

    private val storageButtons: (PluginSettingsPanel) -> List<JButton> = { panel ->
        listOf(
            panel.openCaptureDirectoryButtonForTest(),
            panel.clearPreviewHistoryButtonForTest(),
            panel.clearParamHistoryButtonForTest(),
        )
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    /** 把按钮矩形换算到滚动视口坐标系（可视区原点）。 */
    private fun boundsInViewport(button: JButton, scroll: JBScrollPane): java.awt.Rectangle =
        SwingUtilities.convertRectangle(button.parent, button.bounds, scroll.viewport)

    /** 找到按钮所在的说明行容器（section 内容面板的直接子件）。 */
    private fun disclosureRowOf(button: JButton): Component {
        var current: Component = button
        while (current.parent != null) {
            val parent = current.parent
            if (parent is Container && parent.layout is java.awt.GridBagLayout) return current
            current = parent
        }
        return current
    }

    private fun assertStorageRowFits(language: String, hostWidth: Int, requireNoHorizontalScroll: Boolean) {
        val settings = PluginSettings.getInstance()
        val before = settings.state.copy()
        val configurable = SqlLogConfigurable()
        try {
            settings.state.language = language
            val root = configurable.createComponent()
            val panel = configurable.panelForTest()
            val scroll = JBScrollPane(root)
            scroll.setSize(hostWidth, 742)
            repeat(4) { layoutTree(scroll) }

            val viewport = scroll.viewport
            val extent = viewport.extentSize
            assertTrue("$language/$hostWidth 视口应有正宽度", extent.width > 0)
            if (requireNoHorizontalScroll) {
                assertFalse(
                    "$language/$hostWidth 内容不得超出可视宽度（根面板 preferred=${root.preferredSize.width}, 视口=${extent.width}）",
                    root.preferredSize.width > extent.width,
                )
                assertFalse(
                    "$language/$hostWidth 不应出现横向滚动条（根面板 preferred=${root.preferredSize.width}, 视口=${extent.width}）",
                    scroll.horizontalScrollBar.isVisible,
                )
            }

            storageButtons(panel).forEach { button ->
                assertTrue("$language/$hostWidth 按钮应可见: ${button.text}", button.isVisible)
                assertTrue(
                    "$language/$hostWidth 按钮应有正尺寸: ${button.text} ${button.size}",
                    button.width > 0 && button.height > 0,
                )
                val bounds = boundsInViewport(button, scroll)
                assertTrue(
                    "$language/$hostWidth 按钮左缘应落在可视区内: ${button.text} $bounds",
                    bounds.x >= 0,
                )
                assertTrue(
                    "$language/$hostWidth 按钮右缘不得被裁切: ${button.text} $bounds, 可视宽=${extent.width}",
                    bounds.x + bounds.width <= extent.width,
                )
                // 行容器（说明文字 + 操作）自身不得再撑宽页面：保守口径沿用默认内容区 700px。
                val row = disclosureRowOf(button)
                assertTrue(
                    "$language/$hostWidth 按钮所在行 preferred 宽度应 <= 700: ${button.text} " +
                        "行=${row.preferredSize.width}",
                    row.preferredSize.width <= 700,
                )
            }
        } finally {
            configurable.disposeUIResources()
            settings.loadState(before)
        }
    }

    fun test默认设置内容区_三按钮完整可见且中文页无横向滚动() {
        assertStorageRowFits("zh", 880, requireNoHorizontalScroll = true)
        assertStorageRowFits("en", 880, requireNoHorizontalScroll = false)
    }

    fun test998内容宽度_三按钮完整可见且中文页无横向滚动() {
        assertStorageRowFits("zh", 998, requireNoHorizontalScroll = true)
        assertStorageRowFits("en", 998, requireNoHorizontalScroll = false)
    }

    fun test存储行preferred宽度_中英文均不超保守内容宽度() {
        listOf("zh", "en").forEach { language ->
            val settings = PluginSettings.getInstance()
            val before = settings.state.copy()
            val configurable = SqlLogConfigurable()
            try {
                settings.state.language = language
                configurable.createComponent()
                val panel = configurable.panelForTest()
                val rowWidths = storageButtons(panel).map { disclosureRowOf(it).preferredSize.width }
                assertTrue(
                    "$language 三个操作行 preferred 宽度均应 <= 700，实际=$rowWidths",
                    rowWidths.all { it in 1..700 },
                )
            } finally {
                configurable.disposeUIResources()
                settings.loadState(before)
            }
        }
    }
}
