package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JComboBox

/**
 * 项目级设置页（O-5 Phase B）。
 *
 * 接入 IDE Settings → 当前项目的 MyBatis-Plus SQL Visualizer（project-level）。
 * 展示「使用项目设置」开关 + 可覆盖项（方言/参数替换/逻辑删除/表名映射等）。
 *
 * 仅当「使用项目设置」开启时可编辑其它项；关闭时回退全局 [PluginSettings]。
 */
class ProjectPluginSettingsConfigurable(private val project: Project) : Configurable {

    private val projectSettings = ProjectPluginSettings.getInstance(project)
    private var useProjectCheck: JCheckBox? = null
    private var dialectCombo: javax.swing.JComboBox<String>? = null
    private var modified = false

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 6, 4, 6)
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // 「使用项目设置」开关
        val check = JCheckBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings")).apply {
            isSelected = projectSettings.state.useProjectSettings
            toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
            addActionListener {
                modified = true
                // 开启/关闭时刷新可用性由 apply 处理
            }
        }
        useProjectCheck = check
        panel.add(check, gbc)

        gbc.gridy++
        gbc.insets = Insets(4, 24, 4, 6)  // 缩进
        gbc.fill = GridBagConstraints.NONE  // 不拉伸，保持自然宽度

        // 方言下拉（示例项，后续可扩展更多）
        val dialectPanel = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel(MpSqlVisualizerBundle.message("settings.dialect")), BorderLayout.WEST)
            val combo = JComboBox(DialectRegistry.dialects.map { it.name }.toTypedArray())
            combo.selectedItem = projectSettings.state.dialect.ifBlank {
                PluginSettings.getInstance().state.dialect
            }
            combo.isEnabled = projectSettings.state.useProjectSettings
            combo.addActionListener { modified = true }
            add(combo, BorderLayout.CENTER)
        }
        dialectCombo = dialectPanel.components.filterIsInstance<JComboBox<*>>().first() as? JComboBox<String>
        panel.add(dialectPanel, gbc)

        // 底部占位
        gbc.gridy++
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        return panel
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        val check = useProjectCheck ?: return
        val combo = dialectCombo ?: return
        projectSettings.state.useProjectSettings = check.isSelected
        projectSettings.state.dialect = combo.selectedItem as? String ?: ""
        modified = false
    }

    override fun reset() {
        useProjectCheck?.isSelected = projectSettings.state.useProjectSettings
        dialectCombo?.selectedItem = projectSettings.state.dialect.ifBlank {
            PluginSettings.getInstance().state.dialect
        }
        modified = false
    }

    override fun disposeUIResources() {
        useProjectCheck = null
        dialectCombo = null
    }
}
