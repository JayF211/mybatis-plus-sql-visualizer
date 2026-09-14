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
 *
 * T-FeedbackDlg2（2026-09-14 真机复查两缺陷）：
 * - 勾选跳动：堆栈行以 weighty=1 参与分配、窗口高度不变，堆栈出现即压扁上方描述行。
 *   [toggleStackPreview] 勾选时给窗口做等量高度补偿（堆栈行首选高 + 行距 + 当前纵向富余量，
 *   富余量项保证描述行实际高度分毫不差），取消时按堆栈行实际高对称缩回；
 * T-FeedbackDlg3（2026-09-14 真机反馈两缺陷）：
 * - 手动形态堆栈自主输入：勾选行不再依赖错误池有无（恒显示），勾选后堆栈区**可编辑**——
 *   用户自行粘贴，或点「从 IDE 错误池导入」按插件包名过滤后选择（[stackPoolProvider] 懒取，
 *   弹窗时实时读池）；附件内容取文本区实际内容（[collectStackAttachment]）；
 * - 描述框默认高收敛（滚动面板首选 140→90，文本区 6→4 行）。
 * 错误形态维持原语义：event 堆栈预填只读预览、附件用全文、无堆栈隐藏勾选行。
 *
 * T-FeedbackErr（2026-09-14 真机：报告失败只有一个「Server returned HTTP response code:
 * 400」toast，用户既看不到原因也没有重试入口）——提交状态机落进对话框本身：
 * - 点提交后对话框**不关**，按钮转「提交中…」并禁用，状态行就地显示进度；
 * - 失败（服务端 4xx 正文文案已透出）时按钮恢复「提交」、状态行给红字原因 + 转浏览器兜底，
 *   用户改完内容可直接再提交，输入不会随关窗丢失；
 * - 成功才自动关闭，通知路径（FR 编号 + 查看详情）保持不变；
 * - 附件上传失败降级（[FeedbackApi.SubmissionOutcome.warning]）时正文照发、状态行提示堆栈没带上。
 * 结果文案为纯函数（[successStatusText]/[failureStatusText]），headless 可测。
 *
 * T-FeedbackBatch（用户定案「不能被轻易限流」）：平台错误列表可多选，用户常一次勾十几条。
 * 服务端限流为每 IP 每小时 5 次提交，逐条提交必然撞 429，因此：
 * - 批次条目非空时对话框底部出现「其他 N 条异常」分节，逐条复选框默认全勾
 *   （用户看得见「这批会一起报上去」，不是被静默合并）；
 * - 提交按条数分流（[BatchSubmitter]）：≤5 条合并成一条帖子，更多则首发带合并附件、其余错峰补发；
 * - 标题/描述由 [composeSubmission] 纯函数派生，条目全不勾时退化为普通单条报告。
 */
class ErrorReportDialog(
    /** 错误形态预填；null = 手动形态。 */
    private val prefill: ErrorReportComposer.Prefill?,
    /** 异常堆栈全文（仅错误形态=event 堆栈）；错误形态 null 时隐藏勾选行。 */
    private val stackText: String?,
    /** 栈指纹（写入描述尾部，管理员合并重复帖依据）；手动形态 null。 */
    private val fingerprint: String?,
    private val ideVersion: String?,
    private val pluginVersion: String?,
    /** 手动形态「从 IDE 错误池导入」候选堆栈提供者（点菜单时实时调用，已按插件包名过滤）。 */
    private val stackPoolProvider: () -> List<String> = { emptyList() },
    /**
     * 用户点击提交后回调：携带最终请求/附件与勾选后的批次条目，以及提交结束回调；
     * 调用方拿到结果后必须回调它（成功/失败都要），对话框据此恢复按钮、渲染状态行、成功时自行关闭。
     */
    private val onSubmit: (
        FeedbackApi.Submission,
        List<BatchSubmitter.BatchItem>,
        (FeedbackApi.SubmissionOutcome) -> Unit,
    ) -> Unit,
) : DialogWrapper(true) {

    internal val fields = ReportFormFields()

    /**
     * 本次会话附带的批次条目（错误形态由 [IdeErrorReportSubmitter] 按勾选的 events 注入；
     * 手动形态为空）。条目级勾选状态在提交时读取（[selectedBatchItems]）。
     */
    internal var batchItems: List<BatchSubmitter.BatchItem> = emptyList()
        set(value) {
            field = value
            if (prefill != null) applyBatchItemsToFields(fields, value)
        }

    /** 提交进行中标记：重复点提交直接忽略（T-FeedbackErr 状态机）。 */
    private var isSubmitting = false

    /** 失败状态行文字色（状态行默认继承标签前景色，失败时才染红）。 */
    private val STATUS_ERROR_COLOR = java.awt.Color(0xC0, 0x39, 0x2B)

    init {
        title = MpSqlVisualizerBundle.message("error.report.dialog.title")
        setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"))
        // 平台组件文案不跟插件语言（DialogWrapper cancelAction 坑）：显式覆盖 Cancel 文案
        setCancelButtonText(MpSqlVisualizerBundle.message("error.report.dialog.cancel"))
        // T-FeedbackErr：提交不再一按就关窗——状态行在窗内，失败可原地改内容重试
        setOKButtonTooltip(MpSqlVisualizerBundle.message("error.report.dialog.submit.tip"))
        isOKActionEnabled = true

        fields.browserFallbackHandler = { browserFallback() }

        // T-FeedbackErr 真机第二轮：状态行复制按钮（对齐 SQL 预览窗口失败信息交互）——
        // 复制当前状态行全文（含服务端原因/诊断片段），成功后按钮短暂变「已复制」
        fields.statusCopyHandler = {
            val text = fields.statusLabel.text.orEmpty()
            if (text.isNotBlank()) {
                com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(java.awt.datatransfer.StringSelection(text))
                cn.song.mybatisplus.visualizer.ui.flashCopied(fields.statusCopyButton)
            }
        }
        fields.statusCopyButton.addActionListener { fields.statusCopyHandler() }

        prefill?.let {
            fields.titleField.text = it.title
            fields.descriptionArea.text = it.description
            selectCategory(it.category)
        }
        // 类型：错误形态默认 Bug、手动形态默认疑问咨询
        fields.typeCombo.selectedIndex =
            (if (prefill != null) ErrorReportComposer.TYPE_BUG else ErrorReportComposer.TYPE_QUESTION) - 1
        if (prefill == null) selectCategory(ErrorReportComposer.CATEGORY_OTHER)

        if (prefill == null) {
            // T-FeedbackDlg3：手动形态堆栈自主输入——勾选恒显示，堆栈区可编辑（自行粘贴），
            // 导入按钮按插件包名列错误池候选（点菜单时才读池，反映实时状态）
            fields.stackPreview.isEditable = true
            fields.stackPreview.emptyText.setText(MpSqlVisualizerBundle.message("error.report.dialog.stackPreview.placeholder"))
            fields.stackImportButton.isVisible = true
            fields.stackImportButton.addActionListener { showStackImportMenu() }
            fields.attachStackCheck.isVisible = true
        } else {
            // 错误形态维持原语义：event 堆栈预填只读预览；无堆栈时整行隐藏不留死勾选框
            stackText?.let { fields.stackPreview.text = it.lineSequence().take(60).joinToString("\n") }
            fields.attachStackCheck.isVisible = stackText != null
        }
        // 勾选默认开仅限错误形态（T-FeedbackDlg 原语义）；手动形态默认关，用户自行决定
        fields.attachStackCheck.isSelected = prefill != null && stackText != null
        fields.stackPreviewContainer.isVisible = fields.attachStackCheck.isSelected
        // 监听在初始状态落定后注册：初始可见性直调，不触发窗口高度补偿
        fields.attachStackCheck.addItemListener { toggleStackPreview(fields.attachStackCheck.isSelected) }

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

    /**
     * 堆栈行显隐 + 窗口高度补偿（T-FeedbackDlg2-01）。真机跳动根因：堆栈行 weighty=1
     * 参与 GridBag 纵向分配，勾选后窗口高度不变、堆栈行与描述行争抢纵向空间，
     * 描述被压扁、勾选框整体「跳上去」。补偿公式见 [toggleResizeDelta]——勾选撑高、
     * 取消对称缩回，描述行高度分毫不差。preferred 增长量在翻可见性前后各测一次
     * （GridBagLayout 的 preferred 只统计可见组件），不依赖行高假设。
     * peer 未建（headless/异常路径）时静默跳过补偿，显隐本身照常。
     */
    internal fun toggleStackPreview(show: Boolean) {
        val container = fields.stackPreviewContainer
        val pane = runCatching { contentPane }.getOrNull()
        if (pane == null) {
            container.isVisible = show
            return
        }
        val prefBefore = pane.preferredSize.height
        val stackRowHeight = container.height
        container.isVisible = show
        val delta = toggleResizeDelta(
            show = show,
            contentHeight = pane.height,
            contentPrefHeight = prefBefore,
            preferredGrowth = pane.preferredSize.height - prefBefore,
            stackRowHeight = stackRowHeight,
        )
        if (delta != 0) {
            runCatching {
                val window = javax.swing.SwingUtilities.getWindowAncestor(pane)
                if (window != null) {
                    window.setSize(window.width, (window.height + delta).coerceAtLeast(MIN_WINDOW_HEIGHT))
                }
            }
        }
        pane.invalidate()
        pane.validate()
    }

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

    /**
     * 附件堆栈：错误形态用 event 全文（预览区仅为截断展示）；手动形态取堆栈区实际内容
     * （用户粘贴或从错误池导入，T-FeedbackDlg3）——勾选但内容为空视同不附带。
     */
    internal fun collectStackAttachment(): FeedbackApi.StackAttachment? {
        if (!fields.attachStackCheck.isSelected) return null
        val stack = if (prefill != null) stackText else fields.stackPreview.text?.takeIf { it.isNotBlank() }
        if (stack == null) return null
        val content = ErrorReportComposer.buildStackAttachment(
            stack, ideVersion, pluginVersion, fingerprint,
            occurredAt = FeedbackReporting.nowStamp(),
        )
        return FeedbackApi.StackAttachment(FeedbackApi.STACK_ATTACHMENT_NAME, content.toByteArray(Charsets.UTF_8))
    }

    /**
     * 手动形态「从 IDE 错误池导入」菜单（T-FeedbackDlg3）：点按钮时经 [stackPoolProvider]
     * 实时拉取本插件错误候选（已按包名过滤），每项以首行（异常类: 消息）为菜单文本，
     * 点击填入堆栈区（覆盖式——导入即替换，用户仍可再编辑）。空池给出说明项。
     */
    private fun showStackImportMenu() {
        val menu = javax.swing.JPopupMenu()
        val candidates = runCatching { stackPoolProvider() }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            val empty = javax.swing.JMenuItem(MpSqlVisualizerBundle.message("error.report.dialog.stackImport.empty"))
            empty.isEnabled = false
            menu.add(empty)
        } else {
            candidates.forEach { stack ->
                val firstLine = stack.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val item = javax.swing.JMenuItem(
                    if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine,
                )
                item.addActionListener { fields.stackPreview.text = stack }
                menu.add(item)
            }
        }
        menu.show(fields.stackImportButton, 0, fields.stackImportButton.height)
    }

    override fun doOKAction() {
        // 校验不过先交框架弹错（校验失败时框架不会关窗），通过才组装提交
        if (validateForm(fields.titleField.text, fields.descriptionArea.text) != null) {
            super.doOKAction()
            return
        }
        if (isSubmitting) return
        val submission = composeSubmission(
            FeedbackApi.Submission(collectRequest(), collectStackAttachment()),
            selectedBatchItems(),
        )
        beginSubmitting()
        // T-FeedbackErr：不再 super.doOKAction()（那会先关窗）——结果由提交结束回调决定：
        // 成功才关窗，失败留窗可改可重试
        runCatching { onSubmit(submission, selectedBatchItems()) { outcome -> finishSubmitting(outcome) } }
            .onFailure { finishSubmitting(FeedbackApi.SubmissionOutcome(FeedbackApi.SubmitResult.Failure(it.message ?: "提交失败"))) }
    }

    /** 勾选后参与提交的批次条目（T-FeedbackBatch；全不勾 = 普通单条报告）。 */
    internal fun selectedBatchItems(): List<BatchSubmitter.BatchItem> =
        if (prefill == null) emptyList() else fields.selectedBatchItems(batchItems)

    /** 把批次条目渲染进对话框（错误形态；条目多时显示「其他 N 条异常」分节 + 逐条勾选）。 */
    private fun applyBatchItemsToFields(fields: ReportFormFields, items: List<BatchSubmitter.BatchItem>) {
        fields.batchSection.removeAll()
        fields.batchSection.isVisible = items.isNotEmpty()
        if (items.isEmpty()) return
        fields.batchSection.add(
            JBLabel(MpSqlVisualizerBundle.message("error.report.dialog.batchHeader", items.size)),
            BorderLayout.NORTH,
        )
        fields.batchSection.add(
            JBScrollPane(fields.batchList).apply { preferredSize = JBUI.size(620, 90) },
            BorderLayout.CENTER,
        )
        fields.batchList.removeAll()
        items.forEach { item ->
            fields.batchList.add(
                JCheckBox(batchItemLabel(item)).apply {
                    isSelected = item.attachStack
                    toolTipText = MpSqlVisualizerBundle.message("error.report.dialog.batchItem.tip")
                    // 勾选态即条目语义：勾上=该条连堆栈一起报
                    addItemListener { item.attachStack = isSelected }
                },
            )
        }
    }

    /** 进入「提交中」：按钮禁用并改文案，状态行显示进度（T-FeedbackErr）。 */
    internal fun beginSubmitting() {
        isSubmitting = true
        if (!isHeadless) runCatching {
            setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submitting"))
            isOKActionEnabled = false
        }
        fields.statusLabel.foreground = null
        fields.statusLabel.text = submitStatusText(SubmitPhase.SUBMITTING, null, null)
        repaintStatus()
    }

    /**
     * 提交结束（成功/失败都走这里，T-FeedbackErr）：成功 → 关窗并由调用方发通知；
     * 失败 → 留在窗内，按钮恢复可点、状态行给原因（服务端文案已透出）。
     */
    internal fun finishSubmitting(outcome: FeedbackApi.SubmissionOutcome) {
        isSubmitting = false
        when (val result = outcome.result) {
            is FeedbackApi.SubmitResult.Success -> {
                if (!isHeadless) runCatching { close(OK_EXIT_CODE) }
            }

            is FeedbackApi.SubmitResult.Failure -> {
                if (!isHeadless) runCatching {
                    setOKButtonText(MpSqlVisualizerBundle.message("error.report.dialog.submit"))
                    isOKActionEnabled = true
                }
                fields.statusLabel.foreground = STATUS_ERROR_COLOR
                fields.statusLabel.text = submitStatusText(SubmitPhase.FAILED, result.message, outcome.warning)
                repaintStatus()
            }
        }
    }

    /** 状态行文案变化后标记重绘（headless 无 peer，静默跳过）。 */
    private fun repaintStatus() {
        if (isHeadless) return
        runCatching {
            fields.statusLabel.invalidate()
            contentPane?.let {
                it.invalidate()
                it.repaint()
            }
        }
    }

    /** peer 未创建（headless/异常路径）时不碰按钮与关窗 API。 */
    private val isHeadless: Boolean get() = runCatching { window == null || !window.isDisplayable }.getOrDefault(true)

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

        /** 取消勾选缩窗时的地板高（防把手动拖到极小的窗口缩到看不见内容）。 */
        internal const val MIN_WINDOW_HEIGHT = 400

        /**
         * 勾选/取消「附带异常堆栈」的窗口高度补偿量（纯函数，T-FeedbackDlg2-01）。
         * GridBag 把纵向富余（contentHeight - 可见行首选高之和）在 weighty=1 行间平分，
         * 描述行（weighty=1）随堆栈行显隐被挤占/释放空间——真机即「点击勾选后整片跳上去」。
         *
         * - 勾选：描述行损失 Δ = (slack + S)/2（slack=当前富余、S=堆栈行首选高，即
         *   preferredGrowth）；此后两 weighty 行对分新增空间，窗口撑高 2Δ = slack + S
         *   即可让描述行分毫不差。
         * - 取消：堆栈行当前实占高 R（含行间隙近似）整块释放给唯一 weighty 描述行，
         *   窗口缩回 R 即恢复原状。
         *
         * 返回单位 px，正=撑高、负=缩回；0 = 无需调整。
         */
        internal fun toggleResizeDelta(
            show: Boolean,
            contentHeight: Int,
            contentPrefHeight: Int,
            preferredGrowth: Int,
            stackRowHeight: Int,
        ): Int = if (show) {
            (contentHeight - contentPrefHeight).coerceAtLeast(0) + preferredGrowth.coerceAtLeast(0)
        } else {
            // 取消：堆栈行当前实占高与 preferred 缩减量取大者整块释放（后者兜底未布局态）
            -maxOf(stackRowHeight, -preferredGrowth).coerceAtLeast(0)
        }

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
                // T-FeedbackDlg3：默认首选高 140→90（用户真机反馈描述框默认过大）
                preferredSize = JBUI.size(620, 90)
            }, stretchVertically = true)
            // 勾选 + 导入按钮同行（导入按钮仅手动形态可见，隐藏时 FlowLayout 不留空位）
            fullRow(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                add(fields.attachStackCheck)
                add(fields.stackImportButton)
            })
            fullRow(fields.stackPreviewContainer, stretchVertically = true)
            labelRow("error.report.dialog.name", fields.nameField)
            labelRow("error.report.dialog.email", fields.emailField)
            fullRow(fields.anonymousCheck)
            fullRow(fields.browserLink)
            // T-FeedbackBatch：批次条目分节（默认隐藏，有批次条目时才显示）
            fullRow(fields.batchSection, stretchVertically = true)
            // T-FeedbackErr：状态行常驻最底部（提交中/失败原因就地展示，不依赖 toast），
            // 行内带「复制失败信息」按钮（buildStatusRow 组装）
            fullRow(buildStatusRow(fields))
            return panel
        }

        /** 失败状态行文案（纯函数，T-FeedbackErr）：服务端原因 + 可选附件降级说明 + 重试引导。
 *  附件行用中性表述（attachmentFailed）——状态行只在失败时出现，「帖子已提交，但…」的成功语境
 *  在失败现场自相矛盾（真机第二轮截图实证：同屏出现「提交失败」与「帖子已提交」）。 */
        internal fun failureStatusText(message: String, warning: String? = null): String {
            val head = MpSqlVisualizerBundle.message("error.report.dialog.status.failed", message)
            return if (warning.isNullOrBlank()) head
            else "$head\n${MpSqlVisualizerBundle.message("error.report.dialog.status.attachmentFailed", warning)}"
        }

        /** 提交阶段（状态机；[submitStatusText] 的输入）。 */
        internal enum class SubmitPhase { SUBMITTING, SUCCESS, FAILED }

        /**
         * 状态行组装（纯函数）：可换行文本区居中 + 「复制失败信息」按钮居东——按钮不随行拉伸，
         * 文本区吸收宽度；单独抽出的目的与 buildCenterPanel 一致（headless 可测、布局无 peer 依赖）。
         */
        internal fun buildStatusRow(fields: ReportFormFields): JComponent =
            javax.swing.JPanel(java.awt.BorderLayout(8, 0)).apply {
                isOpaque = false
                add(fields.statusLabel, java.awt.BorderLayout.CENTER)
                add(fields.statusCopyButton, java.awt.BorderLayout.EAST)
            }

        /**
         * 状态行文案（纯函数，T-FeedbackErr 唯一事实源）：对话框实例上的
         * beginSubmitting/finishSubmitting 只是把它写进状态行——DialogWrapper 构造断言 EDT
         * （`ThreadingAssertions` 硬抛，测试 worker 非 EDT），逻辑本身必须能脱离实例被锁定。
         * 返回 null = 该阶段无状态文案（成功即关窗，不需要写状态行）。
         */
        internal fun submitStatusText(phase: SubmitPhase, message: String?, warning: String?): String? =
            when (phase) {
                SubmitPhase.SUBMITTING -> MpSqlVisualizerBundle.message("error.report.dialog.status.submitting")
                SubmitPhase.SUCCESS -> null
                SubmitPhase.FAILED -> failureStatusText(message.orEmpty(), warning)
            }

        // ---------- T-FeedbackBatch：批量条目（纯函数，headless 可测） ----------

        /** 批次条目复选框文案：异常类简单名 + 消息首行（超长截断）。 */
        internal fun batchItemLabel(item: BatchSubmitter.BatchItem): String {
            val type = item.title.substringAfterLast('.').ifBlank { "PluginError" }
            val firstLine = item.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val text = if (firstLine.isBlank()) type else "$type: $firstLine"
            return if (text.length > 90) text.take(90) + "…" else text
        }

        /**
         * 按勾选重写首发载荷（纯函数，T-FeedbackBatch）：≥2 条勾选时标题/描述改为批次形态
         * （`等 N 条异常` + 条目清单），并把堆栈附件换成多节合并附件；1 条或 0 条时原样返回。
         */
        internal fun composeSubmission(
            submission: FeedbackApi.Submission,
            selected: List<BatchSubmitter.BatchItem>,
        ): FeedbackApi.Submission {
            if (selected.size < 2) return submission
            val entries = selected.map { item ->
                ErrorReportComposer.BatchEntry(item.title, item.message, item.stackTraceText)
            }
            val request = submission.request.copy(
                title = ErrorReportComposer.buildBatchTitle(entries),
                description = ErrorReportComposer.buildBatchDescription(entries, submission.request.description),
            )
            return BatchSubmitter.composeFirstSubmission(
                submission.copy(request = request),
                selected,
            )
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

    val descriptionArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.setText(MpSqlVisualizerBundle.message("error.report.dialog.description.placeholder"))
    }

    val attachStackCheck = JCheckBox().apply {
        text = MpSqlVisualizerBundle.message("error.report.dialog.attachStack")
        toolTipText = MpSqlVisualizerBundle.message("error.report.dialog.attachStack.tip")
    }

    /** 手动形态勾选行右侧「从 IDE 错误池导入」（T-FeedbackDlg3）；错误形态隐藏（预览已预填）。 */
    val stackImportButton = javax.swing.JButton(
        MpSqlVisualizerBundle.message("error.report.dialog.stackImport"),
    ).apply {
        toolTipText = MpSqlVisualizerBundle.message("error.report.dialog.stackImport.tip")
        isVisible = false
    }

    /** 堆栈区：错误形态只读预览；手动形态可编辑（粘贴/导入，T-FeedbackDlg3）。 */
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

        /**
     * 提交状态行（T-FeedbackErr 真机第二轮）：必须用可换行文本区而非 JBLabel——失败原因可能
     * 很长（如 Gson 排障链接、非 JSON 正文片段），JBLabel 单行渲染会把 GridBag 整行撑到上千 px，
     * 整个对话框被拉宽到 ~1900px（真机截图实证）。透明背景融入面板，preferred 宽 40 列封顶。
     */
    val statusLabel = JBTextArea(2, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }

    /** 状态行「复制失败信息」按钮（对齐 SQL 预览窗口失败信息交互，用户可直接复制完整原因反馈）。 */
    val statusCopyButton = javax.swing.JButton(MpSqlVisualizerBundle.message("error.report.dialog.status.copy"))

    /** 状态行复制行为由对话框注入（[statusCopyHandler]），字段类保持无逻辑（同 browserLink 模式）。 */
    var statusCopyHandler: () -> Unit = {}

    /** 批次条目列表（T-FeedbackBatch）：每个条目一个复选框，勾选态即该条的「附带堆栈」。 */
    val batchList = JPanel(java.awt.GridLayout(0, 1, 0, 2))

    /** 批次分节容器（默认隐藏；[ErrorReportDialog.batchItems] 注入后显示）。 */
    val batchSection = JPanel(BorderLayout(4, 2)).apply { isVisible = false }

    /** 当前勾选的批次条目（纯遍历，测试可断言）。 */
    fun selectedBatchItems(all: List<BatchSubmitter.BatchItem>): List<BatchSubmitter.BatchItem> =
        all.filterIndexed { index, _ ->
            (batchList.getComponent(index) as? JCheckBox)?.isSelected ?: false
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
