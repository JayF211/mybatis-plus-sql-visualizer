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
 * apply/isModified/reset 手写逻辑保留（面板存取仍走 [ProjectPluginSettings] 单例）。
 *
 * T-RA-04 判定：原 `modified` 标志位已删除。T-SettingsTruth 认定该标志位不可信后
 * isModified() 改纯逐项比对，标志位只剩 13 处写入、零读取——保留等于在类里留一份
 * 「写了却没人信」的假状态（后续读者会误以为它参与判定）；控件监听器也不再为置位而挂。
 * isModified() 逐项比对控件与 state（null 语义：checkbox 勾选态与 state 值按
 * 「null 视为全局值」比较）。
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
    private var inferenceCombo: ComboBox<InferenceOption>? = null
    private var ignoredFieldsField: JBTextField? = null

    /**
     * 表名推断策略下拉项：值 + 当前语言下的显示文案（复用全局面板同一组 bundle 键，
     * 禁用枚举原值直接当显示项——否则中文界面出现 `SNAKE`/`CAMEL` 裸值，与全局页文案不一致）。
     * 必须为 data class：JDK17 `JComboBox.setSelectedItem` 按 equals 匹配（见 [PluginSettingsPanel] 同类教训）。
     */
    private data class InferenceOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun inferenceOptions(): Array<InferenceOption> = arrayOf(
        InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
        InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
    )

    /** 全局设置（测试环境未注册 service 时兜底默认 State）。 */
    private val globalState: PluginSettings.State
        get() = runCatching { PluginSettings.getInstance().state }.getOrDefault(PluginSettings.State())

    private fun fieldTip(): String = MpSqlVisualizerBundle.message("settings.project.field.tip")

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        val st = projectSettings.state
        val global = globalState
        // T-UiNative T-UN-15：布局层迁 DSL v2；控件初始值经 applyToComponent 灌入。
        // T-RA-04：原先每个控件都挂 addActionListener { modified = true } 只为置标志位，
        // 标志位删除后这些监听器一并移除（isModified 逐项比对控件显示值，无需变更通知）。
        lateinit var checkCell: Cell<JBCheckBox>
        lateinit var comboCell: Cell<ComboBox<String>>
        lateinit var replaceParamsCell: Cell<JBCheckBox>
        lateinit var formatSqlCell: Cell<JBCheckBox>
        lateinit var injectLogicDeleteCell: Cell<JBCheckBox>
        lateinit var logicDeleteValueCell: Cell<JBTextField>
        lateinit var tablePrefixCell: Cell<JBTextField>
        lateinit var tableSuffixCell: Cell<JBTextField>
        lateinit var inferenceCell: Cell<ComboBox<InferenceOption>>
        lateinit var ignoredFieldsCell: Cell<JBTextField>
        val panel = panel {
            row {
                checkCell = checkBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"))
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
                        isSelected = st.useProjectSettings
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.dialect")) {
                comboCell = comboBox(DialectRegistry.dialects.map { it.name })
                    .applyToComponent {
                        selectedItem = st.dialect.ifBlank { global.dialect }
                    }
            }
            row {
                replaceParamsCell = checkBox(MpSqlVisualizerBundle.message("settings.replaceParams"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.replaceParams ?: global.replaceParams
                    }
            }
            row {
                formatSqlCell = checkBox(MpSqlVisualizerBundle.message("settings.formatSql"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.formatSql ?: global.formatSql
                    }
            }
            row {
                injectLogicDeleteCell = checkBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                        isSelected = st.injectLogicDelete ?: global.injectLogicDelete
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.logicDeleteValue")) {
                logicDeleteValueCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        // T-RA-04：此处原装自定义 PlainDocument 仅用于置 modified 标志位，
                        // 标志位删除后一并移除（默认文档已满足灌值/编辑需求）
                        text = st.logicDeleteValue.ifBlank { global.logicDeleteValue }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tablePrefix")) {
                tablePrefixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.tablePrefix.ifBlank { global.tablePrefix }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableSuffix")) {
                tableSuffixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.tableSuffix.ifBlank { global.tableSuffix }
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableNameInference")) {
                inferenceCell = comboBox(inferenceOptions().toList())
                    .applyToComponent {
                        // 按 equals 匹配 data class 选项（传裸值会静默选不中，见 PluginSettingsPanel M19 教训）
                        selectedItem = inferenceOptions().firstOrNull {
                            it.value == st.tableNameInference.ifBlank { global.tableNameInference }
                        } ?: inferenceOptions().first()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.ignoredFields")) {
                ignoredFieldsCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        text = st.ignoredFields.ifBlank { global.ignoredFields }
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
        val st = projectSettings.state
        val global = globalState
        // 平台 ComboBox 对同值 setSelectedItem 也派发 ActionEvent（262 实证）——
        // createComponent/reset 初始灌值即会误报「已修改」；故本方法一律逐项比对
        // （控件显示值 vs state+全局兜底语义），不依赖任何标志位（T-RA-04 已删除原标志位）。
        if ((useProjectCheck?.isSelected ?: st.useProjectSettings) != st.useProjectSettings) return true
        // 方言控件显示值回灌全局兜底（st.dialect 空时显示 global.dialect），比对须对齐同语义，
        // 否则「项目级方言未配置」在每次打开设置页都误报已修改（实证：reset 前未改动 isModified=true）
        if ((dialectCombo?.selectedItem as? String ?: st.dialect.ifBlank { global.dialect }) != st.dialect.ifBlank { global.dialect }) return true
        if (boolDiffers(replaceParamsCheck, st.replaceParams, global.replaceParams)) return true
        if (boolDiffers(formatSqlCheck, st.formatSql, global.formatSql)) return true
        if (boolDiffers(injectLogicDeleteCheck, st.injectLogicDelete, global.injectLogicDelete)) return true
        if (stringDiffers(logicDeleteValueField, st.logicDeleteValue.ifBlank { global.logicDeleteValue })) return true
        if (stringDiffers(tablePrefixField, st.tablePrefix.ifBlank { global.tablePrefix })) return true
        if (stringDiffers(tableSuffixField, st.tableSuffix.ifBlank { global.tableSuffix })) return true
        // 推断策略下拉按 data class 选项取值（控件未建时回落 state 值 → 恒等不误报）
        val inferenceShown = (inferenceCombo?.selectedItem as? InferenceOption)?.value
            ?: st.tableNameInference.ifBlank { global.tableNameInference }
        if (inferenceShown != st.tableNameInference.ifBlank { global.tableNameInference }) return true
        if (stringDiffers(ignoredFieldsField, st.ignoredFields.ifBlank { global.ignoredFields })) return true
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
        inferenceCombo?.let { st.tableNameInference = (it.selectedItem as? InferenceOption)?.value ?: "" }
        ignoredFieldsField?.let { st.ignoredFields = it.text.trim() }
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
        // 回灌按 data class 选项 equals 匹配（传裸值 setSelectedItem 静默 return，选中不生效）
        inferenceCombo?.selectedItem = inferenceOptions().firstOrNull {
            it.value == st.tableNameInference.ifBlank { global.tableNameInference }
        } ?: inferenceOptions().first()
        ignoredFieldsField?.text = st.ignoredFields.ifBlank { global.ignoredFields }
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
