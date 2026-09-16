package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.log.SourceCaptureGuide
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor
import cn.song.mybatisplus.visualizer.util.PluginCacheManager
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBTextField
import javax.swing.ButtonGroup
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Component
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
import java.util.Locale
import javax.swing.event.DocumentListener

/**
 * 设置表单面板（T-M5-02，F-16 配置项 UI）。
 *
 * 展示并可编辑全部配置项；[apply]/[reset]/[isModified] 与 [PluginSettings] 联动，
 * 由 [SectionConfigurable] 子类（SQL 预览/表名映射/SQL 日志/界面与操作/关于）接入 IDE Settings
 * 框架（保存即生效）。
 *
 * M14 增强：
 *  - 「界面语言」下拉（默认简体中文）——保存后经 [rebuild] 重建表单即时生效；
 *  - 「快捷键」行——[KeyboardShortcutPanel] **内嵌编辑** Convert to SQL 快捷键，
 *    保存时直接写入 IDE 当前 Keymap（不再跳转）。
 *
 * M14.1（用户反馈）：
 *  - 移除悬停预览相关配置（autoPreview / hoverDelay 已随功能删除）；
 *  - 布局紧凑化（减小行距 + 底部 weighty 占位，消除内容区上方空白）。
 *
 * M16.2 增强：
 *  - 「表前缀」「表后缀」文本输入行（可添加/清除）——统一修饰预览 SQL 中的表名（全局语义）。
 */
class PluginSettingsPanel @JvmOverloads constructor(
    private val settings: PluginSettings,
    private val sections: Set<String> = SECTION_ALL,
) {
    companion object {
        const val SECTION_PREVIEW = "preview"
        const val SECTION_TABLE_MAPPING = "tableMapping"
        const val SECTION_CODE_GEN = "codeGen"
        const val SECTION_SQL_LOG = "sqlLog"
        const val SECTION_UI = "ui"
        const val SECTION_UPDATE_DIAGNOSTICS = "updateDiagnostics"
        val SECTION_ALL = setOf(SECTION_PREVIEW, SECTION_TABLE_MAPPING, SECTION_CODE_GEN, SECTION_SQL_LOG, SECTION_UI, SECTION_UPDATE_DIAGNOSTICS)
    }

    /**
     * 语言选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     *
     * M19 修复：必须为 data class（按 value 判等）——JDK17 `JComboBox.setSelectedItem` 会以
     * `equals` 在 model 中查找元素，**找不到则静默 return**（选中不生效、ActionEvent 不触发）；
     * 原无 equals 覆写（身份判等）时 `reset()`/写穿入口传入的新实例全部匹配失败——选中显示与
     * 写穿监听一并失效。
     */
    private data class LanguageOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun languageOptions(): Array<LanguageOption> = arrayOf(
        LanguageOption("zh", MpSqlVisualizerBundle.message("settings.language.zh")),
        LanguageOption("en", MpSqlVisualizerBundle.message("settings.language.en")),
        LanguageOption("AUTO", MpSqlVisualizerBundle.message("settings.language.auto")),
    )

    /**
     * 表名推断策略选项（值 + 当前语言下的显示文案；rebuild 时按新语言重建）。
     * 与 [LanguageOption] 同理必须为 data class（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    private data class InferenceOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun inferenceOptions(): Array<InferenceOption> = arrayOf(
        InferenceOption(PluginSettings.INFERENCE_SNAKE, MpSqlVisualizerBundle.message("settings.tableNameInference.snake")),
        InferenceOption(PluginSettings.INFERENCE_CAMEL, MpSqlVisualizerBundle.message("settings.tableNameInference.camel")),
    )

    /**
     * 反向转换 Wrapper 形态选项（T-M36.3）：值 + 当前语言下的显示文案。
     * 与 [LanguageOption] 同理必须为 data class（JDK17 `JComboBox.setSelectedItem` 按 equals 匹配）。
     */
    private data class ReverseFormatOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    /** T-CrudColor：CRUD 颜色标记形态下拉项。 */
    private data class CrudColorStyleOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun crudColorStyleOptions(): Array<CrudColorStyleOption> = arrayOf(
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BAR, MpSqlVisualizerBundle.message("settings.log.crudColor.style.bar")),
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_BACKGROUND, MpSqlVisualizerBundle.message("settings.log.crudColor.style.background")),
        CrudColorStyleOption(PluginSettings.CRUD_COLOR_STYLE_OFF, MpSqlVisualizerBundle.message("settings.log.crudColor.style.off")),
    )

    // ---------- T-CrudColor 颜色控件辅助 ----------

    /** 当前下拉选中的样式值（空选兜默认——与渲染器 styleOf 的兜底保持一致）。 */
    private fun selectedCrudColorStyle(): String =
        (crudColorStyleCombo.selectedItem as? CrudColorStyleOption)?.value
            ?: PluginSettings.CRUD_COLOR_STYLE_BAR

    /** 色板 → `#RRGGBB`（未选色 → 空串，渲染侧按默认色处理）。 */
    private fun colorHexOf(panel: ColorPanel): String {
        val c = panel.selectedColor ?: return ""
        return String.format(Locale.ENGLISH, "#%02X%02X%02X", c.red, c.green, c.blue)
    }

    /** `#RRGGBB` → Color（非法回退默认色，不抛）。 */
    private fun colorOf(hex: String): Color = SqlListTypeColor.parseHex(hex, Color(0x4A86E8))

    /** T-DisplayModeToSettings：SQL 列表显示模式下拉项（value=SqlListDisplayMode.name）。 */
    private data class ListDisplayModeOption(val value: String, val label: String) {
        override fun toString(): String = label
    }

    private fun listDisplayModeOptions(): Array<ListDisplayModeOption> = arrayOf(
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.SINGLE_LINE.name, MpSqlVisualizerBundle.message("log.list.displayMode.single")),
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.KEYWORD_WRAP.name, MpSqlVisualizerBundle.message("log.list.displayMode.keywordWrap")),
        ListDisplayModeOption(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.FORMATTED.name, MpSqlVisualizerBundle.message("log.list.displayMode.formatted")),
    )

    /** 当前下拉选中的显示模式值（空选兜默认——与 PluginSettings.sqlListDisplayMode 的兜底一致）。 */
    private fun selectedListDisplayMode(): String =
        (logDisplayModeCombo.selectedItem as? ListDisplayModeOption)?.value
            ?: PluginSettings.DEFAULT_LOG_LIST_DISPLAY_MODE

    private fun reverseFormatOptions(): Array<ReverseFormatOption> = arrayOf(
        ReverseFormatOption(PluginSettings.REVERSE_AUTO, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.auto")),
        ReverseFormatOption(PluginSettings.REVERSE_QUERY, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.query")),
        ReverseFormatOption(PluginSettings.REVERSE_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.lambda")),
        ReverseFormatOption(PluginSettings.REVERSE_WRAPPERS, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.wrappers")),
        ReverseFormatOption(PluginSettings.REVERSE_MPJ_LAMBDA, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.mpjLambda")),
    )

    // T-TenantLine 诊断日志：输出 UI Apply 决策链路。
    private val LOG = Logger.getInstance(PluginSettingsPanel::class.java)

    // ------------------------------------------------------------------
    // 表单控件
    // ------------------------------------------------------------------

    /** 方言下拉（选项来自方言注册表）。 */
    private val dialectCombo = JComboBox(DialectRegistry.dialects.map { it.name }.toTypedArray())

    /** 参数替换开关。 */
    private val replaceParamsCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.replaceParams"))

    /** 格式化开关。 */
    private val formatCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.formatSql"))

    /** 竖向排列 VALUES 行尾配对列名标注开关（T-VPairAnno，默认开）。 */
    private val verticalPairAnnotationCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.verticalPairAnnotation"))

    /** SQL 预览字段较多时自动折叠开关（T-AutoFold，默认关）。 */
    private val autoFoldCheck = JBCheckBox()

    /** 自动折叠字段数阈值输入框（T-AutoFold）：开关勾选才启用编辑（对齐自动保存联动范式）。 */
    private val autoFoldThresholdField = JBTextField(6)

    /** 字面量参数恢复开关（T-HistParam，默认开）：无 `?` 占位符的日志 SQL 把字符串字面量识别为参数。 */
    private val literalParamRecoveryCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.literalParamRecovery"))

    // ---------- M56 T-M56-08：增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

    /** 增强捕获开关（默认关）：开启时弹注入确认对话框（JEP 451 预告 + 数据留本机说明）。 */
    private val enhancedCaptureCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture"))

    /** 脱敏开关（默认开）：对疑似敏感形态打码，agent 采集层执行。 */
    private val enhancedDesensitizeCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize"))

    /** 增强捕获状态行：已挂载 pid=x / 已回落控制台捕获 / 连接中。 */
    private val enhancedStatusLabel = JBLabel(" ")

    /** gutter 预览 SQL 图标开关（M10）。 */
    private val gutterIconCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"))
    private val joinDiagramNativeCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.joinDiagramNative"))

    /** 逻辑删除注入开关（T-M15-07）：@TableLogic 实体查询/删除自动追加 `AND deleted = 0`。 */
    private val injectLogicDeleteCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
    private val logicDeleteValueField = JBTextField(6)

    /** 多租户注入开关（T-TenantLine）：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
    private val injectTenantLineCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"))

    /** 租户列名输入框（T-TenantLine）：默认 tenant_id；可按业务调整，如 org_id / shop_id。 */
    private val tenantColumnNameField = JBTextField(10)

    /** 租户列默认值输入框（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    private val tenantColumnDefaultField = JBTextField(6)

    /** INSERT 也追加租户列开关（F-6 Phase A）：INSERT 列清单追加租户列。 */
    private val injectTenantOnInsertCheck = JBCheckBox()

    /** 数据权限占位提示开关（F-6 Phase C）：显示 DataPermissionInterceptor 运行时差异提示。 */
    private val dataPermissionHintCheck = JBCheckBox()

    /** 数据权限模拟条件输入框（F-6 Phase C）：用户手动填写的 WHERE 片段。 */
    private val dataPermissionMockConditionField = JBTextField(20)

    /** JDBC 模板 SQL 字符串预览开关：queryForList/update 等方法的字符串 SQL 实参显示预览图标。 */
    private val jdbcSqlPreviewCheck = JBCheckBox()

    /** 表前缀输入框（T-M16.2-03）：统一加在所有表名前，全局语义（@TableName 显式表名同样生效）。 */
    private val tablePrefixField = JBTextField(12)

    /** 表后缀输入框（T-M16.2-03）：统一加在所有表名后，全局语义；清空即恢复原样。T-M19.4：多值 `;` 分隔。 */
    private val tableSuffixField = JBTextField(12)

    /** 表名推断策略下拉（T-M19.4）：无 @TableName 时类名→表名的推断方式（SNAKE 默认 / CAMEL 保留驼峰）。 */
    private val inferenceCombo = JComboBox<InferenceOption>()

    /** 忽略字段名输入框（T-M19.4）：`;` 分隔的 Java 字段名——命中字段不进 SQL（SELECT 展开/补全/参数对照）。 */
    private val ignoredFieldsField = JBTextField(12)

    /** 移除表名前缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个开头就移除哪个（首个匹配）。 */
    private val removeTablePrefixField = JBTextField(12)

    /** 移除表名后缀输入框（T-M19.4）：`;` 分隔，最终表名以哪个结尾就移除哪个（首个匹配）。 */
    private val removeTableSuffixField = JBTextField(12)

    /** 界面语言下拉（M14：默认简体中文；跟随系统 / English 可选）。 */
    private val languageCombo = JComboBox<LanguageOption>()

    /** 反向转换 Wrapper 形态下拉（T-M36.3：AUTO/QUERY/LAMBDA/MPJ_LAMBDA）。 */
    private val reverseFormatCombo = JComboBox<ReverseFormatOption>()

    /** 反向转换手动选择实体类开关（自动推断未命中/多表 JOIN 时弹实体选择弹窗）。 */
    private val manualEntityCheck = JBCheckBox()

    /** 插件缓存信息（统一条目总数——所有打开项目汇总；不细分缓存类型）。 */
    private val cacheInfoLabel = JBLabel(" ")

    /** 设置导入/导出结果反馈（20260907：行内提示替代模态弹窗——原弹窗叠在 Settings 对话框下，须关设置才可见）。 */
    private val statusLabel = JBLabel(" ")

    /** 插件缓存统一清除按钮（清全部缓存，下次使用自动重建）。 */
    private val clearCacheButton = JButton(MpSqlVisualizerBundle.message("settings.cache.clear"))

    // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------
    private val logSqlPrefixField = JBTextField(12)
    private val logParamPrefixField = JBTextField(12)
    private val logMixedPrefixCheck = JBCheckBox()
    private val logAutoCaptureCheck = JBCheckBox()
    private val logAutoOpenCheck = JBCheckBox()
    private val logStopOnToolWindowCloseCheck = JBCheckBox()
    private val logTimestampCheck = JBCheckBox()
    private val logTimestampFormatField = JBTextField(12)
    private val logTimestampSourceLogRadio = JBRadioButton()
    private val logTimestampSourceCaptureRadio = JBRadioButton()
    private val logTimestampSourceGroup = ButtonGroup()
    private val logFormatSqlCheck = JBCheckBox()
    private val logFilterKeywordsField = JBTextField(12)
    private val logDedupeField = JBTextField(12)
    private val logMaxParseField = JBTextField(12)
    private val logMaxCountField = JBTextField(12)
    private val logHibernateEnabledCheck = JBCheckBox()
    private val logHibernateSqlPrefixField = JBTextField(12)
    private val logHibernateParamPrefixField = JBTextField(12)
    private val logJdbcEnabledCheck = JBCheckBox()

    // ---------- F-43 自动保存历史 SQL ----------
    private val logAutoSaveCheck = JBCheckBox()
    private val logAutoSavePathField = JBTextField(20)
    private val logAutoSaveBrowseButton = JButton("...")
    private val logAutoSavePatternField = JBTextField(20)
    private val logAutoSavePatternPreview = JBLabel(" ")

    // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
    private val autoInjectInterceptorCheck = JBCheckBox()

    // ---------- O-1 日志窗口差异化（执行耗时 / 慢查询） ----------
    private val logTimePrefixField = JBTextField(12)
    private val slowQueryThresholdField = JBTextField(12)
    private val logShowDurationCheck = JBCheckBox()

    // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
    /** 标记形态下拉（色条 / 整行淡背景 / 关闭）。 */
    private val crudColorStyleCombo = ComboBox<CrudColorStyleOption>()

    /** T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史两列表共享一键）。 */
    private val logDisplayModeCombo = ComboBox<ListDisplayModeOption>()
    /** 四类型色板（平台原生 ColorPanel，点击弹出取色器）。 */
    private val crudColorSelectPanel = ColorPanel()
    private val crudColorInsertPanel = ColorPanel()
    private val crudColorUpdatePanel = ColorPanel()
    private val crudColorDeletePanel = ColorPanel()
    /** 一键恢复四色默认值。 */
    private val crudColorResetButton = JButton()

    /**
     * M19.1：语言写穿后「待应用」标记——语言已实时写入配置，但用户尚未点击「应用/确定」。
     * 平台 Apply 按钮启用 = `configurable.isModified()`，无此标记则写穿后 Apply 按钮灰掉
     * （用户实测反馈）；`apply()` 消费后清除。
     */
    private var languagePending = false

    /** 快捷键内嵌编辑（M14.1：录制式修改 Convert to SQL 的 KeyboardShortcut，保存写入 Keymap）。 */
    private val shortcutEditor = ShortcutEditor(ConvertToSqlAction.ACTION_ID)

    /** T-M41：SQL→Java 快捷键内嵌编辑（同款录制式，目标 action = ConvertSqlToJava）。 */
    private val reverseShortcutEditor = ShortcutEditor(ConvertSqlToJavaAction.ACTION_ID)

    /** 更新分区（M28：检查更新/下载安装/重启提示/启动自动检查开关）。 */
    private val updateSection = UpdateSection(settings)

    // 搜索框已移至主设置页面（PluginSettingsFolderConfigurable）

    private val rootPanel: JPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(8, 10, 8, 10)
    }

    // ------------------------------------------------------------------
    // 构造与表单布局
    // ------------------------------------------------------------------

    init {
        buildSections(rootPanel)
        reset()
        refreshCacheInfo()
        // M19（用户实测：设置切语言确定后重开仍原样——平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()，改完语言切到其他页再确定即被静默跳过；
        // M16.3 roaming 修复只覆盖磁盘持久化链路）：
        // 语言下拉**写穿式持久化**——选中即写入配置（不依赖 OK→apply 链路），并立即重建表单即时切换。
        languageCombo.addActionListener {
            val value = (languageCombo.selectedItem as? LanguageOption)?.value
            if (value != null && value != settings.state.language) {
                settings.state.language = value
                // M19.1：写穿后置「待应用」标记——平台 Apply 按钮启用 = isModified()，无标记则
                // 应用按钮灰掉（用户实测反馈）；标记在 apply() 时清除（应用/确定后恢复灰属正常）
                languagePending = true
                // M19.1：**invokeLater 调度重建**——ActionEvent 派发中途同步修改组件树时
                // UI 刷新不可靠（用户实测：左侧树即时变、右侧面板不变），延后到派发完成再重建
                SwingUtilities.invokeLater { rebuild() }
            }
        }
    }

    /**
     * 构建表单布局（T-M31.8：分组 + TitledSeparator 分隔线，对齐 SQL 预览面板左侧分区样式）。
     * 高频组在上、低频组在下、同类相聚；rebuild 时重新调用（语言切换后组标题与文案刷新）。
     */
    private fun buildSections(panel: JPanel) {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 6, 4, 6)
        }

        fun addRow(panel: JPanel, gbc: GridBagConstraints, label: String, component: JComponent) {
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(component, gbc)
            gbc.fill = GridBagConstraints.NONE
            gbc.gridy++
        }

        /** 分隔线 + 内容面板（无边框、无折叠）。 */
        fun sectionGroup(
            titleKey: String,
            content: (JPanel, GridBagConstraints) -> Unit,
        ) {
            val contentPanel = JPanel(GridBagLayout())
            val inner = GridBagConstraints().apply {
                gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; insets = Insets(2, 6, 2, 6)
            }
            content(contentPanel, inner)

            // 计算内容区标签最大宽度，统一列宽
            val layout = contentPanel.layout as GridBagLayout
            var maxLabelWidth = 0
            for (comp in contentPanel.components) {
                if (comp is JBLabel) {
                    maxLabelWidth = maxOf(maxLabelWidth, comp.preferredSize.width)
                }
            }
            if (maxLabelWidth > 0) {
                layout.columnWidths = intArrayOf(maxLabelWidth + 8, 0)
            }

            // 分隔线（仅横线，无边框）
            gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc)
            gbc.gridy++

            // 内容面板
            gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(contentPanel, gbc)
            gbc.gridy++
        }

        // 组 1：SQL 预览（默认展开）
        if (SECTION_PREVIEW in sections) {
            sectionGroup("settings.section.preview") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.dialect"), dialectCombo)
                addRow(p, g, "", replaceParamsCheck.apply { text = MpSqlVisualizerBundle.message("settings.replaceParams") })
                addRow(p, g, "", formatCheck.apply { text = MpSqlVisualizerBundle.message("settings.formatSql") })
                addRow(p, g, "", verticalPairAnnotationCheck.apply { text = MpSqlVisualizerBundle.message("settings.verticalPairAnnotation") })
                addRow(p, g, "", autoFoldCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.autoFold.enabled")
                    toolTipText = MpSqlVisualizerBundle.message("settings.autoFold.enabled.tip")
                    addActionListener { autoFoldThresholdField.isEnabled = isSelected }
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.autoFold.threshold"), autoFoldThresholdField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.autoFold.threshold.tip")
                })
                addRow(p, g, "", literalParamRecoveryCheck.apply { text = MpSqlVisualizerBundle.message("settings.literalParamRecovery") })
                addRow(p, g, "", injectLogicDeleteCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectLogicDelete") })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.logicDeleteValue"), logicDeleteValueField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.logicDeleteValue.tip")
                })
                addRow(p, g, "", injectTenantLineCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectTenantLine") })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnName"), tenantColumnNameField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnName.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnDefault"), tenantColumnDefaultField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnDefault.tip")
                })
                addRow(p, g, "", injectTenantOnInsertCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.injectTenantOnInsert")
                    toolTipText = MpSqlVisualizerBundle.message("settings.injectTenantOnInsert.tip")
                })
                addRow(p, g, "", dataPermissionHintCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.dataPermissionHint")
                    toolTipText = MpSqlVisualizerBundle.message("settings.dataPermissionHint.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition"), dataPermissionMockConditionField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.dataPermissionMockCondition.tip")
                })
                addRow(p, g, "", jdbcSqlPreviewCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.preview.jdbc")
                    toolTipText = MpSqlVisualizerBundle.message("settings.preview.jdbc.tip")
                })
            }
        }

        // 组 2：表名映射（默认折叠）
        if (SECTION_TABLE_MAPPING in sections) {
            sectionGroup("settings.section.tableMapping") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tablePrefix"), tablePrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.tablePrefix.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tableSuffix"), tableSuffixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.tableSuffix.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTablePrefix"), removeTablePrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.removeTablePrefix.tip")
                    emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.removeTableSuffix"), removeTableSuffixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.removeTableSuffix.tip")
                    emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.ignoredFields"), ignoredFieldsField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.ignoredFields.tip")
                    emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.tableNameInference"), inferenceCombo.apply {
                    model = DefaultComboBoxModel(inferenceOptions())
                    toolTipText = MpSqlVisualizerBundle.message("settings.tableNameInference.tip")
                    selectedItem = inferenceOptions().firstOrNull { it.value == settings.state.tableNameInference }
                        ?: inferenceOptions().first()
                })
            }
        }

        // 组 3：代码生成（默认折叠）
        if (SECTION_CODE_GEN in sections) {
            sectionGroup("settings.section.codeGen") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat"), reverseFormatCombo.apply {
                    model = DefaultComboBoxModel(reverseFormatOptions())
                    toolTipText = MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.tip")
                    selectedItem = reverseFormatOptions().firstOrNull { it.value == settings.state.reverseWrapperFormat }
                        ?: reverseFormatOptions().first()
                })
                addRow(p, g, "", manualEntityCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection")
                    toolTipText = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection.tip")
                })
            }
        }

        // 组 4：SQL 日志（分割线分隔 3 个子分组）
        if (SECTION_SQL_LOG in sections) {
            sectionGroup("settings.log.tab.mybatis") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.sqlPrefix"), logSqlPrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.sqlPrefix.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.paramPrefix"), logParamPrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.paramPrefix.tip")
                })
                addRow(p, g, "", logMixedPrefixCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.mixedPrefix")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.mixedPrefix.tip")
                })
                addRow(p, g, "", logAutoCaptureCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.autoCapture") })
                addRow(p, g, "", logAutoOpenCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.autoOpen") })
                addRow(p, g, "", logStopOnToolWindowCloseCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.stopOnToolWindowClose") })
                addRow(p, g, "", logTimestampCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.timestamp") })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampFormat"), logTimestampFormatField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampFormat.tip")
                })
                // 时间来源单选组（日志时间 / 捕获时间）
                logTimestampSourceLogRadio.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.timestampSource.log")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampSource.log.tip")
                }
                logTimestampSourceCaptureRadio.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.timestampSource.capture")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.timestampSource.capture.tip")
                }
                logTimestampSourceGroup.add(logTimestampSourceLogRadio)
                logTimestampSourceGroup.add(logTimestampSourceCaptureRadio)
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timestampSource"), JPanel().apply {
                    add(logTimestampSourceLogRadio)
                    add(logTimestampSourceCaptureRadio)
                })
                addRow(p, g, "", logFormatSqlCheck.apply { text = MpSqlVisualizerBundle.message("settings.log.formatSql") })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.filterKeywords"), logFilterKeywordsField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.filterKeywords.tip")
                    emptyText.text = MpSqlVisualizerBundle.message("settings.separatorHint")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.dedupeWindow"), logDedupeField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.dedupeWindow.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxParseLength"), logMaxParseField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.maxParseLength.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.maxCount"), logMaxCountField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.maxCount.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.timePrefix"), logTimePrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.timePrefix.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold"), slowQueryThresholdField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.slowQueryThreshold.tip")
                })
                addRow(p, g, "", logShowDurationCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.showDuration")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.showDuration.tip")
                })
            }

            // T-DisplayModeToSettings：SQL 列表显示模式（SQL 日志 + SQL 历史 两列表共享一键，设置页唯一入口）
            sectionGroup("settings.log.tab.list") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("log.list.displayMode"), logDisplayModeCombo.apply {
                    model = DefaultComboBoxModel(listDisplayModeOptions())
                    toolTipText = MpSqlVisualizerBundle.message("log.list.displayMode.tip")
                })
            }

            // T-CrudColor：SQL 列表 CRUD 类型颜色标记（样式 + 四色；不新增复选框，保持复选框台账不变）
            sectionGroup("settings.log.tab.crudColor") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.style"), crudColorStyleCombo.apply {
                    model = DefaultComboBoxModel(crudColorStyleOptions())
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.crudColor.style.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.select"), crudColorSelectPanel)
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.insert"), crudColorInsertPanel)
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.update"), crudColorUpdatePanel)
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.crudColor.delete"), crudColorDeletePanel)
                addRow(p, g, "", crudColorResetButton.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.crudColor.reset")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.crudColor.reset.tip")
                    addActionListener {
                        crudColorSelectPanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_SELECT)
                        crudColorInsertPanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_INSERT)
                        crudColorUpdatePanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_UPDATE)
                        crudColorDeletePanel.selectedColor = colorOf(PluginSettings.CRUD_COLOR_DEFAULT_DELETE)
                    }
                })
            }

            sectionGroup("settings.log.tab.orm") { p, g ->
                addRow(p, g, "", logHibernateEnabledCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.hibernate.enabled")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.enabled.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix"), logHibernateSqlPrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.sqlPrefix.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix"), logHibernateParamPrefixField.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.hibernate.paramPrefix.tip")
                })
                addRow(p, g, "", logJdbcEnabledCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.jdbc")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.jdbc.tip")
                })
            }

            sectionGroup("settings.log.tab.autoSave") { p, g ->
                addRow(p, g, "", logAutoSaveCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.autoSave")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.tip")
                    addActionListener {
                        val enabled = isSelected
                        logAutoSavePathField.isEnabled = enabled
                        logAutoSaveBrowseButton.isEnabled = enabled
                        logAutoSavePatternField.isEnabled = enabled
                        updateAutoSavePreview()
                    }
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.path"),
                    JPanel(BorderLayout(4, 0)).apply {
                        isOpaque = false
                        add(logAutoSavePathField.apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.path.tip")
                            emptyText.text = MpSqlVisualizerBundle.message("settings.log.autoSave.path.empty")
                        }, BorderLayout.CENTER)
                        add(logAutoSaveBrowseButton.apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip")
                            addActionListener { browseAutoSavePath() }
                        }, BorderLayout.EAST)
                    }
                )
                addRow(p, g, MpSqlVisualizerBundle.message("settings.log.autoSave.pattern"),
                    JPanel(BorderLayout(4, 0)).apply {
                        isOpaque = false
                        add(logAutoSavePatternField.apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.tip")
                            emptyText.text = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN
                            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateAutoSavePreview()
                            })
                        }, BorderLayout.CENTER)
                        add(JButton(MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset")).apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.reset.tip")
                            addActionListener { logAutoSavePatternField.text = PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
                        }, BorderLayout.EAST)
                    }
                )
                addRow(p, g, "", logAutoSavePatternPreview.apply {
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.autoSave.pattern.preview.tip")
                })
            }

            // F-44-7：SqlSourceInterceptor 自动注入（源码位置捕获）
            sectionGroup("settings.log.tab.sourceCapture") { p, g ->
                addRow(p, g, "", autoInjectInterceptorCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor")
                    toolTipText = MpSqlVisualizerBundle.message("settings.log.autoInjectInterceptor.tip")
                    addActionListener {
                        if (isSelected) {
                            // 触发注入
                            triggerInterceptorInjection()
                        }
                    }
                })
                // M56 T-M56-08：增强捕获设置（捕获通道类设置与源码位置捕获同组）
                addRow(p, g, "", enhancedCaptureCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.enhancedCapture")
                    addActionListener {
                        if (isSelected && !PluginSettings.getInstance().state.enhancedCaptureConfirmed) {
                            // T-M56-08：首次启用弹注入确认（确认记忆持久化，重开不弹）
                            if (EnhancedCaptureConfirmDialog.confirm(null)) {
                                PluginSettings.getInstance().state.enhancedCaptureConfirmed = true
                            } else {
                                isSelected = false
                            }
                        }
                    }
                })
                addRow(p, g, "", enhancedDesensitizeCheck.apply { text = MpSqlVisualizerBundle.message("settings.enhancedCapture.desensitize") })
                addRow(p, g, "", enhancedStatusLabel)
            }
        }

        // 组 5：界面与操作（默认展开）
        if (SECTION_UI in sections) {
            sectionGroup("settings.section.ui") { p, g ->
                addRow(p, g, "", gutterIconCheck.apply { text = MpSqlVisualizerBundle.message("settings.showGutterIcon") })
                addRow(p, g, "", joinDiagramNativeCheck.apply {
                    text = MpSqlVisualizerBundle.message("settings.joinDiagramNative")
                    toolTipText = MpSqlVisualizerBundle.message("settings.joinDiagramNative.tip")
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.language"), languageCombo.apply {
                    model = DefaultComboBoxModel(languageOptions())
                    selectedItem = languageOptions().firstOrNull { it.value == settings.state.language }
                        ?: languageOptions().first()
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut"), shortcutEditor.apply { updateTexts() })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava"), reverseShortcutEditor.apply { updateTexts() })
            }
        }

        // 组 6：更新与诊断（默认折叠）
        if (SECTION_UPDATE_DIAGNOSTICS in sections) {
            sectionGroup("settings.section.updateDiagnostics") { p, g ->
                addRow(p, g, MpSqlVisualizerBundle.message("settings.cache.label"),
                    JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                        isOpaque = false
                        add(cacheInfoLabel)
                        add(clearCacheButton.apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.cache.tip")
                            addActionListener {
                                ProjectManager.getInstance().openProjects.forEach { PluginCacheManager.clearAll(it) }
                                cacheInfoLabel.text = MpSqlVisualizerBundle.message("settings.cache.cleared")
                            }
                        })
                    })
                g.anchor = GridBagConstraints.NORTHWEST
                addRow(p, g, MpSqlVisualizerBundle.message("settings.update.versionInfo"), updateSection.apply { updateTexts() })
                g.anchor = GridBagConstraints.WEST
                addRow(p, g, MpSqlVisualizerBundle.message("settings.buildNumber"), JPanel(BorderLayout(8, 0)).apply {
                    add(JBLabel().apply {
                        text = PluginBuildInfo.displayText()
                        toolTipText = MpSqlVisualizerBundle.message("settings.buildNumber.tip")
                    }, BorderLayout.CENTER)
                    add(JButton(MpSqlVisualizerBundle.message("settings.buildNumber.copy")).apply {
                        isEnabled = PluginBuildInfo.mpvBuild() != null
                        addActionListener {
                            UiTextClipboard.copy(PluginBuildInfo.displayText())
                            val original = text
                            text = MpSqlVisualizerBundle.message("settings.buildNumber.copied")
                            javax.swing.Timer(1200) { text = original }.apply { isRepeats = false; start() }
                        }
                    }, BorderLayout.EAST)
                })
                addRow(p, g, MpSqlVisualizerBundle.message("settings.pluginSettings"), JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        isOpaque = false
                        add(JButton(MpSqlVisualizerBundle.message("settings.import")).apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.import.tip")
                            addActionListener { importSettings() }
                        })
                        add(JButton(MpSqlVisualizerBundle.message("settings.export")).apply {
                            toolTipText = MpSqlVisualizerBundle.message("settings.export.tip")
                            addActionListener { exportSettings() }
                        })
                    }, BorderLayout.CENTER)
                    add(statusLabel, BorderLayout.EAST)
                })
            }
        }

        // 底部 weighty 占位（内容顶置）
        gbc.gridx = 0; gbc.gridy++; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)
    }

    /**
     * Phase 3：实时过滤配置行。
     *
     * - 空搜索框：恢复全部行可见 + 恢复折叠状态。
     * - 非空搜索框：隐藏不匹配的行，展开包含匹配行的折叠面板。
     * 匹配范围：行标签文本 + 组件 tooltip。
     */
    /**
     * 快捷键内嵌编辑行（M14.1）：只读显示当前快捷键 + 「修改」按钮弹出录制对话框，
     * 捕获按键组合后回填（保存时由 [PluginSettingsPanel.apply] 写入 Keymap）。
     * 不依赖 impl 包（KeyboardShortcutPanel 为包私有），全公开 API 实现。
     *
     * T-M41：actionId 参数化——同一组件服务多个 action（Convert to SQL / SQL→Java），
     * 冲突检测与保存写入均以各自 actionId 为准。internal：测试访问器返回类型（行存在/修改检测）。
     */
    internal inner class ShortcutEditor(
        private val actionId: String,
    ) : JPanel(BorderLayout(6, 0)) {

        var shortcut: KeyboardShortcut? = null
            set(value) {
                field = value
                textField.text = value?.let { KeymapUtil.getShortcutText(it) } ?: "—"
            }

        /** M14.4：本次录制是否「强制使用」（冲突时抢占移除占用者；保存时消费后复位）。 */
        var forceOnApply: Boolean = false

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

        /** M19.2：语言切换后刷新自身文案（tooltip/按钮；buildRows 每轮调用——字段实例文本构造时固化）。 */
        fun updateTexts() {
            textField.toolTipText = MpSqlVisualizerBundle.message("settings.shortcut.tip")
            editButton.text = MpSqlVisualizerBundle.message("settings.shortcut.edit")
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
            // M14.2 焦点修复：isVisible=true 为阻塞调用，必须在显示前调度聚焦，
            // 否则输入框无焦点导致按键捕获不到（第二次修改不生效的根因）
            SwingUtilities.invokeLater { capture.requestFocusInWindow() }
            capture.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> dialog.dispose()
                        KeyEvent.VK_ENTER -> dialog.dispose()
                        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META -> Unit // 纯修饰键忽略
                        else -> {
                            val stroke = KeyStroke.getKeyStrokeForEvent(e)
                            val newShortcut = KeyboardShortcut(stroke, null)
                            // M14.2/14.4 冲突处理：仅冲突时弹窗，且「强制使用」为独立按钮（与普通确认区分）——
                            //  使用：绑定但不抢占（保留占用者，可能不生效）
                            //  强制使用：抢占移除占用者，保证始终生效
                            val conflict = conflictActionName(newShortcut)
                            if (conflict != null) {
                                val options = arrayOf(
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.use"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.force"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.cancel"),
                                )
                                // 与原无 project 行为一致；返回选项索引 Int（0=Yes/使用, 1=No/强制使用, 2=Cancel/取消）。
                                // T-M30：跨版本签名差异——showIdeaMessageDialog 已弃用，改用 MessageDialogBuilder.yesNoCancel
                                // （2024.3+ 可用）。guessWindowAndAsk() 返回 int：0=Yes(第一个按钮), 1=No(第二个), 2=Cancel(第三个)。
                                val choice = MessageDialogBuilder.yesNoCancel(
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict.title"),
                                    MpSqlVisualizerBundle.message("settings.shortcut.conflict", conflict)
                                )
                                    .yesText(options[0])
                                    .noText(options[1])
                                    .cancelText(options[2])
                                    .icon(Messages.getWarningIcon())
                                    .guessWindowAndAsk()
                                when (choice) {
                                    0 -> { // 使用（普通）：不抢占
                                        forceOnApply = false
                                        shortcut = newShortcut
                                        dialog.dispose()
                                    }
                                    1 -> { // 强制使用：抢占，保证生效
                                        forceOnApply = true
                                        shortcut = newShortcut
                                        dialog.dispose()
                                    }
                                    else -> Unit // 取消
                                }
                            } else {
                                forceOnApply = false
                                shortcut = newShortcut
                                dialog.dispose()
                            }
                        }
                    }
                }
            })
            dialog.isVisible = true
        }

        /** 若新快捷键已被其他 action 占用，返回占用者名称（可读 action 文本；环境异常兜底 null）。 */
        private fun conflictActionName(newShortcut: KeyboardShortcut): String? = runCatching {
            val keymap = KeymapManager.getInstance().activeKeymap
            keymap.getActionIds()
                .filter { it != actionId }
                .firstOrNull { actionId -> keymap.getShortcuts(actionId).any { it == newShortcut } }
                ?.let { actionId ->
                    ActionManager.getInstance().getAction(actionId)?.templatePresentation?.text ?: actionId
                }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 快捷键读写（M14.1：内嵌编辑，直接写当前 Keymap）
    // ------------------------------------------------------------------

    /** 指定 action 的当前键盘快捷键（activeKeymap 中第一个 KeyboardShortcut；环境异常兜底 null）。 */
    private fun currentShortcut(actionId: String): KeyboardShortcut? = runCatching {
        KeymapManager.getInstance().activeKeymap
            .getShortcuts(actionId)
            .filterIsInstance<KeyboardShortcut>()
            .firstOrNull()
    }.getOrNull()

    /**
     * 应用快捷键（M14.3/14.4）：
     *  - 普通「使用」：仅绑定给本插件（移除自身旧绑定，不动其他 action）；
     *  - 「强制使用」：额外从**所有其他 action**（含 IDE 默认 keymap 继承绑定）移除该组合，保证独占始终生效。
     *
     * 抢占原理：IDEA keymap 支持"负绑定"——[Keymap.removeShortcut] 对继承自父级 keymap（$default）的
     * 快捷键会在当前（用户级）keymap 写入禁用覆盖，与 IDE Keymap 设置页"绑定冲突键时自动移除旧绑定"一致。
     */
    private fun applyShortcut(editor: ShortcutEditor, actionId: String) {
        runCatching {
            val keymap = KeymapManager.getInstance().activeKeymap
            // 1) 清理本 action 的旧键盘快捷键
            keymap.getShortcuts(actionId).filterIsInstance<KeyboardShortcut>()
                .forEach { keymap.removeShortcut(actionId, it) }
            // 2) 绑定新组合；「强制使用」时先抢占（从所有其他 action 移除，含默认继承绑定）
            val newShortcut = editor.shortcut
            if (newShortcut != null) {
                if (editor.forceOnApply) {
                    keymap.getActionIds().filter { it != actionId }.forEach { other ->
                        keymap.getShortcuts(other).filter { it == newShortcut }
                            .forEach { keymap.removeShortcut(other, it) }
                    }
                }
                keymap.addShortcut(actionId, newShortcut)
                editor.forceOnApply = false
            }
        }
    }

    /**
     * 语言切换后重建表单（设置页自身文案即时刷新；控件值从配置回读）。
     */
    fun rebuild() {
        rootPanel.removeAll()
        buildSections(rootPanel)
        reset()
        refreshCacheInfo()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    /**
     * M19：语言写穿入口（测试用）——选中语言值，触发与用户下拉选择**同一**写穿路径
     * （ActionListener → 立即写入配置 + 重建表单）。
     */
    internal fun selectLanguage(value: String) {
        languageCombo.selectedItem = languageOptions().firstOrNull { it.value == value } ?: return
        // selectedItem 变化会同步触发 ActionListener，写穿与 rebuild 由监听统一处理
    }

    /** T-xx：反向转换形态选择入口（测试用）——与下拉选择同一路径（selectedItem 按 equals 匹配）。 */
    internal fun selectReverseFormat(value: String) {
        reverseFormatCombo.selectedItem = reverseFormatOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-CrudColor：标记形态下拉选择入口（测试用）——与用户选择同一路径。 */
    internal fun selectCrudColorStyle(value: String) {
        crudColorStyleCombo.selectedItem = crudColorStyleOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-DisplayModeToSettings：显示模式下拉选择入口（测试用）——与用户选择同一路径。 */
    internal fun selectListDisplayMode(value: String) {
        logDisplayModeCombo.selectedItem = listDisplayModeOptions().firstOrNull { it.value == value } ?: return
    }

    /** T-DisplayModeToSettings：当前下拉选中值（测试断言 reset 回填用）。 */
    internal fun currentListDisplayModeForTest(): String = selectedListDisplayMode()

    /** T-CrudColor：四色色板设置入口（测试用）——与用户取色同一路径。 */
    internal fun setCrudColor(which: String, hex: String) {
        val color = colorOf(hex)
        when (which) {
            "select" -> crudColorSelectPanel.selectedColor = color
            "insert" -> crudColorInsertPanel.selectedColor = color
            "update" -> crudColorUpdatePanel.selectedColor = color
            "delete" -> crudColorDeletePanel.selectedColor = color
        }
    }

    /** T-M41 测试访问器：SQL→Java 快捷键编辑行（断言行存在 / 修改检测 / apply 写入 Keymap）。 */
    internal fun reverseShortcutEditorForTest(): ShortcutEditor = reverseShortcutEditor

    /** T-xx：启动自动监听复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setLogAutoCaptureCheck(checked: Boolean) {
        logAutoCaptureCheck.isSelected = checked
    }

    /** 手动选择实体类复选框设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setManualEntityCheck(checked: Boolean) {
        manualEntityCheck.isSelected = checked
    }

    /** T-TenantLine：多租户注入开关设置入口（测试用）——与用户勾选同一路径（isModified/apply 检测）。 */
    internal fun setInjectTenantLineCheck(checked: Boolean) {
        injectTenantLineCheck.isSelected = checked
    }

    /** T-TenantLine：租户列名输入框设置入口（测试用）——与用户输入同一路径（isModified/apply 检测）。 */
    internal fun setTenantColumnName(value: String) {
        tenantColumnNameField.text = value
    }

    /**
     * 刷新插件缓存信息（大小 + 条目数，所有打开项目汇总——含反查/解析/Wrapper 预览三类缓存；
     * 面板构造与清除按钮共用）。
     */
    private fun refreshCacheInfo() {
        val projects = ProjectManager.getInstance().openProjects.toList()
        val total = PluginCacheManager.totalSizeBytes(projects)
        cacheInfoLabel.text = MpSqlVisualizerBundle.message(
            "settings.cache.info",
            PluginCacheManager.formatSize(total),
            PluginCacheManager.entryCount(projects),
        )
    }

    /** 测试访问器：缓存信息文本（断言展示与清除后刷新）。 */
    internal fun cacheInfoForTest(): String = cacheInfoLabel.text

    /** 测试访问器：清除缓存按钮（断言存在与点击行为）。 */
    internal fun clearCacheButtonForTest(): JButton = clearCacheButton

    /** 测试访问器：导入/导出行内反馈（20260907——文本/前景色/tooltip 三断言面）。 */
    internal fun statusForTest(): JBLabel = statusLabel

    // ------------------------------------------------------------------
    // F-44-7：SqlSourceInterceptor 自动注入
    // ------------------------------------------------------------------

    /**
     * 触发拦截器注入（用户勾选开关后调用）。
     *
     * T-JumpCapture：注入编排与四类结果弹窗收敛到 [SourceCaptureGuide]（与「跳转源码」
     * 引导弹框共用，防重入亦在其中）；面板侧仅保留勾选回滚——仅它持有 checkbox。
     */
    private fun triggerInterceptorInjection() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            Messages.showWarningDialog(
                null,
                MpSqlVisualizerBundle.message("log.inject.noProject"),
                MpSqlVisualizerBundle.message("log.inject.title"),
            )
            autoInjectInterceptorCheck.isSelected = false
            return
        }
        SourceCaptureGuide.runInjection(project) { result ->
            val success = SourceCaptureGuide.showInjectionResult(project, result)
            if (!success) autoInjectInterceptorCheck.isSelected = false
        }
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
     *
     * M19.1：语言**写穿**后表单与配置一致，但语言变更尚未「应用」——`languagePending`
     * 使 `isModified()` 保持 true，Apply 按钮在写穿后仍可点击（平台 Apply 启用 = isModified()）。
     */
    fun isModified(): Boolean {
        val s = settings.state
        return languagePending
            || (SECTION_PREVIEW in sections && isModifiedPreview(s))
            || (SECTION_TABLE_MAPPING in sections && isModifiedTableMapping(s))
            || (SECTION_CODE_GEN in sections && isModifiedCodeGen(s))
            || (SECTION_SQL_LOG in sections && isModifiedSqlLog(s))
            || (SECTION_UI in sections && isModifiedUi(s))
            || (SECTION_UPDATE_DIAGNOSTICS in sections && updateSection.autoCheckCheck.isSelected != s.autoCheckUpdate)
    }

    private fun isModifiedPreview(s: PluginSettings.State): Boolean =
        dialectCombo.selectedItem != s.dialect
            || replaceParamsCheck.isSelected != s.replaceParams
            || formatCheck.isSelected != s.formatSql
            || verticalPairAnnotationCheck.isSelected != s.verticalPairAnnotation
            || autoFoldCheck.isSelected != s.autoFoldEnabled
            || autoFoldThresholdField.text.trim().toIntOrNull() != s.autoFoldFieldThreshold
            || literalParamRecoveryCheck.isSelected != s.literalParamRecovery
            || injectLogicDeleteCheck.isSelected != s.injectLogicDelete
            || logicDeleteValueField.text.trim() != s.logicDeleteValue
            || injectTenantLineCheck.isSelected != s.injectTenantLine
            || tenantColumnNameField.text.trim() != s.tenantColumnName
            || tenantColumnDefaultField.text.trim() != s.tenantColumnDefault
            || injectTenantOnInsertCheck.isSelected != s.injectTenantOnInsert
            || dataPermissionHintCheck.isSelected != s.dataPermissionHint
            || dataPermissionMockConditionField.text.trim() != s.dataPermissionMockCondition
            || jdbcSqlPreviewCheck.isSelected != s.jdbcSqlPreviewEnabled

    private fun isModifiedTableMapping(s: PluginSettings.State): Boolean =
        tablePrefixField.text.trim() != s.tablePrefix
            || tableSuffixField.text.trim() != s.tableSuffix
            || (inferenceCombo.selectedItem as? InferenceOption)?.value != s.tableNameInference
            || ignoredFieldsField.text.trim() != s.ignoredFields
            || removeTablePrefixField.text.trim() != s.removeTablePrefix
            || removeTableSuffixField.text.trim() != s.removeTableSuffix

    private fun isModifiedCodeGen(s: PluginSettings.State): Boolean =
        (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value != s.reverseWrapperFormat
            || manualEntityCheck.isSelected != s.reverseManualEntitySelection

    private fun isModifiedSqlLog(s: PluginSettings.State): Boolean =
        logSqlPrefixField.text.trim() != s.logSqlPrefix
            || logParamPrefixField.text.trim() != s.logParamPrefix
            || logMixedPrefixCheck.isSelected != s.logMixedPrefix
            || logAutoCaptureCheck.isSelected != s.logAutoCaptureOnStart
            || logAutoOpenCheck.isSelected != s.logAutoOpenOnCapture
            || logStopOnToolWindowCloseCheck.isSelected != s.logStopOnToolWindowClose
            || logTimestampCheck.isSelected != s.logTimestampEnabled
            || logTimestampFormatField.text.trim() != s.logTimestampFormat
            || (if (logTimestampSourceLogRadio.isSelected) "log" else "capture") != s.logTimestampSource
            || logFormatSqlCheck.isSelected != s.logFormatSql
            || logFilterKeywordsField.text.trim() != s.logFilterKeywords
            || logDedupeField.text.trim().toLongOrNull() != s.logDedupeWindowMs
            || logMaxParseField.text.trim().toIntOrNull() != s.logMaxParseLength
            || logMaxCountField.text.trim().toIntOrNull() != s.logMaxCount
            || logTimePrefixField.text.trim() != s.logTimePrefix
            || slowQueryThresholdField.text.trim().toLongOrNull() != s.slowQueryThresholdMs
            || logShowDurationCheck.isSelected != s.showDurationInLogList
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，改动判定随 sqlLog 分区
            || enhancedCaptureCheck.isSelected != s.enhancedCaptureEnabled
            || enhancedDesensitizeCheck.isSelected != s.enhancedCaptureDesensitize
            || logHibernateEnabledCheck.isSelected != s.logHibernateEnabled
            || logHibernateSqlPrefixField.text.trim() != s.logHibernateSqlPrefix
            || logHibernateParamPrefixField.text.trim() != s.logHibernateParamPrefix
            || logJdbcEnabledCheck.isSelected != s.logJdbcEnabled
            || logAutoSaveCheck.isSelected != s.logAutoSaveEnabled
            || logAutoSavePathField.text.trim() != s.logAutoSavePath
            || logAutoSavePatternField.text.trim() != s.logAutoSaveFileNamePattern
            || autoInjectInterceptorCheck.isSelected != s.autoInjectSqlSourceInterceptor
            // T-CrudColor：仅改样式或仅改某个颜色也必须能启用 Apply
            // （reverseWrapperFormat 漏挂 isModified 导致改动被平台静默丢弃的假开关事故，此处防同类回归）
            || selectedCrudColorStyle() != s.crudColorStyle
            || selectedListDisplayMode() != s.logListSqlDisplayMode
            || colorHexOf(crudColorSelectPanel) != s.crudColorSelect
            || colorHexOf(crudColorInsertPanel) != s.crudColorInsert
            || colorHexOf(crudColorUpdatePanel) != s.crudColorUpdate
            || colorHexOf(crudColorDeletePanel) != s.crudColorDelete

    private fun isModifiedUi(s: PluginSettings.State): Boolean =
        gutterIconCheck.isSelected != s.showGutterIcon
            || joinDiagramNativeCheck.isSelected != s.joinDiagramNative
            || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
            || shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)
            || reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 [rebuild] 刷新设置页文案）
     */
    fun apply(): Boolean {
        val s = settings.state
        // M19.1：语言变更判定包含「待应用」标记——写穿后（combo 与 state 已一致）仍报告一次变更，
        // 触发调用方 rebuild；消费后清除标记（后续 apply 幂等）
        val languageChanged = languagePending || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
        if (SECTION_PREVIEW in sections) {
            s.dialect = dialectCombo.selectedItem as? String ?: PluginSettings.DEFAULT_DIALECT
            s.replaceParams = replaceParamsCheck.isSelected
            s.formatSql = formatCheck.isSelected
            s.verticalPairAnnotation = verticalPairAnnotationCheck.isSelected
            s.autoFoldEnabled = autoFoldCheck.isSelected
            s.autoFoldFieldThreshold = autoFoldThresholdField.text.trim().toIntOrNull()
                ?: PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD
            s.literalParamRecovery = literalParamRecoveryCheck.isSelected
            s.injectLogicDelete = injectLogicDeleteCheck.isSelected
            s.logicDeleteValue = logicDeleteValueField.text.trim()
            s.injectTenantLine = injectTenantLineCheck.isSelected
            s.tenantColumnName = tenantColumnNameField.text.trim()
            s.tenantColumnDefault = tenantColumnDefaultField.text.trim()
            s.injectTenantOnInsert = injectTenantOnInsertCheck.isSelected
            s.dataPermissionHint = dataPermissionHintCheck.isSelected
            s.dataPermissionMockCondition = dataPermissionMockConditionField.text.trim()
            s.jdbcSqlPreviewEnabled = jdbcSqlPreviewCheck.isSelected
        }
        if (SECTION_TABLE_MAPPING in sections) {
            s.tablePrefix = tablePrefixField.text.trim()
            s.tableSuffix = tableSuffixField.text.trim()
            s.tableNameInference = (inferenceCombo.selectedItem as? InferenceOption)?.value ?: PluginSettings.INFERENCE_SNAKE
            s.ignoredFields = ignoredFieldsField.text.trim()
            s.removeTablePrefix = removeTablePrefixField.text.trim()
            s.removeTableSuffix = removeTableSuffixField.text.trim()
        }
        if (SECTION_CODE_GEN in sections) {
            s.reverseWrapperFormat = (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value ?: PluginSettings.REVERSE_AUTO
            s.reverseManualEntitySelection = manualEntityCheck.isSelected
        }
        if (SECTION_SQL_LOG in sections) {
            s.logSqlPrefix = logSqlPrefixField.text.trim()
            s.logParamPrefix = logParamPrefixField.text.trim()
            s.logMixedPrefix = logMixedPrefixCheck.isSelected
            s.logAutoCaptureOnStart = logAutoCaptureCheck.isSelected
            s.logAutoOpenOnCapture = logAutoOpenCheck.isSelected
            s.logStopOnToolWindowClose = logStopOnToolWindowCloseCheck.isSelected
            s.logTimestampEnabled = logTimestampCheck.isSelected
            s.logTimestampFormat = logTimestampFormatField.text.trim()
            s.logTimestampSource = if (logTimestampSourceLogRadio.isSelected) "log" else "capture"
            s.logFormatSql = logFormatSqlCheck.isSelected
            s.logFilterKeywords = logFilterKeywordsField.text.trim()
            s.logDedupeWindowMs = logDedupeField.text.trim().toLongOrNull() ?: PluginSettings.DEFAULT_LOG_DEDUPE_WINDOW_MS
            s.logMaxParseLength = logMaxParseField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH
            s.logMaxCount = logMaxCountField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_MAX_COUNT
            s.logTimePrefix = logTimePrefixField.text.trim()
            s.slowQueryThresholdMs = slowQueryThresholdField.text.trim().toLongOrNull()
                ?: PluginSettings.DEFAULT_SLOW_QUERY_THRESHOLD_MS
            s.showDurationInLogList = logShowDurationCheck.isSelected
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            s.enhancedCaptureEnabled = enhancedCaptureCheck.isSelected
            s.enhancedCaptureDesensitize = enhancedDesensitizeCheck.isSelected
            s.logHibernateEnabled = logHibernateEnabledCheck.isSelected
            s.logHibernateSqlPrefix = logHibernateSqlPrefixField.text.trim()
            s.logHibernateParamPrefix = logHibernateParamPrefixField.text.trim()
            s.logJdbcEnabled = logJdbcEnabledCheck.isSelected
            s.logAutoSaveEnabled = logAutoSaveCheck.isSelected
            s.logAutoSavePath = logAutoSavePathField.text.trim()
            s.logAutoSaveFileNamePattern = logAutoSavePatternField.text.trim()
                .ifEmpty { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
            s.autoInjectSqlSourceInterceptor = autoInjectInterceptorCheck.isSelected
            // T-CrudColor：CRUD 颜色标记（样式 + 四色）
            s.crudColorStyle = selectedCrudColorStyle()
            // T-DisplayModeToSettings：SQL 列表显示模式（apply 后经 SETTINGS_CHANGE_TOPIC 广播，两列表既有监听刷新）
            s.logListSqlDisplayMode = selectedListDisplayMode()
            s.crudColorSelect = colorHexOf(crudColorSelectPanel)
            s.crudColorInsert = colorHexOf(crudColorInsertPanel)
            s.crudColorUpdate = colorHexOf(crudColorUpdatePanel)
            s.crudColorDelete = colorHexOf(crudColorDeletePanel)
        }
        if (SECTION_UI in sections) {
            s.showGutterIcon = gutterIconCheck.isSelected
            s.joinDiagramNative = joinDiagramNativeCheck.isSelected
            s.language = (languageCombo.selectedItem as? LanguageOption)?.value ?: PluginSettings.DEFAULT_LANGUAGE
            if (shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)) {
                applyShortcut(shortcutEditor, ConvertToSqlAction.ACTION_ID)
            }
            if (reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)) {
                applyShortcut(reverseShortcutEditor, ConvertSqlToJavaAction.ACTION_ID)
            }
        }
        if (SECTION_UPDATE_DIAGNOSTICS in sections) {
            s.autoCheckUpdate = updateSection.autoCheckCheck.isSelected
        }
        languagePending = false
        // 强制触发持久化：原地修改 state 属性后，框架可能不检测引用变化导致 XML 未写入（实证：
        // injectTenantLine=true 未持久化到 mp-sql-visualizer.xml，仅 showGutterIcon 等部分字段生效）。
        // 替换 state 引用使 XmlSerializer 检测到「状态变更」并在下一次持久化周期写入。
        LOG.info("PluginSettingsPanel.apply: state.after=${s.injectTenantLine}, tenantColumn=${s.tenantColumnName}, " +
                 "calling loadState(s.copy())")
        settings.loadState(s.copy())
        // 发布设置变更通知（订阅方如 SqlLogPanel 据此即时刷新 UI）
        ApplicationManager.getApplication().messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        return languageChanged
    }

    /**
     * 从配置加载表单值（Settings 打开/Reset 时调用）。
     */
    fun reset() {
        val s = settings.state
        if (SECTION_PREVIEW in sections) {
            dialectCombo.selectedItem = s.dialect
            replaceParamsCheck.isSelected = s.replaceParams
            formatCheck.isSelected = s.formatSql
            verticalPairAnnotationCheck.isSelected = s.verticalPairAnnotation
            autoFoldCheck.isSelected = s.autoFoldEnabled
            autoFoldThresholdField.text = s.autoFoldFieldThreshold.toString()
            autoFoldThresholdField.isEnabled = s.autoFoldEnabled
            literalParamRecoveryCheck.isSelected = s.literalParamRecovery
            injectLogicDeleteCheck.isSelected = s.injectLogicDelete
            logicDeleteValueField.text = s.logicDeleteValue
            injectTenantLineCheck.isSelected = s.injectTenantLine
            tenantColumnNameField.text = s.tenantColumnName
            tenantColumnDefaultField.text = s.tenantColumnDefault
            injectTenantOnInsertCheck.isSelected = s.injectTenantOnInsert
            dataPermissionHintCheck.isSelected = s.dataPermissionHint
            dataPermissionMockConditionField.text = s.dataPermissionMockCondition
            jdbcSqlPreviewCheck.isSelected = s.jdbcSqlPreviewEnabled
        }
        if (SECTION_TABLE_MAPPING in sections) {
            tablePrefixField.text = s.tablePrefix
            tableSuffixField.text = s.tableSuffix
            inferenceCombo.selectedItem = inferenceOptions().firstOrNull { it.value == s.tableNameInference }
                ?: inferenceOptions().first()
            ignoredFieldsField.text = s.ignoredFields
            removeTablePrefixField.text = s.removeTablePrefix
            removeTableSuffixField.text = s.removeTableSuffix
        }
        if (SECTION_CODE_GEN in sections) {
            reverseFormatCombo.selectedItem = reverseFormatOptions().firstOrNull { it.value == s.reverseWrapperFormat }
                ?: reverseFormatOptions().first()
            manualEntityCheck.isSelected = s.reverseManualEntitySelection
        }
        if (SECTION_UI in sections) {
            gutterIconCheck.isSelected = s.showGutterIcon
            joinDiagramNativeCheck.isSelected = s.joinDiagramNative
            languageCombo.selectedItem = languageOptions().firstOrNull { it.value == s.language } ?: languageOptions().first()
            shortcutEditor.shortcut = currentShortcut(ConvertToSqlAction.ACTION_ID)
            reverseShortcutEditor.shortcut = currentShortcut(ConvertSqlToJavaAction.ACTION_ID)
        }
        if (SECTION_UPDATE_DIAGNOSTICS in sections) {
            updateSection.resetState()
        }
        if (SECTION_SQL_LOG in sections) {
            logSqlPrefixField.text = s.logSqlPrefix
            logParamPrefixField.text = s.logParamPrefix
            logMixedPrefixCheck.isSelected = s.logMixedPrefix
            logAutoCaptureCheck.isSelected = s.logAutoCaptureOnStart
            logAutoOpenCheck.isSelected = s.logAutoOpenOnCapture
            logStopOnToolWindowCloseCheck.isSelected = s.logStopOnToolWindowClose
            logTimestampCheck.isSelected = s.logTimestampEnabled
            logTimestampFormatField.text = s.logTimestampFormat
            if (s.logTimestampSource == "capture") {
                logTimestampSourceCaptureRadio.isSelected = true
            } else {
                logTimestampSourceLogRadio.isSelected = true
            }
            logFormatSqlCheck.isSelected = s.logFormatSql
            logFilterKeywordsField.text = s.logFilterKeywords
            logDedupeField.text = s.logDedupeWindowMs.toString()
            logMaxParseField.text = s.logMaxParseLength.toString()
            logMaxCountField.text = s.logMaxCount.toString()
            logTimePrefixField.text = s.logTimePrefix
            slowQueryThresholdField.text = s.slowQueryThresholdMs.toString()
            logShowDurationCheck.isSelected = s.showDurationInLogList
            // M56 T-M56-08：增强捕获三行归位 SQL 日志区组后，读写随 sqlLog 分区（preview 分支不再处理）
            enhancedCaptureCheck.isSelected = s.enhancedCaptureEnabled
            enhancedDesensitizeCheck.isSelected = s.enhancedCaptureDesensitize
            logHibernateEnabledCheck.isSelected = s.logHibernateEnabled
            logHibernateSqlPrefixField.text = s.logHibernateSqlPrefix
            logHibernateParamPrefixField.text = s.logHibernateParamPrefix
            logJdbcEnabledCheck.isSelected = s.logJdbcEnabled
            logAutoSaveCheck.isSelected = s.logAutoSaveEnabled
            logAutoSavePathField.text = s.logAutoSavePath
            logAutoSavePatternField.text = s.logAutoSaveFileNamePattern
            val autoSaveEnabled = s.logAutoSaveEnabled
            logAutoSavePathField.isEnabled = autoSaveEnabled
            logAutoSaveBrowseButton.isEnabled = autoSaveEnabled
            logAutoSavePatternField.isEnabled = autoSaveEnabled
            updateAutoSavePreview()
            // T-CrudColor：CRUD 颜色标记控件回填
            crudColorStyleCombo.selectedItem = crudColorStyleOptions()
                .firstOrNull { it.value == s.crudColorStyle } ?: crudColorStyleOptions().first()
            // T-DisplayModeToSettings：显示模式下拉回填
            logDisplayModeCombo.selectedItem = listDisplayModeOptions()
                .firstOrNull { it.value == s.logListSqlDisplayMode } ?: listDisplayModeOptions().first()
            crudColorSelectPanel.selectedColor = colorOf(s.crudColorSelect)
            crudColorInsertPanel.selectedColor = colorOf(s.crudColorInsert)
            crudColorUpdatePanel.selectedColor = colorOf(s.crudColorUpdate)
            crudColorDeletePanel.selectedColor = colorOf(s.crudColorDelete)
            autoInjectInterceptorCheck.isSelected = s.autoInjectSqlSourceInterceptor
        }
    }

    // ------------------------------------------------------------------
    // F-43：自动保存历史 SQL 辅助方法
    // ------------------------------------------------------------------

    private fun browseAutoSavePath() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            false, true, false, false, false, false
        ).withTitle(MpSqlVisualizerBundle.message("settings.log.autoSave.browse.tip"))
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(java.io.File(System.getProperty("user.home")))
        val dir = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, baseDir)
        if (dir != null) {
            logAutoSavePathField.text = dir.path
        }
    }

    private fun updateAutoSavePreview() {
        val pattern = logAutoSavePatternField.text.trim()
            .ifEmpty { PluginSettings.DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN }
        val resolved = resolvePatternPreview(pattern)
        logAutoSavePatternPreview.text = MpSqlVisualizerBundle.message(
            "settings.log.autoSave.pattern.preview", resolved
        )
    }

    /** 解析模板用于预览（复用 SqlLogAutoSaver 的占位符规则）。 */
    private fun resolvePatternPreview(pattern: String): String {
        val now = System.currentTimeMillis()
        return pattern.replace(Regex("""\$([^$]+)\$""")) { mr ->
            val formatStr = mr.groupValues[1]
            try {
                val formatted = java.text.SimpleDateFormat(formatStr, java.util.Locale.ENGLISH).format(java.util.Date(now)).replace(':', '-')
                if (formatted == formatStr) mr.value else formatted
            } catch (_: Exception) {
                mr.value
            }
        }
    }

    // ------------------------------------------------------------------
    // O-5：设置导入/导出
    // ------------------------------------------------------------------

    /**
     * 从 JSON 文件导入设置（文件选择器 → 读取 → 覆盖当前全局配置 → 刷新表单）。
     * 20260907：结果反馈改面板内行内 label（原模态弹窗 parent=rootPanel 叠在 Settings 对话框之下，须关掉设置才可见）。
     */
    private fun importSettings() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.extension?.lowercase() == "json" }
            .withTitle(MpSqlVisualizerBundle.message("settings.import"))
        val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, null, null) ?: return
        // T-Perf-14：文件 IO + JSON 解析移后台（EDT 不做磁盘读）；结果回 EDT 刷新表单与行内反馈
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                applyImportedText(text)
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                outcome.onSuccess {
                    reset()
                    showStatus(MpSqlVisualizerBundle.message("settings.import.success"), isError = false)
                }.onFailure { e ->
                    showStatus(MpSqlVisualizerBundle.message("settings.import.failed", briefMessage(e)), isError = true, fullDetail = e.message)
                }
            }, com.intellij.openapi.application.ModalityState.any()) // 默认 modality 在模态 Settings 弹窗存续期不执行（真机实证反馈不出现）——对齐 M36.5.13 惯例
        }
    }

    /** 解析导入文本并覆盖当前全局配置（internal 供测试直调——文件选择器在 headless 下不可用）。 */
    internal fun applyImportedText(text: String) {
        PluginSettings.fromJson(text)
    }

    /**
     * 导出当前设置到 JSON 文件（文件选择器 → 写入）。
     * 20260907：结果反馈改面板内行内 label（同导入）。
     */
    private fun exportSettings() {
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            MpSqlVisualizerBundle.message("settings.export"),
            "JSON (*.json)",
            "json",
        )
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByIoFile(java.io.File(System.getProperty("user.home")))
        val wrapper = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null)
        val saved = wrapper.save(baseDir, "mp-sql-visualizer-settings.json")
        if (saved != null) {
            // T-Perf-14：文件写入移后台（EDT 不做磁盘写）；结果回 EDT 行内反馈
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val outcome = runCatching {
                    writeExportedFile(java.io.File(saved.file.absolutePath))
                }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    outcome.onSuccess {
                        showStatus(MpSqlVisualizerBundle.message("settings.export.success"), isError = false)
                    }.onFailure { e ->
                        showStatus(MpSqlVisualizerBundle.message("settings.export.failed", briefMessage(e)), isError = true, fullDetail = e.message)
                    }
                }, com.intellij.openapi.application.ModalityState.any()) // 同导入：模态 Settings 弹窗存续期照常回调
            }
        }
    }

    /** 写出当前设置 JSON（internal 供测试直调）。 */
    internal fun writeExportedFile(target: java.io.File) {
        target.writeText(PluginSettings.toJson(), Charsets.UTF_8)
    }

    /**
     * 行内反馈（20260907）：成功=默认前景色，失败=ErrorForeground 红字；完整异常详情放 tooltip。
     * 常驻可见（直到下次操作覆盖），不抢焦点、无遮挡。
     */
    private fun showStatus(text: String, isError: Boolean, fullDetail: String? = null) {
        statusLabel.text = text
        statusLabel.toolTipText = fullDetail ?: text
        statusLabel.foreground = if (isError) {
            com.intellij.ui.JBColor.namedColor("ErrorForeground", com.intellij.ui.JBColor.RED)
        } else {
            com.intellij.ui.JBColor.foreground()
        }
    }

    /** 异常摘要截断（行内 label 宽度有限，全量详情走 tooltip）。 */
    private fun briefMessage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return if (msg.length > 120) msg.take(120) + "…" else msg
    }
}
