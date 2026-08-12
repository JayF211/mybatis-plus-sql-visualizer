package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
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
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 [rebuild] 重建表单即时生效；
 *  - 「快捷键」行——展示当前快捷键 + 一键跳转 IDE Keymap 设置修改。
 */
class PluginSettingsPanel(private val settings: PluginSettings) {

    /** 语言选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。 */
    private class LanguageOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun languageOptions(): Array<LanguageOption> = arrayOf(
        LanguageOption("zh", MpSqlVisualizerBundle.message("settings.language.zh")),
        LanguageOption("en", MpSqlVisualizerBundle.message("settings.language.en")),
        LanguageOption("AUTO", MpSqlVisualizerBundle.message("settings.language.auto")),
    )

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    /** 方言下拉（选项来自方言注册表）。 */
    private val dialectCombo = JComboBox(DialectRegistry.dialects.map { it.name }.toTypedArray())

    /** 参数替换开关。 */
    private val replaceParamsCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"))

    /** 格式化开关。 */
    private val formatCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"))

    /** 自动预览（悬停）开关。 */
    private val autoPreviewCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.autoPreview"))

    /** gutter 预览 SQL 图标开关（M10）。 */
    private val gutterIconCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"))

    /** 悬停延迟（毫秒）。 */
    private val hoverDelaySpinner = JSpinner(SpinnerNumberModel(500, 100, 5000, 100))

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    private val languageCombo = JComboBox<LanguageOption>()

    /** 修改快捷键按钮（M14：跳转 IDE Keymap 设置）。 */
    private val modifyShortcutButton = JButton(MpSqlVisualizerBundle.message("settings.shortcut.modify"))

    private val rootPanel: JPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(10)
    }

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    init {
        buildRows(rootPanel)
        reset()
    }

    /**
     * 构建表单布局（GridBag 两列表格：标签 + 控件）。
     * rebuild 时重新调用（语言切换后文案刷新）。
     */
    private fun buildRows(panel: JPanel) {
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
        addRow("", autoPreviewCheck)
        addRow("", gutterIconCheck)
        addRow(MpSqlVisualizerBundle.message("settings.hoverDelay"), hoverDelaySpinner)
        addRow(MpSqlVisualizerBundle.message("settings.language"), languageCombo.apply {
            model = DefaultComboBoxModel(languageOptions())
            // 选中当前配置值（按 value 匹配，AUTO 兜底）
            selectedItem = languageOptions().firstOrNull { it.value == settings.state.language }
                ?: languageOptions().first()
        })
        addRow(MpSqlVisualizerBundle.message("settings.shortcut.current", currentShortcutText()), modifyShortcutButton.apply {
            addActionListener {
                // 打开 IDE Keymap 设置（application-level；用户可搜索 Convert to SQL 修改）
                ShowSettingsUtil.getInstance().showSettingsDialog(null, "preferences.keymap")
            }
        })

        // 补充说明
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JBLabel(MpSqlVisualizerBundle.message("settings.keymap.hint")), gbc)
    }

    /** 当前 Convert to SQL 快捷键文本（Keymap 中实际生效的第一个快捷键；环境异常兜底占位）。 */
    private fun currentShortcutText(): String = runCatching {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(ConvertToSqlAction.ACTION_ID)
        if (shortcuts.isEmpty()) "—" else KeymapUtil.getShortcutText(shortcuts.first())
    }.getOrElse { "—" }

    /**
     * 语言切换后重建表单（设置页自身文案即时刷新；控件值从配置回读）。
     */
    fun rebuild() {
        rootPanel.removeAll()
        buildRows(rootPanel)
        reset()
        rootPanel.revalidate()
        rootPanel.repaint()
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
            || autoPreviewCheck.isSelected != s.autoPreview
            || gutterIconCheck.isSelected != s.showGutterIcon
            || hoverDelaySpinner.value != s.hoverDelay
            || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）。
     *
     * @return 界面语言是否发生变化（true 时调用方应 [rebuild] 刷新设置页文案）
     */
    fun apply(): Boolean {
        val s = settings.state
        val languageChanged = (languageCombo.selectedItem as? LanguageOption)?.value != s.language
        s.dialect = dialectCombo.selectedItem as? String ?: PluginSettings.DEFAULT_DIALECT
        s.replaceParams = replaceParamsCheck.isSelected
        s.formatSql = formatCheck.isSelected
        s.autoPreview = autoPreviewCheck.isSelected
        s.showGutterIcon = gutterIconCheck.isSelected
        s.hoverDelay = hoverDelaySpinner.value as? Int ?: PluginSettings.DEFAULT_HOVER_DELAY
        s.language = (languageCombo.selectedItem as? LanguageOption)?.value ?: PluginSettings.DEFAULT_LANGUAGE
        return languageChanged
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    fun reset() {
        val s = settings.state
        dialectCombo.selectedItem = s.dialect
        replaceParamsCheck.isSelected = s.replaceParams
        formatCheck.isSelected = s.formatSql
        autoPreviewCheck.isSelected = s.autoPreview
        gutterIconCheck.isSelected = s.showGutterIcon
        hoverDelaySpinner.value = s.hoverDelay
        languageCombo.selectedItem = languageOptions().firstOrNull { it.value == s.language } ?: languageOptions().first()
    }
}
