package cn.song.mybatisplus.visualizer.feedback;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 批量报告提交编排（T-FeedbackBatch，2026-09-14 用户定案「不能被轻易限流」）。
 *
 * <p>用户在 IDE 错误列表里勾选 N 条异常后，服务端 <b>5 次/小时/IP</b> 的限流决定了逐条提交必然
 * 撞 429。本对象把「首发 + 其余条目」的分流策略收在一处（纯逻辑，网络经 {@link FeedbackApi} seam）：
 *
 * <p>| 条目数 | 策略 | 服务端请求数 |
 * <br>|--------|------|--------------|
 * <br>| 1 | 原样单帖（带堆栈附件） | ≤2（附件+帖子） |
 * <br>| 2~{@link ErrorReportComposer#BATCH_MERGE_MAX} | <b>合并成一条帖子</b>：描述出清单、附件收全文 | ≤2 |
 * <br>| 更大 | 首发只带第一条 + <b>合并附件</b>（含全部条目），其余副本交 {@link PendingReportScheduler} 错峰补发 | ≤2 + N-1 错峰 |
 *
 * <p>首发失败（额度用尽/网络）时不排后续——首发都发不出去，排队只会连环失败。
 */
public final class BatchSubmitter {

    private BatchSubmitter() {
    }

    /**
     * 批量合并阈值（≤ 此条数合成一条帖子）：单一事实源在 {@link ErrorReportComposer#BATCH_MERGE_MAX}
     * （值同源——服务端 5 次/小时/IP）；本常量仅为既有引用保留镜像。
     */
    public static final int BATCH_MERGE_MAX = ErrorReportComposer.BATCH_MERGE_MAX;

    /** 对话框里的一条批量条目：内容 + 是否附带堆栈（勾选默认开，同单条形态）。 */
    public static final class BatchItem {
        private final String title;
        private final String message;
        private final String stackTraceText;
        private boolean attachStack;

        public BatchItem(String title, String message, String stackTraceText) {
            this(title, message, stackTraceText, true);
        }

        public BatchItem(String title, String message, String stackTraceText, boolean attachStack) {
            this.title = title;
            this.message = message;
            this.stackTraceText = stackTraceText;
            this.attachStack = attachStack;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public String getStackTraceText() {
            return stackTraceText;
        }

        public boolean getAttachStack() {
            return attachStack;
        }

        public void setAttachStack(boolean attachStack) {
            this.attachStack = attachStack;
        }
    }

    /**
     * 首发提交 + 大条目数时的错峰补发。
     * {@code onResult} 只回调<b>首发</b>结果（用户在看的那一次）；补发结果不再打扰用户（另有队列语义）。
     */
    public static void submit(FeedbackApi.Submission submission,
                              List<BatchItem> batchItems,
                              Consumer<FeedbackApi.SubmissionOutcome> onResult) {
        List<FeedbackApi.Submission> queued = followUpSubmissions(submission, batchItems);
        FeedbackReporting.submitInBackground(submission, outcome -> {
            onResult.accept(outcome);
            // 首发成功才排后续（限额/网络问题下排队无意义，只会连环失败）
            if (outcome.getResult() instanceof FeedbackApi.SubmitResult.Success) {
                PendingReportScheduler.scheduleQuietly(queued);
            }
        });
    }

    /**
     * 首发载荷重写（纯函数）：多条时把堆栈附件换成<b>合并附件</b>（全部条目分节），
     * 让同一条帖子自带全部现场；单条时原样返回。
     */
    public static FeedbackApi.Submission composeFirstSubmission(FeedbackApi.Submission submission,
                                                                List<BatchItem> batchItems) {
        if (batchItems.size() <= 1) {
            return submission;
        }
        FeedbackApi.StackAttachment attachment = submission.getStackAttachment();
        if (attachment == null) {
            return submission;
        }
        List<String> stacks = new ArrayList<>();
        for (BatchItem item : batchItems) {
            if (item.getAttachStack()) {
                stacks.add(item.getStackTraceText() != null ? item.getStackTraceText() : "");
            }
        }
        boolean anyNotBlank = false;
        for (String stack : stacks) {
            if (!stack.isBlank()) {
                anyNotBlank = true;
                break;
            }
        }
        if (!anyNotBlank) {
            return submission.copy(submission.getRequest(), null);
        }
        // T-FeedbackBatch：合并附件走 buildStackAttachment List 重载——按条分节（#N 标题 + 全文），
        // 均分截断上限；不再拼接单串（原 joinStacks 适配已回收）
        String merged = ErrorReportComposer.buildStackAttachment(
                stacks,
                submission.getRequest().getIdeVersion(),
                submission.getRequest().getPluginVersion(),
                null,
                FeedbackReporting.nowStamp());
        return submission.copy(
                submission.getRequest(),
                new FeedbackApi.StackAttachment(
                        FeedbackApi.STACK_ATTACHMENT_NAME,
                        merged.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 错峰补发列表（纯函数）：条目数超过 {@link ErrorReportComposer#BATCH_MERGE_MAX} 时，
     * 除第一条外的条目各成一条帖子（标题/描述按单条形态组装、附件按各自勾选），交给队列。
     */
    public static List<FeedbackApi.Submission> followUpSubmissions(FeedbackApi.Submission submission,
                                                                   List<BatchItem> batchItems) {
        if (batchItems.size() <= ErrorReportComposer.BATCH_MERGE_MAX) {
            return List.of();
        }
        List<FeedbackApi.Submission> followUps = new ArrayList<>();
        for (int i = 1; i < batchItems.size(); i++) {
            followUps.add(submissionFor(submission, batchItems.get(i)));
        }
        return followUps;
    }

    /** 批量条目 → 独立帖子载荷（纯函数）：沿用首发的时间/版本上下文，标题描述各自组装。 */
    public static FeedbackApi.Submission submissionFor(FeedbackApi.Submission template, BatchItem item) {
        FeedbackApi.PostRequest templateRequest = template.getRequest();
        FeedbackApi.PostRequest request = templateRequest.copy(
                ErrorReportComposer.buildTitle(item.getTitle(), item.getMessage()),
                ErrorReportComposer.buildDescription(item.getMessage(), null, null),
                templateRequest.getType(),
                ErrorReportComposer.guessCategory(item.getStackTraceText()),
                templateRequest.getSubmitterName(),
                templateRequest.getSubmitterEmail(),
                templateRequest.isAnonymous(),
                templateRequest.getSource(),
                templateRequest.getIdeVersion(),
                templateRequest.getPluginVersion(),
                templateRequest.getAttachments());
        String stack = item.getStackTraceText();
        if (stack != null && (stack.isBlank() || !item.getAttachStack())) {
            stack = null;
        }
        FeedbackApi.StackAttachment attachment = null;
        if (stack != null) {
            attachment = new FeedbackApi.StackAttachment(
                    FeedbackApi.STACK_ATTACHMENT_NAME,
                    ErrorReportComposer.buildStackAttachment(
                            stack,
                            request.getIdeVersion(),
                            request.getPluginVersion(),
                            null,
                            FeedbackReporting.nowStamp()).getBytes(StandardCharsets.UTF_8));
        }
        return new FeedbackApi.Submission(request, attachment);
    }
}