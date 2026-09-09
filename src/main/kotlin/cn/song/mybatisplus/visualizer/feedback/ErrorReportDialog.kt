package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.labels.LinkLabel
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

    private val titleField = JTextField(ErrorReportComposer.TITLE_MAX)
    private val typeCombo = JComboBox(Array(4) { index -> typeLabel(index + 1) })
    private val categoryCombo = JComboBox(Array(8) { index -> categoryLabel(CATEGORIES[index]) })
    private val descriptionArea = JBTextArea(8, 46)
    private val stackPreview = JBTextArea(10, 46)
    private val attachStackCheck = JCheckBox().apply {
        text = MpSqlVisualizerBundle.message("error.report.dialog.attachStack")
        toolTipText = MpSqlVisualizerBundle.message("error.report.dialog.attachStack.tip")
        addItemListener { stackPreviewContainer.isVisible = isSelected }
    }
    private val stackPreviewContainer = JPanel(BorderLayout(4, 2)).apply {
        add(JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.stackPreview")), BorderLayout.NORTH)
        add(JBScrollPane(stackPreview), BorderLayout.CENTER)
    }
    private val nameField = JTextField(20)
    private val emailField = JTextField(20)
    private val anonymousCheck = JCheckBox().apply {
        text = MpSqlVisualizerBundle.message("error.report.dialog.anonymous")
    }
    private val browserLink = LinkLabel<Any>("", null) { _, _ -> browserFallback() }

    init {
        title = MpSqlVisualizerBundle.message("error.report.dialog.title")
        setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"))
        // 平台组件文案不跟插件语言（DialogWrapper cancelAction 坑）：显式覆盖 Cancel 文案
        setCancelButtonText(MpSqlVisualizerBundle.message("error.report.dialog.cancel"))

        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        stackPreview.isEditable = false
        stackPreview.lineWrap = false

        prefill?.let {
            titleField.text = it.title
            descriptionArea.text = it.description
            selectCategory(it.category)
        }
        // 类型：错误形态默认 Bug、手动形态默认疑问咨询
        typeCombo.selectedIndex =
            (if (prefill != null) ErrorReportComposer.TYPE_BUG else ErrorReportComposer.TYPE_QUESTION) - 1
        if (prefill == null) selectCategory(ErrorReportComposer.CATEGORY_OTHER)

        stackText?.let { stackPreview.text = it.lineSequence().take(60).joinToString("\n") }
        attachStackCheck.isSelected = stackText != null
        stackPreviewContainer.isVisible = attachStackCheck.isSelected

        nameField.text = ReportSubmitterStore.loadName()
        emailField.text = ReportSubmitterStore.loadEmail()
        // 匿名默认开（与 web 表单一致）；已记忆昵称则默认实名展示，用户可自行勾回
        anonymousCheck.isSelected = nameField.text.isBlank()

        browserLink.text = MpSqlVisualizerBundle.message("error.report.dialog.browser")

        init()
    }

    override fun createCenterPanel(): JComponent {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 0, 4, 0)
        }
        val panel = JPanel(GridBagLayout())
        fun labelRow(textKey: String, component: JComponent) {
            gbc.gridx = 0
            gbc.gridy++
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(JBLabel(MpSqlVisualizerBundle.message(textKey)), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(component, gbc)
        }
        fun fullRow(component: JComponent) {
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(component, gbc)
            gbc.gridwidth = 1
        }

        labelRow("error.report.dialog.subject", titleField)
        labelRow("error.report.dialog.type", typeCombo)
        labelRow("error.report.dialog.category", categoryCombo)
        labelRow("error.report.dialog.description", JBScrollPane(descriptionArea))
        fullRow(attachStackCheck)
        fullRow(stackPreviewContainer)
        labelRow("error.report.dialog.name", nameField)
        labelRow("error.report.dialog.email", emailField)
        fullRow(anonymousCheck)
        fullRow(browserLink)
        return panel
    }

    /** 提交前组装最终请求（描述追加指纹行；昵称/邮箱记忆随提交持久化）。 */
    internal fun collectRequest(): FeedbackApi.PostRequest {
        ReportSubmitterStore.save(nameField.text.trim(), emailField.text.trim())
        return FeedbackApi.PostRequest(
            title = titleField.text.trim(),
            description = ErrorReportComposer.buildDescription(null, descriptionArea.text, fingerprint),
            type = typeCombo.selectedIndex + 1,
            category = CATEGORIES[categoryCombo.selectedIndex],
            submitterName = nameField.text.trim().ifEmpty { null },
            submitterEmail = emailField.text.trim().ifEmpty { null },
            isAnonymous = anonymousCheck.isSelected,
            source = FeedbackApi.SOURCE_IDE,
            ideVersion = ideVersion,
            pluginVersion = pluginVersion,
            attachments = emptyList(),
        )
    }

    internal fun collectStackAttachment(): FeedbackApi.StackAttachment? {
        if (stackText == null || !attachStackCheck.isSelected) return null
        val content = ErrorReportComposer.buildStackAttachment(
            stackText, ideVersion, pluginVersion, fingerprint,
            occurredAt = java.time.ZonedDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"),
            ),
        )
        return FeedbackApi.StackAttachment(FeedbackApi.STACK_ATTACHMENT_NAME, content.toByteArray(Charsets.UTF_8))
    }

    override fun doOKAction() {
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
        BrowserUtil.browse("${FeedbackApi.SITE_BASE}/about/feedback?$params")
    }

    private fun selectCategory(slug: String) {
        val index = CATEGORIES.indexOf(slug)
        if (index >= 0) categoryCombo.selectedIndex = index
    }

    private fun typeLabel(type: Int): String = MpSqlVisualizerBundle.message("feedback.type.$type")

    private fun categoryLabel(slug: String): String = MpSqlVisualizerBundle.message("feedback.category.$slug")

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
    }
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
