package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * 项目级设置页（O-5 Phase B；T-SettingsTruth P4 接线补完）。
 *
 * 已注册 plugin.xml `<projectConfigurable parentId="tools">`（项目级 configurable 不得挂在
 * 应用级 configurable 之下——平台限制，挂 tools 顶层与全局页平级），Settings → Tools →
 * SQL Visualizer（Current Project）。
 *
 * 展示「使用项目设置」总开关 + 十项可覆盖字段（State 十字段一一对应）：
 * 方言 / 参数替换 / 格式化 / 逻辑删除注入 / 逻辑删除值 / 表前缀 / 表后缀 / 推断策略 / 忽略字段。
 * 开关类字段 tri-state 语义：null = 使用全局（控件初始灌全局值，apply 时写实际勾选态）；
 * 字符串字段空 = 使用全局（tooltip 说明，[settings.project.field.tip]）。
 *
 * T-UiNative T-UN-15（试点）：GridBag 手摆布局迁 Kotlin UI DSL v2（`panel { }`）。
 * apply/isModified/reset 手写逻辑与 `modified` 标志位范式保留（面板存取仍走
 * [ProjectPluginSettings] 单例）；isModified 除标志位外逐项比对控件与 state
 * （null 语义：checkbox 勾选态与 state 值按「null 视为全局值」比较）。
 */
class ProjectPluginSettingsConfigurable(private val project: Project) : Configurable {

    private val projectSettings = ProjectPluginSettings.getInstance(project)
    private var useProjectCheck: JBCheckBox? = null
    private var dialectCombo: ComboBox<String>? = null
    private var replaceParamsCheck: JBCheckBox? = null
    private var formatSqlCheck: JBCheckBox? = null
    private var injectLogicDeleteCheck: JBCheckBox? = null
    private var logicDeleteValueField: JBTextField? = null
    private var tablePrefixField: JBTextField? = null
    private var tableSuffixField: JBTextField? = null
    private var inferenceCombo: ComboBox<String>? = null
    private var ignoredFieldsField: JBTextField? = null
    private var modified = false

    /** 全局设置（测试环境未注册 service 时兜底默认 State）。 */
    private val globalState: PluginSettings.State
        get() = runCatching { PluginSettings.getInstance().state }.getOrDefault(PluginSettings.State())

    private fun fieldTip(): String = MpSqlVisualizerBundle.message("settings.project.field.tip")

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        val st = projectSettings.state
        val global = globalState
        // T-UiNative T-UN-15：布局层迁 DSL v2；控件值/启用态的初始灌入与监听经
        // applyToComponent 复刻原 JCheckBox/JComboBox 行为（addActionListener 语义不变）
        lateinit var checkCell: Cell<JBCheckBox>
        lateinit var comboCell: Cell<ComboBox<String>>
        lateinit var replaceParamsCell: Cell<JBCheckBox>
        lateinit var formatSqlCell: Cell<JBCheckBox>
        lateinit var injectLogicDeleteCell: Cell<JBCheckBox>
        lateinit var logicDeleteValueCell: Cell<JBTextField>
        lateinit var tablePrefixCell: Cell<JBTextField>
        lateinit var tableSuffixCell: Cell<JBTextField>
        lateinit var inferenceCell: Cell<ComboBox<String>>
        lateinit var ignoredFieldsCell: Cell<JBTextField>
        val panel = panel {
            row {
                checkCell = checkBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"))
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
                        isSelected = st.useProjectSettings
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.dialect")) {
                comboCell = comboBox(DialectRegistry.dialects.map { it.name })
                    .applyToComponent {
                        selectedItem = st.dialect.ifBlank { global.dialect }
                        addActionListener { modified = true }
                    }
            }
            row {
                replaceParamsCell = checkBox(MpSqlVisualizerBundle.message("settings.project.replaceParams"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.replaceParams ?: global.replaceParams
                        addActionListener { modified = true }
                    }
            }
            row {
                formatSqlCell = checkBox(MpSqlVisualizerBundle.message("settings.project.formatSql"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.formatSql ?: global.formatSql
                        addActionListener { modified = true }
                    }
            }
            row {
                injectLogicDeleteCell = checkBox(MpSqlVisualizerBundle.message("settings.project.injectLogicDelete"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.injectLogicDelete ?: global.injectLogicDelete
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.logicDeleteValue")) {
                logicDeleteValueCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.logicDeleteValue.ifBlank { global.logicDeleteValue }
                        document = object : javax.swing.text.PlainDocument() {
                            override fun insertString(offs: Int, str: String?, attr: javax.swing.text.AttributeSet?) {
                                super.insertString(offs, str, attr)
                                modified = true
                            }
                            override fun remove(offs: Int, len: Int) {
                                super.remove(offs, len)
                                modified = true
                            }
                        }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.tablePrefix")) {
                tablePrefixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.tablePrefix.ifBlank { global.tablePrefix }
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.tableSuffix")) {
                tableSuffixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.tableSuffix.ifBlank { global.tableSuffix }
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.tableNameInference")) {
                inferenceCell = comboBox(listOf(PluginSettings.INFERENCE_SNAKE, PluginSettings.INFERENCE_CAMEL))
                    .applyToComponent {
                        selectedItem = st.tableNameInference.ifBlank { global.tableNameInference }
                        addActionListener { modified = true }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.project.ignoredFields")) {
                ignoredFieldsCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.ignoredFields.ifBlank { global.ignoredFields }
                        addActionListener { modified = true }
                    }
            }
        }
        useProjectCheck = checkCell.component
        dialectCombo = comboCell.component
        replaceParamsCheck = replaceParamsCell.component
        formatSqlCheck = formatSqlCell.component
        injectLogicDeleteCheck = injectLogicDeleteCell.component
        logicDeleteValueField = logicDeleteValueCell.component
        tablePrefixField = tablePrefixCell.component
        tableSuffixField = tableSuffixCell.component
        inferenceCombo = inferenceCell.component
        ignoredFieldsField = ignoredFieldsCell.component
        return panel
    }

    /** 三态布尔比对：state 为 null 视为全局值，与勾选态比较。 */
    private fun boolDiffers(check: JBCheckBox?, stateValue: Boolean?, globalValue: Boolean): Boolean {
        check ?: return false
        return check.isSelected != (stateValue ?: globalValue)
    }

    private fun stringDiffers(field: JBTextField?, stateValue: String): Boolean {
        field ?: return false
        return field.text.trim() != stateValue
    }

    override fun isModified(): Boolean {
        if (modified) return true
        val st = projectSettings.state
        val global = globalState
        if ((useProjectCheck?.isSelected ?: st.useProjectSettings) != st.useProjectSettings) return true
        if ((dialectCombo?.selectedItem as? String ?: st.dialect) != st.dialect) return true
        if (boolDiffers(replaceParamsCheck, st.replaceParams, global.replaceParams)) return true
        if (boolDiffers(formatSqlCheck, st.formatSql, global.formatSql)) return true
        if (boolDiffers(injectLogicDeleteCheck, st.injectLogicDelete, global.injectLogicDelete)) return true
        if (stringDiffers(logicDeleteValueField, st.logicDeleteValue)) return true
        if (stringDiffers(tablePrefixField, st.tablePrefix)) return true
        if (stringDiffers(tableSuffixField, st.tableSuffix)) return true
        if ((inferenceCombo?.selectedItem as? String ?: st.tableNameInference) != st.tableNameInference) return true
        if (stringDiffers(ignoredFieldsField, st.ignoredFields)) return true
        return false
    }

    override fun apply() {
        val st = projectSettings.state
        useProjectCheck?.let { st.useProjectSettings = it.isSelected }
        dialectCombo?.let { st.dialect = it.selectedItem as? String ?: "" }
        replaceParamsCheck?.let { st.replaceParams = it.isSelected }
        formatSqlCheck?.let { st.formatSql = it.isSelected }
        injectLogicDeleteCheck?.let { st.injectLogicDelete = it.isSelected }
        logicDeleteValueField?.let { st.logicDeleteValue = it.text.trim() }
        tablePrefixField?.let { st.tablePrefix = it.text.trim() }
        tableSuffixField?.let { st.tableSuffix = it.text.trim() }
        inferenceCombo?.let { st.tableNameInference = it.selectedItem as? String ?: "" }
        ignoredFieldsField?.let { st.ignoredFields = it.text.trim() }
        modified = false
    }

    override fun reset() {
        val st = projectSettings.state
        val global = globalState
        useProjectCheck?.isSelected = st.useProjectSettings
        dialectCombo?.selectedItem = st.dialect.ifBlank { global.dialect }
        replaceParamsCheck?.isSelected = st.replaceParams ?: global.replaceParams
        formatSqlCheck?.isSelected = st.formatSql ?: global.formatSql
        injectLogicDeleteCheck?.isSelected = st.injectLogicDelete ?: global.injectLogicDelete
        logicDeleteValueField?.text = st.logicDeleteValue.ifBlank { global.logicDeleteValue }
        tablePrefixField?.text = st.tablePrefix.ifBlank { global.tablePrefix }
        tableSuffixField?.text = st.tableSuffix.ifBlank { global.tableSuffix }
        inferenceCombo?.selectedItem = st.tableNameInference.ifBlank { global.tableNameInference }
        ignoredFieldsField?.text = st.ignoredFields.ifBlank { global.ignoredFields }
        modified = false
    }

    override fun disposeUIResources() {
        useProjectCheck = null
        dialectCombo = null
        replaceParamsCheck = null
        formatSqlCheck = null
        injectLogicDeleteCheck = null
        logicDeleteValueField = null
        tablePrefixField = null
        tableSuffixField = null
        inferenceCombo = null
        ignoredFieldsField = null
    }
}
