package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * 设置页内部构建号行测试（T-M23.14）：
 *  - 设置页存在「内部构建号 (MPV-Build)」标签行
 *  - 值来自 [PluginBuildInfo.displayText]（测试环境无 jar → 兜底 "—"，与 manifest 读取测试互补：
 *    jar 形态读取由 PluginBuildInfoTest 临时 jar 覆盖）
 */
class PluginSettingsPanelBuildInfoTest : BasePlatformTestCase() {

    /** 递归收集组件树中全部 JBLabel（设置页 GridBag 布局嵌套一层，递归通用）。 */
    private fun collectLabels(component: Component): List<JBLabel> {
        val labels = mutableListOf<JBLabel>()
        if (component is JBLabel) labels += component
        if (component is Container) component.components.forEach { labels += collectLabels(it) }
        return labels
    }

    fun testSettingsPanelShowsBuildNumberRow() {
        val configurable = PluginSettingsConfigurable()
        configurable.createComponent()
        val labels = collectLabels(configurable.panelForTest().getRoot())

        // 行标签（i18n）：中文环境默认「内部构建号 (MPV-Build)」
        val rowLabel = labels.firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.buildNumber") }
        assertNotNull("设置页应存在构建号行标签", rowLabel)

        // 值标签：文本 = displayText()（测试环境兜底 "—"），tooltip 解释用途
        val valueLabel = labels.firstOrNull { it.toolTipText == MpSqlVisualizerBundle.message("settings.buildNumber.tip") }
        assertNotNull("构建号行应有解释 tooltip", valueLabel)
        assertEquals("值应来自 displayText（测试环境为 —）", PluginBuildInfo.displayText(), valueLabel!!.text)
        assertEquals("—", valueLabel.text)
    }

    /** T-M28.2：构建号行复制按钮——存在、文案走 bundle、开发态（无构建号）禁用。 */
    fun testBuildNumberRowHasCopyButton() {
        val configurable = PluginSettingsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest().getRoot()

        val copyButton = collectButtons(panel)
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.buildNumber.copy") }
        assertNotNull("构建号行应有复制按钮", copyButton)
        assertEquals(
            "测试环境无构建号（非 jar 形态）→ 复制按钮应禁用（复制 — 无意义）",
            false,
            copyButton!!.isEnabled,
        )
    }

    /** 递归收集组件树中全部 JButton（复制按钮定位辅助）。 */
    private fun collectButtons(component: Component): List<JButton> {
        val buttons = mutableListOf<JButton>()
        if (component is JButton) buttons += component
        if (component is Container) component.components.forEach { buttons += collectButtons(it) }
        return buttons
    }

    /** T-M31.8：设置页 5 个分组标题（TitledSeparator）存在且按高频→低频顺序排列。 */
    fun testSettingsPanelSectionsTitles() {
        val configurable = PluginSettingsConfigurable()
        configurable.createComponent()
        val separators = collectSeparators(configurable.panelForTest().getRoot())

        val expected = listOf(
            "settings.section.preview",
            "settings.section.tableMapping",
            "settings.section.codeGen",
            "settings.section.sqlLog", // T-M31.14 新增
            "settings.section.ui",
            "settings.section.updateDiagnostics",
        ).map { MpSqlVisualizerBundle.message(it) }
        assertEquals("6 个分组标题按高频→低频顺序", expected, separators)
    }

    /** 递归收集组件树中全部 TitledSeparator 标题（按布局顺序）。 */
    private fun collectSeparators(component: Component): List<String> {
        val titles = mutableListOf<String>()
        if (component is TitledSeparator) titles += component.text ?: ""
        if (component is Container) component.components.forEach { titles += collectSeparators(it) }
        return titles
    }
}
