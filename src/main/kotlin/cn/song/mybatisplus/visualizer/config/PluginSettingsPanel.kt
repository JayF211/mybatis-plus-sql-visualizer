package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；[apply]/[reset]/[isModified] 与 [PluginSettings] 联动，
 * 由 [PluginSettingsConfigurable] 接入 IDE Settings 框架（保存即生效）。
 */
class PluginSettingsPanel(private val settings: PluginSettings) {

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    /** 方言下拉（选项来自方言注册表）。 */
    private val dialectCombo = JComboBox(DialectRegistry.dialects.map { it.name }.toTypedArray())

    /** 参数替换开关。 */
    private val replaceParamsCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"))

    /** 格式化开关。 */
    private val formatCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"))

    /** 显示方式下拉（代码值 → 本地化标签）。 */
    private val displayOptions = listOf(
        PluginSettings.DISPLAY_DIALOG to MpSqlVisualizerBundle.message("settings.displayMode.dialog"),
        PluginSettings.DISPLAY_TOOL_WINDOW to MpSqlVisualizerBundle.message("settings.displayMode.toolWindow"),
        PluginSettings.DISPLAY_BOTH to MpSqlVisualizerBundle.message("settings.displayMode.both"),
    )
    private val displayCombo = JComboBox(displayOptions.map { it.second }.toTypedArray())

    /** 自动预览（悬停）开关。 */
    private val autoPreviewCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.autoPreview"))

    /** 悬停延迟（毫秒）。 */
    private val hoverDelaySpinner = JSpinner(SpinnerNumberModel(500, 100, 5000, 100))

    private val rootPanel: JPanel = buildForm()

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    init {
        reset()
    }

    /**
     * 构建表单布局（GridBag 两列表格：标签 + 控件）。
     */
    private fun buildForm(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(6, 6, 6, 6)
        }

        fun addRow(label: String, component: JComponent) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(component, gbc)
            gbc.fill = GridBagConstraints.NONE
            gbc.gridy++
        }

        addRow(MpSqlVisualizerBundle.message("settings.dialect"), dialectCombo)
        addRow("", replaceParamsCheck)
        addRow("", formatCheck)
        addRow(MpSqlVisualizerBundle.message("settings.displayMode"), displayCombo)
        addRow("", autoPreviewCheck)
        addRow(MpSqlVisualizerBundle.message("settings.hoverDelay"), hoverDelaySpinner)

        // 补充说明
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JBLabel(MpSqlVisualizerBundle.message("settings.keymap.hint")), gbc)
        return panel
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /**
     * 根面板（Configurable.createComponent 返回）。
     */
    fun getRoot(): JComponent = rootPanel

    /**
     * 表单值是否与配置不一致。
     */
    fun isModified(): Boolean {
        val s = settings.state
        return dialectCombo.selectedItem != s.dialect
            || replaceParamsCheck.isSelected != s.replaceParams
            || formatCheck.isSelected != s.formatSql
            || displayCombo.selectedIndex != displayOptions.indexOfFirst { it.first == s.displayMode }
            || autoPreviewCheck.isSelected != s.autoPreview
            || hoverDelaySpinner.value != s.hoverDelay
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）。
     */
    fun apply() {
        val s = settings.state
        s.dialect = dialectCombo.selectedItem as? String ?: PluginSettings.DEFAULT_DIALECT
        s.replaceParams = replaceParamsCheck.isSelected
        s.formatSql = formatCheck.isSelected
        s.displayMode = displayOptions.getOrNull(displayCombo.selectedIndex)?.first ?: PluginSettings.DISPLAY_BOTH
        s.autoPreview = autoPreviewCheck.isSelected
        s.hoverDelay = hoverDelaySpinner.value as? Int ?: PluginSettings.DEFAULT_HOVER_DELAY
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    fun reset() {
        val s = settings.state
        dialectCombo.selectedItem = s.dialect
        replaceParamsCheck.isSelected = s.replaceParams
        formatCheck.isSelected = s.formatSql
        displayCombo.selectedIndex = displayOptions.indexOfFirst { it.first == s.displayMode }.coerceAtLeast(0)
        autoPreviewCheck.isSelected = s.autoPreview
        hoverDelaySpinner.value = s.hoverDelay
    }
}
