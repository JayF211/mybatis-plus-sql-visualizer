package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.SafeBrowser
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * 需求池报告确认框（T-ErrReport-03）：错误形态（预填标题/描述/分类/堆栈附件）与
 * 手动形态（全空、类型默认疑问咨询、无附件）共用。
 *
 * UI 只做编排：请求组装在 [collectRequest]（组合 ErrorReportComposer 纯函数），
 * 提交交回调用方回调（后台线程）；提交结果经通知展示（[FeedbackReporting.notifyResult]），
 * 不在对话框内等网络。普通 Swing 组件布局，不走 HTML 渲染（EDT 冻结教训）。
 *
 * T-FeedbackDlg（2026-09-14 真机反馈三缺陷）：
 * - 宽度：标题框列数曾误用 TITLE_MAX=200（JTextField 列数是首选宽度语义）致弹窗 1272px，
 *   收敛为 30 列 + 描述/堆栈滚动面板首选宽 620，整体 ~660px 量级；
 * - 占位：标题/描述经 getEmptyText 显示灰色提示（文案对齐官网表单），并补 [doValidate]
 *   客户端校验（title 5~200 / description ≥10），短输入拦截在触网前；
 * - 拖拽：[isResizable]=true + [getDimensionServiceKey] 记忆尺寸，描述/堆栈行
 *   weighty=1 + BOTH 随拖拽纵向伸缩。
 * 布局与校验抽为 [buildCenterPanel]/[validateForm] 纯函数（[ReportFormFields] 载体），
 * headless 测试零 DialogWrapper peer 依赖。
 */
class ErrorReportDialog(
    /** 错误形态预填；null = 手动形态。 */
    private val prefill: ErrorReportComposer.Prefill?,
    /** 异常堆栈全文；null = 手动形态（无附件勾选行）。 */
    private val stackText: String?,
    /** 栈指纹（写入描述尾部，管理员合并重复帖依据）；手动形态 null。 */
    private val fingerprint: String?,
    private val ideVersion: String?,
    private val pluginVersion: String?,
    /** 用户点击提交后回调（携带最终请求与可选堆栈附件；提交在回调方后台执行）。 */
    private val onSubmit: (FeedbackApi.Submission) -> Unit,
) : DialogWrapper(true) {

    internal val fields = ReportFormFields()

    init {
        title = MpSqlVisualizerBundle.message("error.report.dialog.title")
        setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"))
        // 平台组件文案不跟插件语言（DialogWrapper cancelAction 坑）：显式覆盖 Cancel 文案
        setCancelButtonText(MpSqlVisualizerBundle.message("error.report.dialog.cancel"))

        fields.browserFallbackHandler = { browserFallback() }
        fields.attachStackCheck.addItemListener {
            fields.stackPreviewContainer.isVisible = fields.attachStackCheck.isSelected
        }

        prefill?.let {
            fields.titleField.text = it.title
            fields.descriptionArea.text = it.description
            selectCategory(it.category)
        }
        // 类型：错误形态默认 Bug、手动形态默认疑问咨询
        fields.typeCombo.selectedIndex =
            (if (prefill != null) ErrorReportComposer.TYPE_BUG else ErrorReportComposer.TYPE_QUESTION) - 1
        if (prefill == null) selectCategory(ErrorReportComposer.CATEGORY_OTHER)

        stackText?.let { fields.stackPreview.text = it.lineSequence().take(60).joinToString("\n") }
        fields.attachStackCheck.isSelected = stackText != null
        fields.stackPreviewContainer.isVisible = fields.attachStackCheck.isSelected

        fields.nameField.text = ReportSubmitterStore.loadName()
        fields.emailField.text = ReportSubmitterStore.loadEmail()
        // 匿名默认开（与 web 表单一致）；已记忆昵称则默认实名展示，用户可自行勾回
        fields.anonymousCheck.isSelected = fields.nameField.text.isBlank()

        fields.browserLink.text = MpSqlVisualizerBundle.message("error.report.dialog.browser")

        init()
    }

    override fun createCenterPanel(): JComponent = buildCenterPanel(fields)

    override fun isResizable(): Boolean = true

    override fun getDimensionServiceKey(): String = DIMENSION_SERVICE_KEY

    override fun doValidate(): ValidationInfo? {
        val key = validateForm(fields.titleField.text, fields.descriptionArea.text) ?: return null
        val component = if (key == VALIDATION_TITLE_KEY) fields.titleField else fields.descriptionArea
        return ValidationInfo(MpSqlVisualizerBundle.message(key), component)
    }

    /** 提交前组装最终请求（描述追加指纹行；昵称/邮箱记忆随提交持久化）。 */
    internal fun collectRequest(): FeedbackApi.PostRequest {
        ReportSubmitterStore.save(fields.nameField.text.trim(), fields.emailField.text.trim())
        return FeedbackApi.PostRequest(
            title = fields.titleField.text.trim(),
            description = ErrorReportComposer.buildDescription(null, fields.descriptionArea.text, fingerprint),
            type = fields.typeCombo.selectedIndex + 1,
            category = CATEGORIES[fields.categoryCombo.selectedIndex],
            submitterName = fields.nameField.text.trim().ifEmpty { null },
            submitterEmail = fields.emailField.text.trim().ifEmpty { null },
            isAnonymous = fields.anonymousCheck.isSelected,
            source = FeedbackApi.SOURCE_IDE,
            ideVersion = ideVersion,
            pluginVersion = pluginVersion,
            attachments = emptyList(),
        )
    }

    internal fun collectStackAttachment(): FeedbackApi.StackAttachment? {
        if (stackText == null || !fields.attachStackCheck.isSelected) return null
        val content = ErrorReportComposer.buildStackAttachment(
            stackText, ideVersion, pluginVersion, fingerprint,
            occurredAt = java.time.ZonedDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"),
            ),
        )
        return FeedbackApi.StackAttachment(FeedbackApi.STACK_ATTACHMENT_NAME, content.toByteArray(Charsets.UTF_8))
    }

    override fun doOKAction() {
        // 校验不过先交框架弹错（super 只在校验通过时关闭对话框），通过才组装提交回调
        if (validateForm(fields.titleField.text, fields.descriptionArea.text) != null) {
            super.doOKAction()
            return
        }
        val submission = FeedbackApi.Submission(collectRequest(), collectStackAttachment())
        super.doOKAction()
        onSubmit(submission)
    }

    private fun browserFallback() {
        val params = listOfNotNull(
            "source=${FeedbackApi.SOURCE_IDE}",
            ideVersion?.let { "ideVersion=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}" },
            pluginVersion?.let { "pluginVersion=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}" },
        ).joinToString("&")
        SafeBrowser.browse("${FeedbackApi.SITE_BASE}/about/feedback?$params")
    }

    private fun selectCategory(slug: String) {
        val index = CATEGORIES.indexOf(slug)
        if (index >= 0) fields.categoryCombo.selectedIndex = index
    }

    companion object {
        /** 与需求池 Categories 一致（sql-visualizer-admin constants.ts 顺序）。 */
        internal val CATEGORIES = arrayOf(
            ErrorReportComposer.CATEGORY_WRAPPER,
            ErrorReportComposer.CATEGORY_XML,
            ErrorReportComposer.CATEGORY_ANNOTATION,
            ErrorReportComposer.CATEGORY_SQL_TO_JAVA,
            ErrorReportComposer.CATEGORY_SQL_LOG,
            ErrorReportComposer.CATEGORY_BATCH,
            ErrorReportComposer.CATEGORY_COMPLETION,
            ErrorReportComposer.CATEGORY_OTHER,
        )

        /** DimensionService key：用户拖拽后的对话框尺寸跨会话记忆（T-FeedbackDlg-02）。 */
        internal const val DIMENSION_SERVICE_KEY = "MpSqlVisualizer.ErrorReportDialog"

        internal const val VALIDATION_TITLE_KEY = "error.report.dialog.validation.title"
        internal const val VALIDATION_DESCRIPTION_KEY = "error.report.dialog.validation.description"

        /**
         * 提交前客户端校验（纯函数）：title 5~200 / description ≥10（需求池服务端契约，
         * 与 ErrorReportComposer 同源常量）。返回应提示的 i18n key；null = 通过。
         */
        internal fun validateForm(title: String, description: String): String? {
            val trimmed = title.trim()
            if (trimmed.length < ErrorReportComposer.TITLE_MIN || trimmed.length > ErrorReportComposer.TITLE_MAX) {
                return VALIDATION_TITLE_KEY
            }
            if (description.trim().length < ErrorReportComposer.DESCRIPTION_MIN) {
                return VALIDATION_DESCRIPTION_KEY
            }
            return null
        }

        /**
         * 布局组装（纯函数，T-FeedbackDlg-03/02）：描述与堆栈滚动面板首选宽 620 钳制弹窗宽度，
         * 两行 fill=BOTH + weighty=1 随对话框拖拽纵向伸缩（堆栈未勾选不可见时不参与分配）。
         */
        internal fun buildCenterPanel(fields: ReportFormFields): JComponent {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 0, 4, 0)
            }
            val panel = JPanel(GridBagLayout())
            fun labelRow(textKey: String, component: JComponent, stretchVertically: Boolean = false) {
                gbc.gridx = 0
                gbc.gridy++
                gbc.weightx = 0.0
                gbc.weighty = 0.0
                gbc.fill = GridBagConstraints.NONE
                panel.add(JBLabel(MpSqlVisualizerBundle.message(textKey)), gbc)
                gbc.gridx = 1
                gbc.weightx = 1.0
                if (stretchVertically) {
                    gbc.weighty = 1.0
                    gbc.fill = GridBagConstraints.BOTH
                } else {
                    gbc.fill = GridBagConstraints.HORIZONTAL
                }
                panel.add(component, gbc)
                gbc.weighty = 0.0
            }
            fun fullRow(component: JComponent, stretchVertically: Boolean = false) {
                gbc.gridx = 0
                gbc.gridy++
                gbc.gridwidth = 2
                gbc.weightx = 1.0
                gbc.weighty = if (stretchVertically) 1.0 else 0.0
                gbc.fill = if (stretchVertically) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
                panel.add(component, gbc)
                gbc.gridwidth = 1
                gbc.weighty = 0.0
            }

            labelRow("error.report.dialog.subject", fields.titleField)
            labelRow("error.report.dialog.type", fields.typeCombo)
            labelRow("error.report.dialog.category", fields.categoryCombo)
            labelRow("error.report.dialog.description", JBScrollPane(fields.descriptionArea).apply {
                preferredSize = JBUI.size(620, 140)
            }, stretchVertically = true)
            fullRow(fields.attachStackCheck)
            fullRow(fields.stackPreviewContainer, stretchVertically = true)
            labelRow("error.report.dialog.name", fields.nameField)
            labelRow("error.report.dialog.email", fields.emailField)
            fullRow(fields.anonymousCheck)
            fullRow(fields.browserLink)
            return panel
        }

        internal fun typeLabel(type: Int): String = MpSqlVisualizerBundle.message("feedback.type.$type")

        internal fun categoryLabel(slug: String): String = MpSqlVisualizerBundle.message("feedback.category.$slug")
    }
}

/**
 * 对话框全部输入控件（T-FeedbackDlg 可测试性抽取）：构造即带占位提示与换行配置，
 * [ErrorReportDialog.buildCenterPanel]/[ErrorReportDialog.validateForm] 的纯函数载体，
 * headless 测试直接构造，零 DialogWrapper peer 依赖。
 */
internal class ReportFormFields {

    /** 标题 30 列（T-FeedbackDlg-03：旧实现误用 TITLE_MAX=200 列致弹窗 1272px 过宽）。 */
    val titleField = JBTextField(30).apply {
        emptyText.setText(MpSqlVisualizerBundle.message("error.report.dialog.subject.placeholder"))
    }

    val typeCombo = JComboBox(Array(4) { index -> ErrorReportDialog.typeLabel(index + 1) })

    val categoryCombo = JComboBox(Array(ErrorReportDialog.CATEGORIES.size) { index ->
        ErrorReportDialog.categoryLabel(ErrorReportDialog.CATEGORIES[index])
    })

    val descriptionArea = JBTextArea(6, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.setText(MpSqlVisualizerBundle.message("error.report.dialog.description.placeholder"))
    }

    val attachStackCheck = JCheckBox().apply {
        text = MpSqlVisualizerBundle.message("error.report.dialog.attachStack")
        toolTipText = MpSqlVisualizerBundle.message("error.report.dialog.attachStack.tip")
    }

    val stackPreview = JBTextArea(10, 40).apply {
        isEditable = false
        lineWrap = false
    }

    val stackPreviewContainer = JPanel(BorderLayout(4, 2)).apply {
        add(JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.stackPreview")), BorderLayout.NORTH)
        add(JBScrollPane(stackPreview).apply { preferredSize = JBUI.size(620, 180) }, BorderLayout.CENTER)
    }

    val nameField = JTextField(20)
    val emailField = JTextField(20)

    val anonymousCheck = JCheckBox().apply {
        text = MpSqlVisualizerBundle.message("error.report.dialog.anonymous")
    }

    /** 浏览器兜底链接行为由对话框注入（[browserFallbackHandler]），字段类保持无逻辑。 */
    var browserFallbackHandler: () -> Unit = {}
    val browserLink = LinkLabel<Any>("", null) { _, _ -> browserFallbackHandler() }
}

/**
 * 报告提交者昵称/邮箱的持久化（T-ErrReport-03）：对话框 OK 时写入 State。
 * 直接原地改 state 属性不触发 XmlSerializer 持久化（T-TenantLine 根因），须
 * copy + loadState 替换引用；测试环境（service 未注册）静默跳过。
 */
internal object ReportSubmitterStore {

    fun loadName(): String =
        runCatching { PluginSettings.getInstance().state.reportSubmitterName }.getOrDefault("")

    fun loadEmail(): String =
        runCatching { PluginSettings.getInstance().state.reportSubmitterEmail }.getOrDefault("")

    fun save(name: String, email: String) {
        runCatching {
            val settings = PluginSettings.getInstance()
            val current = settings.state
            if (current.reportSubmitterName != name || current.reportSubmitterEmail != email) {
                settings.loadState(current.copy(reportSubmitterName = name, reportSubmitterEmail = email))
            }
        }
    }
}
