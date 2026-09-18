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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

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
 *
 * T-DialectAutoInfer：方言下拉首项为「智能推断」哨兵——选中即项目级 dialectAuto=true，
 * 选具体方言名即 dialectAuto=false 并把值写进 dialect（state.dialectAuto=null 表示跟随全局，
 * 显示项按全局模式推导，见 [expectedDialectItem]）。哨兵项不复活 T-RA-04 删除的 modified
 * 标志位：apply 只按控件显示值甄别两种语义写 state，isModified 仍走纯逐项比对。
 *
 * T-ProjectSettingsScope P1（2026-09-18）：总开关关闭时 9 项**置灰并显示全局生效值**——
 * 原实现无条件灌「state 残值」而运行时按全局取值，页面所见与运行时所用不一致（用户看不出真实生效值）。
 * 四处必须联动（缺一处即回到原缺陷）：[refreshControls]（灌值 + 可用性）、[apply]（关闭态不写 9 项，
 * 否则把显示的全局值当成项目自定义值钉进 state）、[isModified]（关闭态只比总开关，否则显示全局值
 * vs state 残值恒报已修改）。
 *
 * T-ProjectSettingsScope P2：「重置为跟随全局」按钮清除 9 项残值（总开关不动，见 [resetToGlobal]）。
 */
class ProjectPluginSettingsConfigurable(private val project: Project) : Configurable {

    private val projectSettings = ProjectPluginSettings.getInstance(project)
    private var useProjectCheck: JBCheckBox? = null
    private var scopeStatusLabel: JLabel? = null
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
     * T-ProjectSettingsScope P1：9 项可覆盖字段的 Cell——总开关关闭时统一置灰。
     * 只用于「可用性」联动（值刷新走上面各控件字段，dispose 后为空安全）；dispose 时清空避免持有组件。
     */
    private val projectValueCells = mutableListOf<Cell<JComponent>>()

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

    /** T-DialectAutoInfer：「智能推断」哨兵项（createComponent 时按当前语言初始化）。 */
    private val autoItem: String = MpSqlVisualizerBundle.message("settings.dialect.auto")

    override fun getDisplayName(): String = MpSqlVisualizerBundle.message("plugin.name")

    override fun createComponent(): JComponent {
        val st = projectSettings.state
        // T-UiNative T-UN-15：布局层迁 DSL v2；控件初始值统一经 [refreshControls] 灌入
        // （灌值分支只此一处，防「四处联动」漏改）。
        // T-RA-04：原先每个控件都挂 addActionListener { modified = true } 只为置标志位，
        // 标志位删除后这些监听器一并移除（isModified 逐项比对控件显示值，无需变更通知）。
        // T-ProjectSettingsScope P1：总开关的监听器有真实职责（关闭态刷新 9 项可用性与显示值），
        // 与 T-RA-04 删除的「只为置假标志位」监听器不同，重新引入不违该定案。
        // T-DialectAutoInfer：auto 哨兵项=「智能推断」；项目级 dialectAuto null=跟随全局，
        // 显示项由 [expectedDialectItem] 按有效模式推导（与 reset/isModified 同源）。
        lateinit var checkCell: Cell<JBCheckBox>
        lateinit var scopeStatusCell: Cell<JLabel>
        lateinit var comboCell: Cell<ComboBox<String>>
        lateinit var replaceParamsCell: Cell<JBCheckBox>
        lateinit var formatSqlCell: Cell<JBCheckBox>
        lateinit var injectLogicDeleteCell: Cell<JBCheckBox>
        lateinit var logicDeleteValueCell: Cell<JBTextField>
        lateinit var tablePrefixCell: Cell<JBTextField>
        lateinit var tableSuffixCell: Cell<JBTextField>
        lateinit var inferenceCell: Cell<ComboBox<InferenceOption>>
        lateinit var ignoredFieldsCell: Cell<JBTextField>
        lateinit var resetCell: Cell<JButton>
        projectValueCells.clear()
        val panel = panel {
            row {
                checkCell = checkBox(MpSqlVisualizerBundle.message("settings.project.useProjectSettings"))
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.useProjectSettings.tip")
                        addActionListener { refreshControls(isSelected) }
                    }
            }
            row {
                // P1 可选增强：关闭态范围提示行（既有状态行形态：文案空时留一个空格占位，避免行高跳动）
                scopeStatusCell = label(" ")
            }
            row(MpSqlVisualizerBundle.message("settings.dialect")) {
                comboCell = comboBox(listOf(autoItem) + DialectRegistry.dialects.map { it.name })
            }
            row {
                replaceParamsCell = checkBox(MpSqlVisualizerBundle.message("settings.replaceParams"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                formatSqlCell = checkBox(MpSqlVisualizerBundle.message("settings.formatSql"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                injectLogicDeleteCell = checkBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.logicDeleteValue")) {
                logicDeleteValueCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                        // T-RA-04：此处原装自定义 PlainDocument 仅用于置 modified 标志位，
                        // 标志位删除后一并移除（默认文档已满足灌值/编辑需求）
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tablePrefix")) {
                tablePrefixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableSuffix")) {
                tableSuffixCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row(MpSqlVisualizerBundle.message("settings.tableNameInference")) {
                inferenceCell = comboBox(inferenceOptions().toList())
            }
            row(MpSqlVisualizerBundle.message("settings.ignoredFields")) {
                ignoredFieldsCell = textField()
                    .applyToComponent {
                        toolTipText = fieldTip()
                    }
            }
            row {
                // T-ProjectSettingsScope P2：形态照抄 PluginSettingsPanel 的「恢复默认颜色」
                // （JButton + bundle 文案 + tooltip + addActionListener，无双确认弹窗），
                // 置于所重置的 9 行之后。始终可用：关闭态正是残值在 UI 上唯一不可见的时刻。
                resetCell = button(MpSqlVisualizerBundle.message("settings.project.reset")) { resetToGlobal() }
                    .applyToComponent {
                        toolTipText = MpSqlVisualizerBundle.message("settings.project.reset.tip")
                    }
            }
        }
        useProjectCheck = checkCell.component
        scopeStatusLabel = scopeStatusCell.component
        dialectCombo = comboCell.component
        replaceParamsCheck = replaceParamsCell.component
        formatSqlCheck = formatSqlCell.component
        injectLogicDeleteCheck = injectLogicDeleteCell.component
        logicDeleteValueField = logicDeleteValueCell.component
        tablePrefixField = tablePrefixCell.component
        tableSuffixField = tableSuffixCell.component
        inferenceCombo = inferenceCell.component
        ignoredFieldsField = ignoredFieldsCell.component
        projectValueCells += listOf(
            comboCell,
            replaceParamsCell,
            formatSqlCell,
            injectLogicDeleteCell,
            logicDeleteValueCell,
            tablePrefixCell,
            tableSuffixCell,
            inferenceCell,
            ignoredFieldsCell,
        )
        refreshControls(st.useProjectSettings)
        return panel
    }

    /** 当前页面按「项目自定义值」还是「全局生效值」呈现：以总开关控件为准（控件未建时回落 state）。 */
    private fun showProjectNow(): Boolean =
        useProjectCheck?.isSelected ?: projectSettings.state.useProjectSettings

    /** 三态布尔项当前应显示的值（关闭态一律显示全局生效值）。 */
    private fun shownBool(stateValue: Boolean?, globalValue: Boolean, showProject: Boolean): Boolean =
        if (showProject) stateValue ?: globalValue else globalValue

    /** 文本项当前应显示的值（关闭态一律显示全局生效值）。 */
    private fun shownText(stateValue: String, globalValue: String, showProject: Boolean): String =
        if (showProject) stateValue.ifBlank { globalValue } else globalValue

    /**
     * T-ProjectSettingsScope P1：把「当前应显示的值 + 可编辑性」灌进控件。
     *
     * createComponent（初值）/ [reset] / 总开关监听器三处共用同一份表达式，防规格所点
     * 「四处联动只修一处」回潮。
     *
     * @param showProject true=按项目自定义值呈现；false=一律呈现全局生效值（页面所见即运行时所用）
     */
    private fun refreshControls(showProject: Boolean) {
        val st = projectSettings.state
        val global = globalState
        useProjectCheck?.isSelected = showProject
        scopeStatusLabel?.text = if (showProject) " " else MpSqlVisualizerBundle.message("settings.project.scope.global")
        dialectCombo?.selectedItem = expectedDialectItem(st, global, showProject)
        replaceParamsCheck?.isSelected = shownBool(st.replaceParams, global.replaceParams, showProject)
        formatSqlCheck?.isSelected = shownBool(st.formatSql, global.formatSql, showProject)
        injectLogicDeleteCheck?.isSelected = shownBool(st.injectLogicDelete, global.injectLogicDelete, showProject)
        logicDeleteValueField?.text = shownText(st.logicDeleteValue, global.logicDeleteValue, showProject)
        tablePrefixField?.text = shownText(st.tablePrefix, global.tablePrefix, showProject)
        tableSuffixField?.text = shownText(st.tableSuffix, global.tableSuffix, showProject)
        // 回灌按 data class 选项 equals 匹配（传裸值 setSelectedItem 静默 return，选中不生效）
        val inferenceShown = shownText(st.tableNameInference, global.tableNameInference, showProject)
        inferenceCombo?.selectedItem = inferenceOptions().firstOrNull { it.value == inferenceShown }
            ?: inferenceOptions().first()
        ignoredFieldsField?.text = shownText(st.ignoredFields, global.ignoredFields, showProject)
        // 关闭态置灰：这 9 行显示的是全局生效值，编辑它们没有落点（apply 不写回，见 [apply]）
        projectValueCells.forEach { it.enabled(showProject) }
    }

    /**
     * T-ProjectSettingsScope P2：「重置为跟随全局」——9 项归零/null（[ProjectPluginSettings.State.dialectAuto]
     * 一并归 null），**总开关保持不动**（按钮语义是「清除本项目自定义值」，不是「停用项目设置」）。
     *
     * 直接改 state 而非只改控件显示值：关闭态 [apply] 不写这 9 项，只改控件则残值永远清不掉；
     * 开启态把「显示的全局值」写进 state 又会变成硬覆盖值，与「跟随全局」语义相反。
     */
    private fun resetToGlobal() {
        val st = projectSettings.state
        st.dialect = ""
        st.dialectAuto = null
        st.replaceParams = null
        st.formatSql = null
        st.injectLogicDelete = null
        st.logicDeleteValue = ""
        st.tablePrefix = ""
        st.tableSuffix = ""
        st.tableNameInference = ""
        st.ignoredFields = ""
        refreshControls(showProjectNow())
    }

    /**
     * T-DialectAutoInfer + T-ProjectSettingsScope P1：方言下拉应显示的项。
     *
     * 开启态：有效模式为推断时显示哨兵项「智能推断」，否则显示项目手动值（空则回退全局手动值）。
     * 关闭态（`showProject=false`）：一律按全局推导（全局推断开则哨兵项，否则全局方言名），
     * 与运行时 [ProjectPluginSettings.effectiveDialectAuto] / [ProjectPluginSettings.effectiveDialect]
     * 的取值口径一致——页面所见即运行时所用。
     *
     * createComponent / reset / 总开关联动 / isModified 四处共用同一推导：T-RA-04 起 isModified 是
     * 「控件显示值 vs state」的逐项比对，哨兵项使显示值与 `state.dialect` 不再一一对应（auto 模式下
     * state.dialect 是保留的手动值、并非显示值），故必须由同一函数把 state 反推成显示项，否则哨兵项
     * 会让未改动的页面每次打开都误报已修改。
     */
    private fun expectedDialectItem(
        st: ProjectPluginSettings.State,
        global: PluginSettings.State,
        showProject: Boolean,
    ): String {
        val auto = if (showProject) st.dialectAuto ?: global.dialectAuto else global.dialectAuto
        if (auto) return autoItem
        return if (showProject) st.dialect.ifBlank { global.dialect } else global.dialect
    }

    /** 三态布尔比对：state 为 null 视为全局值，与勾选态比较（只在总开关开启态比对时调用）。 */
    private fun boolDiffers(check: JBCheckBox?, stateValue: Boolean?, globalValue: Boolean): Boolean {
        check ?: return false
        return check.isSelected != shownBool(stateValue, globalValue, showProject = true)
    }

    /** 文本比对：state 空视为全局值（只在总开关开启态比对时调用）。 */
    private fun stringDiffers(field: JBTextField?, stateValue: String, globalValue: String): Boolean {
        field ?: return false
        return field.text.trim() != shownText(stateValue, globalValue, showProject = true)
    }

    override fun isModified(): Boolean {
        val st = projectSettings.state
        val global = globalState
        val showProject = showProjectNow()
        // 平台 ComboBox 对同值 setSelectedItem 也派发 ActionEvent（262 实证）——
        // createComponent/reset 初始灌值即会误报「已修改」；故本方法一律逐项比对
        // （控件显示值 vs state+全局兜底语义），不依赖任何标志位（T-RA-04 已删除原标志位）。
        if (showProject != st.useProjectSettings) return true
        // T-ProjectSettingsScope P1：关闭态 9 项显示的是全局生效值、apply 也不写这 9 项，
        // 故不参与比对——若仍比对，「控件显示全局值 vs state 残值」恒报已修改
        // （误报形态同 T-SettingsTruth 6.3：页面一打开就是「已修改」）。
        if (!showProject) return false
        // 方言控件显示值回灌全局兜底（st.dialect 空时显示 global.dialect），比对须对齐同语义，
        // 否则「项目级方言未配置」在每次打开设置页都误报已修改（实证：reset 前未改动 isModified=true）
        // T-DialectAutoInfer：期望显示项含哨兵项分支，与 createComponent/reset 同源推导
        val dialectShown = dialectCombo?.selectedItem as? String ?: expectedDialectItem(st, global, true)
        if (dialectShown != expectedDialectItem(st, global, true)) return true
        if (boolDiffers(replaceParamsCheck, st.replaceParams, global.replaceParams)) return true
        if (boolDiffers(formatSqlCheck, st.formatSql, global.formatSql)) return true
        if (boolDiffers(injectLogicDeleteCheck, st.injectLogicDelete, global.injectLogicDelete)) return true
        if (stringDiffers(logicDeleteValueField, st.logicDeleteValue, global.logicDeleteValue)) return true
        if (stringDiffers(tablePrefixField, st.tablePrefix, global.tablePrefix)) return true
        if (stringDiffers(tableSuffixField, st.tableSuffix, global.tableSuffix)) return true
        // 推断策略下拉按 data class 选项取值（控件未建时回落 state 值 → 恒等不误报）
        val inferenceShown = (inferenceCombo?.selectedItem as? InferenceOption)?.value
            ?: st.tableNameInference.ifBlank { global.tableNameInference }
        if (inferenceShown != st.tableNameInference.ifBlank { global.tableNameInference }) return true
        if (stringDiffers(ignoredFieldsField, st.ignoredFields, global.ignoredFields)) return true
        return false
    }

    override fun apply() {
        val st = projectSettings.state
        val showProject = showProjectNow()
        st.useProjectSettings = showProject
        // T-ProjectSettingsScope P1：关闭态不写 9 项——此时控件显示的是**全局生效值**，
        // 无条件写回会把全局值（以及空/null 字段的全局兜底值）当成项目自定义值钉进 state：
        // 用户重开总开关后发现自定义值已被覆盖，或「跟随全局」被悄悄改成硬覆盖值。
        if (!showProject) return
        // T-DialectAutoInfer：哨兵项=项目级推断（true）；具体方言=手动（false + 值）。
        // auto 分支不改 st.dialect——手动值作为记忆保留，切回手动时不必重选
        (dialectCombo?.selectedItem as? String)?.let { selected ->
            if (selected == autoItem) {
                st.dialectAuto = true
            } else {
                st.dialectAuto = false
                st.dialect = selected
            }
        }
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
        // 回灌统一走 refreshControls（与 createComponent/总开关联动同源表达式）
        refreshControls(projectSettings.state.useProjectSettings)
    }

    override fun disposeUIResources() {
        useProjectCheck = null
        scopeStatusLabel = null
        dialectCombo = null
        replaceParamsCheck = null
        formatSqlCheck = null
        injectLogicDeleteCheck = null
        logicDeleteValueField = null
        tablePrefixField = null
        tableSuffixField = null
        inferenceCombo = null
        ignoredFieldsField = null
        projectValueCells.clear()
    }
}
