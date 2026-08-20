package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction
import cn.song.mybatisplus.visualizer.util.PluginCacheManager
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.PluginBuildInfo
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
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
 *
 * M16.2 增强：
 *  - 「表前缀」「表后缀」文本输入行（可添加/清除）——统一修饰预览 SQL 中的表名（全局语义）。
 */
class PluginSettingsPanel(private val settings: PluginSettings) {

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

    /** gutter 预览 SQL 图标开关（M10）。 */
    private val gutterIconCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.showGutterIcon"))

    /** 逻辑删除注入开关（T-M15-07）：@TableLogic 实体查询/删除自动追加 `AND deleted = 0`。 */
    private val injectLogicDeleteCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectLogicDelete"))
    private val logicDeleteValueField = JBTextField(6)

    /** 多租户注入开关（T-TenantLine）：SELECT/UPDATE/DELETE 自动追加 `AND tenant_id = ?`、INSERT 列清单追加租户列。 */
    private val injectTenantLineCheck = JBCheckBox(MpSqlVisualizerBundle.message("settings.injectTenantLine"))

    /** 租户列名输入框（T-TenantLine）：默认 tenant_id；可按业务调整，如 org_id / shop_id。 */
    private val tenantColumnNameField = JBTextField(10)

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

    /** 插件缓存统一清除按钮（清全部缓存，下次使用自动重建）。 */
    private val clearCacheButton = JButton(MpSqlVisualizerBundle.message("settings.cache.clear"))

    // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------
    private val logFilterBarCheck = JBCheckBox()
    private val logSqlPrefixField = JBTextField(12)
    private val logParamPrefixField = JBTextField(12)
    private val logMixedPrefixCheck = JBCheckBox()
    private val logAutoCaptureCheck = JBCheckBox()
    private val logAutoOpenCheck = JBCheckBox()
    private val logStopOnToolWindowCloseCheck = JBCheckBox()
    private val logTimestampCheck = JBCheckBox()
    private val logTimestampFormatField = JBTextField(12)
    private val logFormatSqlCheck = JBCheckBox()
    private val logFilterKeywordsField = JBTextField(12)
    private val logDedupeField = JBTextField(12)
    private val logMaxParseField = JBTextField(12)

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
            insets = Insets(4, 6, 4, 6)   // M14.1 紧凑行距
        }

        fun titleRow(titleKey: String) {
            gbc.gridx = 0
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(TitledSeparator(MpSqlVisualizerBundle.message(titleKey)), gbc)
            gbc.gridwidth = 1
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            gbc.gridy++
        }

        fun section(rows: (JPanel, GridBagConstraints) -> Unit) {
            val sectionPanel = JPanel(GridBagLayout())
            val inner = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 6, 4, 6)
            }
            rows(sectionPanel, inner)
            gbc.gridx = 0
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(sectionPanel, gbc)
            gbc.gridwidth = 1
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            gbc.gridy++
        }

        fun addRow(panel: JPanel, gbc: GridBagConstraints, label: String, component: JComponent) {
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

        // M19.2：复选框/按钮为**字段实例**（构造时按当时语言固化文本）——rebuild 重建布局只重新
        // add 同一实例，文本不随语言变（用户实测：切换语言后右侧复选框仍中文、确定重开才变）。
        // 修复：文本在 buildSections 内每轮（重）设置，rebuild 时随语言即时刷新（组标题同理）。

        // 组 1：SQL 预览（最高频）
        titleRow("settings.section.preview")
        section { p, g ->
            addRow(p, g, MpSqlVisualizerBundle.message("settings.dialect"), dialectCombo)
            addRow(p, g, "", replaceParamsCheck.apply { text = MpSqlVisualizerBundle.message("settings.replaceParams") })
            addRow(p, g, "", formatCheck.apply { text = MpSqlVisualizerBundle.message("settings.formatSql") })
            addRow(p, g, "", injectLogicDeleteCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectLogicDelete") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.logicDeleteValue"), logicDeleteValueField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.logicDeleteValue.tip")
            })
            // T-TenantLine：多租户注入开关 + 租户列名输入框
            addRow(p, g, "", injectTenantLineCheck.apply { text = MpSqlVisualizerBundle.message("settings.injectTenantLine") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tenantColumnName"), tenantColumnNameField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tenantColumnName.tip")
            })
        }

        // 组 2：表名映射（预览相关，低频——配置一次长期不变）
        titleRow("settings.section.tableMapping")
        section { p, g ->
            // 添加表前缀/表后缀：**单值**（用户反馈定稿，不存在多个——无 placeholder 提示；tooltip 保留）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tablePrefix"), tablePrefixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tablePrefix.tip")
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.tableSuffix"), tableSuffixField.apply {
                toolTipText = MpSqlVisualizerBundle.message("settings.tableSuffix.tip")
            })
            // T-M19.4（用户要求）：行顺序 = 添加表前缀 → 添加表后缀 → 移除表名前缀 → 移除表名后缀 →
            // 忽略字段名 → 表名推断策略；移除类 2 栏 + 忽略字段名共 3 个输入栏支持多值（`;` 分隔），
            // 空时显示灰色提示「多个时使用;来分隔」（JBTextField.emptyText 原生 placeholder；
            // tooltip/emptyText 均在 buildSections 内每轮重设——rebuild 时随语言即时刷新，同 M19.2）
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
                // 选中当前配置值（按 value 匹配，SNAKE 兜底）
                selectedItem = inferenceOptions().firstOrNull { it.value == settings.state.tableNameInference }
                    ?: inferenceOptions().first()
            })
        }

        // 组 3：代码生成（低频；后续生成类设置并入此组）
        titleRow("settings.section.codeGen")
        section { p, g ->
            // T-M36.3：SQL 反向转换默认 Wrapper 形态（AUTO 自动/字符串列名/Lambda/MPJ Lambda）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.reverseWrapperFormat"), reverseFormatCombo.apply {
                model = DefaultComboBoxModel(reverseFormatOptions())
                toolTipText = MpSqlVisualizerBundle.message("settings.reverseWrapperFormat.tip")
                selectedItem = reverseFormatOptions().firstOrNull { it.value == settings.state.reverseWrapperFormat }
                    ?: reverseFormatOptions().first()
            })
            // 手动选择实体类开关：自动推断未命中/多表 JOIN 时弹实体选择弹窗
            addRow(p, g, "", manualEntityCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection")
                toolTipText = MpSqlVisualizerBundle.message("settings.reverseManualEntitySelection.tip")
            })
        }

        // 组 4：SQL 日志（T-M31.14 日志还原配置，参考 MybatisCodeHelper-Pro Log Setting）
        titleRow("settings.section.sqlLog")
        section { p, g ->
            addRow(p, g, "", logFilterBarCheck.apply {
                text = MpSqlVisualizerBundle.message("settings.log.filterBar")
                toolTipText = MpSqlVisualizerBundle.message("settings.log.filterBar.tip")
            })
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
        }

        // 组 5：界面与操作（低频——环境偏好）
        titleRow("settings.section.ui")
        section { p, g ->
            addRow(p, g, "", gutterIconCheck.apply { text = MpSqlVisualizerBundle.message("settings.showGutterIcon") })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.language"), languageCombo.apply {
                model = DefaultComboBoxModel(languageOptions())
                // 选中当前配置值（按 value 匹配，zh 兜底）
                selectedItem = languageOptions().firstOrNull { it.value == settings.state.language }
                    ?: languageOptions().first()
            })
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut"), shortcutEditor.apply { updateTexts() })
            // T-M41：SQL→Java 反向转换快捷键（同款录制式内嵌编辑，保存写入 Keymap）
            addRow(p, g, MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava"), reverseShortcutEditor.apply { updateTexts() })
        }

        // 组 6：更新与诊断（最低频）
        titleRow("settings.section.updateDiagnostics")
        section { p, g ->
            // 插件缓存（用户反馈：移至版本信息上方）：大小展示 + 统一清除
            // （统一入口，新增缓存类型在 PluginCacheManager 注册即可；清除后信息行变「已清除」状态文本）
            addRow(
                p, g,
                MpSqlVisualizerBundle.message("settings.cache.label"),
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
                },
            )

            // M28：更新分区（检查更新/下载安装/重启提示/启动自动检查开关）——
            // 「版本信息」标签走 addRow 标签列（与插件缓存/内部构建号左对齐）。
            // T-M41 修复：updateSection 为多行组件，GridBag 默认 WEST（垂直居中）会把标签
            // 垂直居中到整块中间（错位到「启动时自动检查更新」行——用户截图实证）→ 顶部对齐
            g.anchor = GridBagConstraints.NORTHWEST
            addRow(p, g, MpSqlVisualizerBundle.message("settings.update.versionInfo"), updateSection.apply { updateTexts() })
            g.anchor = GridBagConstraints.WEST

            // T-M23.14：内部构建号（MPV-Build，jar manifest 注入的构建时刻时间戳）——只读信息行，
            // 用户反馈问题时可直接报出定位具体产物；非 jar 环境（开发/测试）显示 "—"。
            // T-M28.2：右侧「复制」按钮一键复制完整构建号文本（反馈问题直接粘贴）；
            // 无构建号（开发态 "—"）禁用；复制成功按钮文字短暂变「已复制」。
            addRow(p, g, MpSqlVisualizerBundle.message("settings.buildNumber"), JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel().apply {
                    text = PluginBuildInfo.displayText()
                    toolTipText = MpSqlVisualizerBundle.message("settings.buildNumber.tip")
                }, BorderLayout.CENTER)
                add(JButton(MpSqlVisualizerBundle.message("settings.buildNumber.copy")).apply {
                    isEnabled = PluginBuildInfo.mpvBuild() != null
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(PluginBuildInfo.displayText()))
                        val original = text
                        text = MpSqlVisualizerBundle.message("settings.buildNumber.copied")
                        javax.swing.Timer(1200) { text = original }.apply { isRepeats = false; start() }
                    }
                }, BorderLayout.EAST)
            })
        }

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

    /** 刷新插件缓存信息（缓存大小——所有打开项目汇总；面板构造与清除按钮共用）。 */
    private fun refreshCacheInfo() {
        val projects = ProjectManager.getInstance().openProjects
        val total = projects.sumOf { PluginCacheManager.sizeBytes(it) }
        cacheInfoLabel.text = MpSqlVisualizerBundle.message("settings.cache.info", PluginCacheManager.formatSize(total))
    }

    /** 测试访问器：缓存信息文本（断言展示与清除后刷新）。 */
    internal fun cacheInfoForTest(): String = cacheInfoLabel.text

    /** 测试访问器：清除缓存按钮（断言存在与点击行为）。 */
    internal fun clearCacheButtonForTest(): JButton = clearCacheButton

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
            || dialectCombo.selectedItem != s.dialect
            || replaceParamsCheck.isSelected != s.replaceParams
            || formatCheck.isSelected != s.formatSql
            || gutterIconCheck.isSelected != s.showGutterIcon
            || injectLogicDeleteCheck.isSelected != s.injectLogicDelete
            || logicDeleteValueField.text.trim() != s.logicDeleteValue
            || injectTenantLineCheck.isSelected != s.injectTenantLine
            || tenantColumnNameField.text.trim() != s.tenantColumnName
            || tablePrefixField.text.trim() != s.tablePrefix
            || tableSuffixField.text.trim() != s.tableSuffix
            || (inferenceCombo.selectedItem as? InferenceOption)?.value != s.tableNameInference
            // T-xx（设置生效性审计）：反向转换形态下拉此前**漏挂 isModified**——仅改此项时 Apply 按钮不启用，
            // 改动被平台静默丢弃（有读取点但保存链路断裂，形同假开关）；补上与其余下拉一致的变化检测
            || (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value != s.reverseWrapperFormat
            || manualEntityCheck.isSelected != s.reverseManualEntitySelection
            || ignoredFieldsField.text.trim() != s.ignoredFields
            || removeTablePrefixField.text.trim() != s.removeTablePrefix
            || removeTableSuffixField.text.trim() != s.removeTableSuffix
            || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
            || updateSection.autoCheckCheck.isSelected != s.autoCheckUpdate
            || shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)
            || reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)
            || logSqlPrefixField.text.trim() != s.logSqlPrefix
            || logParamPrefixField.text.trim() != s.logParamPrefix
            || logMixedPrefixCheck.isSelected != s.logMixedPrefix
            || logAutoCaptureCheck.isSelected != s.logAutoCaptureOnStart
            || logAutoOpenCheck.isSelected != s.logAutoOpenOnCapture
            || logStopOnToolWindowCloseCheck.isSelected != s.logStopOnToolWindowClose
            || logTimestampCheck.isSelected != s.logTimestampEnabled
            || logTimestampFormatField.text.trim() != s.logTimestampFormat
            || logFormatSqlCheck.isSelected != s.logFormatSql
            || logFilterKeywordsField.text.trim() != s.logFilterKeywords
            || logDedupeField.text.trim().toLongOrNull() != s.logDedupeWindowMs
            || logMaxParseField.text.trim().toIntOrNull() != s.logMaxParseLength
            || logFilterBarCheck.isSelected != s.logFilterBarEnabled
    }

    /**
     * 将表单值写回配置（Settings 点击 OK/Apply 时调用）；快捷键变化时写入 Keymap。
     *
     * @return 界面语言是否发生变化（true 时调用方应 [rebuild] 刷新设置页文案）
     */
    fun apply(): Boolean {
        val s = settings.state
        // T-TenantLine 诊断：输出 apply 调用时的表单值，确认 UI → state 写入链路
        LOG.info("PluginSettingsPanel.apply: injectTenantLineCheck=${injectTenantLineCheck.isSelected}, " +
                 "tenantColumnNameField=${tenantColumnNameField.text.trim()}, " +
                 "state.before=${s.injectTenantLine}")
        // M19.1：语言变更判定包含「待应用」标记——写穿后（combo 与 state 已一致）仍报告一次变更，
        // 触发调用方 rebuild；消费后清除标记（后续 apply 幂等）
        val languageChanged = languagePending || (languageCombo.selectedItem as? LanguageOption)?.value != s.language
        s.dialect = dialectCombo.selectedItem as? String ?: PluginSettings.DEFAULT_DIALECT
        s.replaceParams = replaceParamsCheck.isSelected
        s.formatSql = formatCheck.isSelected
        s.showGutterIcon = gutterIconCheck.isSelected
        s.injectLogicDelete = injectLogicDeleteCheck.isSelected
        s.logicDeleteValue = logicDeleteValueField.text.trim()
        s.injectTenantLine = injectTenantLineCheck.isSelected
        s.tenantColumnName = tenantColumnNameField.text.trim()
        s.tablePrefix = tablePrefixField.text.trim()
        s.tableSuffix = tableSuffixField.text.trim()
        s.tableNameInference = (inferenceCombo.selectedItem as? InferenceOption)?.value ?: PluginSettings.INFERENCE_SNAKE
        s.ignoredFields = ignoredFieldsField.text.trim()
        s.removeTablePrefix = removeTablePrefixField.text.trim()
        s.removeTableSuffix = removeTableSuffixField.text.trim()
        s.language = (languageCombo.selectedItem as? LanguageOption)?.value ?: PluginSettings.DEFAULT_LANGUAGE
        s.reverseWrapperFormat = (reverseFormatCombo.selectedItem as? ReverseFormatOption)?.value ?: PluginSettings.REVERSE_AUTO
        s.reverseManualEntitySelection = manualEntityCheck.isSelected
        s.autoCheckUpdate = updateSection.autoCheckCheck.isSelected
        s.logSqlPrefix = logSqlPrefixField.text.trim()
        s.logParamPrefix = logParamPrefixField.text.trim()
        s.logMixedPrefix = logMixedPrefixCheck.isSelected
        s.logAutoCaptureOnStart = logAutoCaptureCheck.isSelected
        s.logAutoOpenOnCapture = logAutoOpenCheck.isSelected
        s.logStopOnToolWindowClose = logStopOnToolWindowCloseCheck.isSelected
        s.logTimestampEnabled = logTimestampCheck.isSelected
        s.logTimestampFormat = logTimestampFormatField.text.trim()
        s.logFormatSql = logFormatSqlCheck.isSelected
        s.logFilterKeywords = logFilterKeywordsField.text.trim()
        s.logDedupeWindowMs = logDedupeField.text.trim().toLongOrNull() ?: PluginSettings.DEFAULT_LOG_DEDUPE_WINDOW_MS
        s.logMaxParseLength = logMaxParseField.text.trim().toIntOrNull() ?: PluginSettings.DEFAULT_LOG_MAX_PARSE_LENGTH
        s.logFilterBarEnabled = logFilterBarCheck.isSelected
        if (shortcutEditor.shortcut != currentShortcut(ConvertToSqlAction.ACTION_ID)) {
            applyShortcut(shortcutEditor, ConvertToSqlAction.ACTION_ID)
        }
        if (reverseShortcutEditor.shortcut != currentShortcut(ConvertSqlToJavaAction.ACTION_ID)) {
            applyShortcut(reverseShortcutEditor, ConvertSqlToJavaAction.ACTION_ID)
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
        dialectCombo.selectedItem = s.dialect
        replaceParamsCheck.isSelected = s.replaceParams
        formatCheck.isSelected = s.formatSql
        gutterIconCheck.isSelected = s.showGutterIcon
        injectLogicDeleteCheck.isSelected = s.injectLogicDelete
        logicDeleteValueField.text = s.logicDeleteValue
        injectTenantLineCheck.isSelected = s.injectTenantLine
        tenantColumnNameField.text = s.tenantColumnName
        tablePrefixField.text = s.tablePrefix
        tableSuffixField.text = s.tableSuffix
        inferenceCombo.selectedItem = inferenceOptions().firstOrNull { it.value == s.tableNameInference }
            ?: inferenceOptions().first()
        ignoredFieldsField.text = s.ignoredFields
        removeTablePrefixField.text = s.removeTablePrefix
        removeTableSuffixField.text = s.removeTableSuffix
        languageCombo.selectedItem = languageOptions().firstOrNull { it.value == s.language } ?: languageOptions().first()
        reverseFormatCombo.selectedItem = reverseFormatOptions().firstOrNull { it.value == s.reverseWrapperFormat }
            ?: reverseFormatOptions().first()
        manualEntityCheck.isSelected = s.reverseManualEntitySelection
        updateSection.resetState()
        shortcutEditor.shortcut = currentShortcut(ConvertToSqlAction.ACTION_ID)
        reverseShortcutEditor.shortcut = currentShortcut(ConvertSqlToJavaAction.ACTION_ID)
        logSqlPrefixField.text = s.logSqlPrefix
        logParamPrefixField.text = s.logParamPrefix
        logMixedPrefixCheck.isSelected = s.logMixedPrefix
        logAutoCaptureCheck.isSelected = s.logAutoCaptureOnStart
        logAutoOpenCheck.isSelected = s.logAutoOpenOnCapture
        logStopOnToolWindowCloseCheck.isSelected = s.logStopOnToolWindowClose
        logTimestampCheck.isSelected = s.logTimestampEnabled
        logTimestampFormatField.text = s.logTimestampFormat
        logFormatSqlCheck.isSelected = s.logFormatSql
        logFilterKeywordsField.text = s.logFilterKeywords
        logDedupeField.text = s.logDedupeWindowMs.toString()
        logMaxParseField.text = s.logMaxParseLength.toString()
        logFilterBarCheck.isSelected = s.logFilterBarEnabled
    }
}
