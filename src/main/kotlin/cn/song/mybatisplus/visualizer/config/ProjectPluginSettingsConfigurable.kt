package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * 项目级设置页（O-5 Phase B）。
 *
 * 接入 IDE Settings → 当前项目的 SQL Visualizer（project-level）。
 * 展示「使用项目设置」开关 + 可覆盖项（方言/参数替换/逻辑删除/表名映射等）。
 *
 * 仅当「使用项目设置」开启时可编辑其它项；关闭时回退全局 [PluginSettings]。
 *
 * T-UiNative T-UN-15（试点）：GridBag 手摆布局迁 Kotlin UI DSL v2（`panel { }`）。
 * 保守迁移——apply/isModified/reset 手写逻辑与 `modified` 标志位原样保留（面板存取仍走
 * [ProjectPluginSettings] 单例）；原「下拉随开关启用」只是创建期一次性赋值（无动态联动，
 * 原注释：开启/关闭刷新由 apply 处理），故用 `applyToComponent` 原样复刻，不引入
 * `enabledIf` 动态联动改变行为。主面板 [PluginSettingsPanel] 的迁移立后续任务。
 *
 * ⚠️ 20260907-1617 审计：本类同样未注册进 plugin.xml（当前为死类，无挂载点、无联动逻辑，
 * apply 只写 ProjectPluginSettings 不涉自动监听）——保留代码待 O-5 Phase B 后续接线，
 * 接线前不得在其中新增依赖挂载点的联动逻辑（教训见 pitfall-log-startup-capture-regression）。
 */
class ProjectPluginSettingsConfigurable(private val project: Project) : Configurable {

    private val projectSettings = ProjectPluginSettings.getInstance(project)
    private var useProjectCheck: JBCheckBox? = null
    private var dialectCombo: ComboBox<String>? = null
    private var modified = false

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        // T-UiNative T-UN-15：布局层迁 DSL v2；控件值/启用态的初始灌入与监听经
        // applyToComponent 复刻原 JCheckBox/JComboBox 行为（doClick/addActionListener 语义不变）
        val initialDialect = projectSettings.state.dialect.ifBlank {
            PluginSettings.getInstance().state.dialect
        }
        lateinit var checkCell: Cell<JBCheckBox>
        lateinit var comboCell: Cell<ComboBox<String>>
        val panel = panel {
            row {
                checkCell = checkBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"))
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
                        isSelected = projectSettings.state.useProjectSettings
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.dialect")) {
                comboCell = comboBox(DialectRegistry.dialects.map { it.name })
                    .applyToComponent {
                        selectedItem = initialDialect
                        isEnabled = projectSettings.state.useProjectSettings
                        addActionListener { modified = true }
                    }
            }
        }
        useProjectCheck = checkCell.component
        dialectCombo = comboCell.component
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
