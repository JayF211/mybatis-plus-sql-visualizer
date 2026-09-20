package cn.song.mybatisplus.visualizer.feedback

/**
 * 批量报告提交编排（T-FeedbackBatch，2026-09-14 用户定案「不能被轻易限流」）。
 *
 * 用户在 IDE 错误列表里勾选 N 条异常后，服务端 **5 次/小时/IP** 的限流决定了逐条提交必然
 * 撞 429。本对象把「首发 + 其余条目」的分流策略收在一处（纯逻辑，网络经 [FeedbackApi] seam）：
 *
 * | 条目数 | 策略 | 服务端请求数 |
 * |--------|------|--------------|
 * | 1 | 原样单帖（带堆栈附件） | ≤2（附件+帖子） |
 * | 2~[ErrorReportComposer.BATCH_MERGE_MAX] | **合并成一条帖子**：描述出清单、附件收全文 | ≤2 |
 * | 更大 | 首发只带第一条 + **合并附件**（含全部条目），其余副本交 [PendingReportScheduler] 错峰补发 | ≤2 + N-1 错峰 |
 *
 * 首发失败（额度用尽/网络）时不排后续——首发都发不出去，排队只会连环失败。
 */
object BatchSubmitter {

    /**
     * 对话框里的一条批量条目：内容 + 是否附带堆栈（勾选默认开，同单条形态）。
     *
     * T-ErrorReportRedact（D1=B）：[includeMessage] = 该条目的 message 全文是否随帖子外传。
     * 默认 **false**（脱敏形态：标题/描述只出异常类名）——由对话框的「附带异常消息全文」
     * 开关在收集时统一回写（与 [attachStack] 同款的逐条传输策略标记）。
     */
    class BatchItem(
        val title: String,
        val message: String?,
        val stackTraceText: String?,
        var attachStack: Boolean = true,
        var includeMessage: Boolean = false,
    )

    /**
     * 首发提交 + 大条目数时的错峰补发。
     * [onResult] 只回调**首发**结果（用户在看的那一次）；补发结果不再打扰用户（另有队列语义）。
     */
    fun submit(
        submission: FeedbackApi.Submission,
        batchItems: List<BatchItem>,
        onResult: (FeedbackApi.SubmissionOutcome) -> Unit,
    ) {
        val queued = followUpSubmissions(submission, batchItems)
        FeedbackReporting.submitInBackground(submission) { outcome ->
            onResult(outcome)
            // 首发成功才排后续（限额/网络问题下排队无意义，只会连环失败）
            if (outcome.result is FeedbackApi.SubmitResult.Success) {
                PendingReportScheduler.scheduleQuietly(queued)
            }
        }
    }

    /**
     * 首发载荷重写（纯函数）：多条时把堆栈附件换成**合并附件**（全部条目分节），
     * 让同一条帖子自带全部现场；单条时原样返回。
     */
    internal fun composeFirstSubmission(
        submission: FeedbackApi.Submission,
        batchItems: List<BatchItem>,
    ): FeedbackApi.Submission {
        if (batchItems.size <= 1) return submission
        val attachment = submission.stackAttachment ?: return submission
        val stacks = batchItems.filter { it.attachStack }.map { it.stackTraceText ?: "" }
        if (stacks.none { it.isNotBlank() }) return submission.copy(stackAttachment = null)
        val merged = ErrorReportComposer.buildStackAttachment(
            stacks,
            ideVersion = submission.request.ideVersion,
            pluginVersion = submission.request.pluginVersion,
            fingerprint = null,
            occurredAt = FeedbackReporting.nowStamp(),
        )
        return submission.copy(
            stackAttachment = FeedbackApi.StackAttachment(
                FeedbackApi.STACK_ATTACHMENT_NAME,
                merged.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * 错峰补发列表（纯函数）：条目数超过 [ErrorReportComposer.BATCH_MERGE_MAX] 时，
     * 除第一条外的条目各成一条帖子（标题/描述按单条形态组装、附件按各自勾选），交给队列。
     */
    internal fun followUpSubmissions(
        submission: FeedbackApi.Submission,
        batchItems: List<BatchItem>,
    ): List<FeedbackApi.Submission> {
        if (batchItems.size <= ErrorReportComposer.BATCH_MERGE_MAX) return emptyList()
        return batchItems.drop(1).map { item -> submissionFor(submission, item) }
    }

    /**
     * 批量条目 → 独立帖子载荷（纯函数）：沿用首发的时间/版本上下文，标题描述各自组装。
     *
     * T-ErrorReportRedact（D1=B）：[BatchItem.includeMessage] 决定该条帖子走哪种形态——
     * false（默认）走脱敏形态（标题只出类名、描述出类名 + 栈帧摘要 + cause 链），
     * true 时逐字节等于改前形态（标题 `类名: 消息首行`、描述 message 全文）。
     * 附件与消息开关无关（各自条目的 [BatchItem.attachStack] 独立决定）。
     */
    internal fun submissionFor(
        template: FeedbackApi.Submission,
        item: BatchItem,
    ): FeedbackApi.Submission {
        val request = template.request.copy(
            title = if (item.includeMessage) {
                ErrorReportComposer.buildTitle(item.title, item.message)
            } else {
                ErrorReportComposer.buildRedactedTitle(item.title)
            },
            description = if (item.includeMessage) {
                ErrorReportComposer.buildDescription(item.message, userNotes = null, fingerprint = null)
            } else {
                ErrorReportComposer.buildRedactedDescription(
                    item.title,
                    item.stackTraceText,
                    messageOmitted = !item.message.isNullOrBlank(),
                )
            },
            category = ErrorReportComposer.guessCategory(item.stackTraceText),
        )
        val stack = item.stackTraceText?.takeIf { it.isNotBlank() && item.attachStack }
        val attachment = stack?.let {
            FeedbackApi.StackAttachment(
                FeedbackApi.STACK_ATTACHMENT_NAME,
                ErrorReportComposer.buildStackAttachment(
                    it,
                    request.ideVersion,
                    request.pluginVersion,
                    fingerprint = null,
                    occurredAt = FeedbackReporting.nowStamp(),
                ).toByteArray(Charsets.UTF_8),
            )
        }
        return FeedbackApi.Submission(request, attachment)
    }
}