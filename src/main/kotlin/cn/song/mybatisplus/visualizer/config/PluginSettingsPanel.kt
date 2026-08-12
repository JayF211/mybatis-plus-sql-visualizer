package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；[apply]/[reset]/[isModified] 与 [PluginSettings] 联动，
 * 由 [PluginSettingsConfigurable] 接入 IDE Settings 框架（保存即生效）。
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 [rebuild] 重建表单即时生效；
 *  - 「快捷键」行——[KeyboardShortcutPanel] **内嵌编辑** Convert to SQL 快捷键，
 *    保存时直接写入 IDE 当前 Keymap（不再跳转）。
 *
 * M14.1（用户反馈）：
 *  - 移除悬停预览相关配置（autoPreview / hoverDelay 已随功能删除）；
 *  - 布局紧凑化（减小行距 + 底部 weighty 占位，消除内容区上方空白）。
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

    /** gutter 预览 SQL 图标开关（M10）。 */
    private val gutterIconCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"))

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    private val languageCombo = JComboBox<LanguageOption>()

    /** 快捷键内嵌编辑（M14.1：录制式修改 Convert to SQL 的 KeyboardShortcut，保存写入 Keymap）。 */
    private val shortcutEditor = ShortcutEditor()

    private val rootPanel: JPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(8, 10, 8, 10)
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
            insets = Insets(4, 6, 4, 6)   // M14.1 紧凑行距
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
        addRow("", gutterIconCheck)
        addRow(MpSqlVisualizerBundle.message("settings.language"), languageCombo.apply {
            model = DefaultComboBoxModel(languageOptions())
            // 选中当前配置值（按 value 匹配，zh 兜底）
            selectedItem = languageOptions().firstOrNull { it.value == settings.state.language }
                ?: languageOptions().first()
        })
        addRow(MpSqlVisualizerBundle.message("settings.shortcut"), shortcutEditor)

        // M14.1 布局优化：底部 weighty 占位，把内容顶到顶部（消除内容区上方空白/居中拉伸）
        gbc.gridx = 0
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)
    }

    /**
     * 快捷键内嵌编辑行（M14.1）：只读显示当前快捷键 + 「修改」按钮弹出录制对话框，
     * 捕获按键组合后回填（保存时由 [PluginSettingsPanel.apply] 写入 Keymap）。
     * 不依赖 impl 包（KeyboardShortcutPanel 为包私有），全公开 API 实现。
     */
    private inner class ShortcutEditor : JPanel(BorderLayout(6, 0)) {

        var shortcut: KeyboardShortcut? = null
            set(value) {
                field = value
                textField.text = value?.let { KeymapUtil.getShortcutText(it) } ?: "—"
            }

        private val textField = JBTextField(18).apply {
            isEditable = false
            toolTipText = MpSqlVisualizerBundle.message("settings.shortcut.tip")
        }

        private val editButton = JButton(MpSqlVisualizerBundle.message("settings.shortcut.edit"))

        init {
            add(textField, BorderLayout.CENTER)
            add(editButton, BorderLayout.EAST)
            editButton.addActionListener { captureShortcut() }
        }

        /** 弹出模态录制对话框：聚焦输入框后按下组合键即完成，ESC 取消。 */
        private fun captureShortcut() {
            val dialog = JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL)
            dialog.title = MpSqlVisualizerBundle.message("settings.shortcut.capture")
            val capture = JBTextField(20).apply {
                isFocusable = true
            }
            val panel = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(10)
                add(JBLabel(MpSqlVisualizerBundle.message("settings.shortcut.capture")), BorderLayout.NORTH)
                add(capture, BorderLayout.CENTER)
            }
            dialog.contentPane.add(panel)
            dialog.pack()
            dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this))
            capture.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> dialog.dispose()
                        KeyEvent.VK_ENTER -> dialog.dispose()
                        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META -> Unit // 纯修饰键忽略
                        else -> {
                            val stroke = KeyStroke.getKeyStrokeForEvent(e)
                            shortcut = KeyboardShortcut(stroke, null)
                            dialog.dispose()
                        }
                    }
                }
            })
            dialog.isVisible = true
            capture.requestFocusInWindow()
        }
    }

    // ------------------------------------------------------------------
    // 快捷键读写（M14.1：内嵌编辑，直接写当前 Keymap）
    // ------------------------------------------------------------------

    /** 当前 Convert to SQL 的键盘快捷键（activeKeymap 中第一个 KeyboardShortcut；环境异常兜底 null）。 */
    private fun currentShortcut(): KeyboardShortcut? = runCatching {
        KeymapManager.getInstance().activeKeymap
            .getShortcuts(ConvertToSqlAction.ACTION_ID)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
    }.getOrNull()

    /** 将编辑后的快捷键写入当前 Keymap（移除旧的 KeyboardShortcut，添加新的；保存即生效并持久化）。 */
    private fun applyShortcut() {
        runCatching {
            val keymap = KeymapManager.getInstance().activeKeymap
            keymap.getShortcuts(ConvertToSqlAction.ACTION_ID)
                .filterIsInstance<KeyboardShortcut>()
                .forEach { keymap.removeShortcut(ConvertToSqlAction.ACTION_ID, it) }
            shortcutEditor.shortcut?.let { keymap.addShortcut(ConvertToSqlAction.ACTION_ID, it) }
        }
    }

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
     * 表单值是否与配置不一致（含快捷键编辑状态）。
     */
    fun isModified(): Boolean {
        val s = settings.state
        return dialectCombo.selectedItem != s.dialect
            || replaceParamsCheck.isSelected != s.replaceParams
            || formatCheck.isSelected != s.formatSql
            || gutterIconCheck.isSelected != s.showGutterIcon
            || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
            || shortcutEditor.shortcut != currentShortcut()
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 [rebuild] 刷新设置页文案）
     */
    fun apply(): Boolean {
        val s = settings.state
        val languageChanged = (languageCombo.selectedItem as? LanguageOption)?.value != s.language
        s.dialect = dialectCombo.selectedItem as? String ?: PluginSettings.DEFAULT_DIALECT
        s.replaceParams = replaceParamsCheck.isSelected
        s.formatSql = formatCheck.isSelected
        s.showGutterIcon = gutterIconCheck.isSelected
        s.language = (languageCombo.selectedItem as? LanguageOption)?.value ?: PluginSettings.DEFAULT_LANGUAGE
        if (shortcutEditor.shortcut != currentShortcut()) applyShortcut()
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
        gutterIconCheck.isSelected = s.showGutterIcon
        languageCombo.selectedItem = languageOptions().firstOrNull { it.value == s.language } ?: languageOptions().first()
        shortcutEditor.shortcut = currentShortcut()
    }
}
