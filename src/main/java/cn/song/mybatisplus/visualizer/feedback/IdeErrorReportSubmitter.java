package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.ErrorReportSubmitter;
import com.intellij.openapi.diagnostic.IdeaLoggingEvent;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.util.Consumer;
import java.awt.Component;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * errorHandler EP 实现（T-ErrReport-04）：IDE Internal Errors 对话框对本插件错误的
 * 「报告」按钮接官网需求池。
 *
 * <p>平台按错误归属插件解析本 EP（IdeErrorsDialog.getSubmitter(Throwable, PluginId)，
 * {@code com.intellij.errorHandler}，dynamic——243 平台 jar javap 实证，见开发计划 §四）；
 * 无需自行过滤事件来源。Freeze 事件已被归属插件（真机截图实证），同通道可达。
 *
 * <p>流程：指纹 24h 去重（命中直接 DUPLICATE 回调、不弹框）→ EDT 弹报告确认框 →
 * 用户确认后后台提交（{@link FeedbackReporting#submitInBackground}）→
 * consumer 回调 NEW_ISSUE（带 FR 编号与详情页）/ FAILED。
 */
public class IdeErrorReportSubmitter extends ErrorReportSubmitter {

    /** 弹框+提交流程 seam 的函数形态（测试注入捕获；生产 {@link #openDialogAndSubmit}）。 */
    @FunctionalInterface
    public interface OpenReportFlow {
        void open(ErrorReportComposer.Prefill prefill,
                  @Nullable String stackText,
                  String fingerprint,
                  Consumer<? super SubmittedReportInfo> consumer);
    }

    /** 去重判定 seam（测试注入；生产走 ErrorFingerprint 24h 窗口）。 */
    public volatile Predicate<String> dedupeGate = ErrorFingerprint::seenRecently;

    /** 弹框+提交流程 seam（测试注入捕获；生产 {@link #openDialogAndSubmit}）。 */
    public volatile OpenReportFlow openReportFlow = this::openDialogAndSubmit;

    @Override
    public @NotNull String getReportActionText() {
        return MpSqlVisualizerBundle.message("error.report.action");
    }

    @Override
    public String getPrivacyNoticeText() {
        return MpSqlVisualizerBundle.message("error.report.privacy");
    }

    @Override
    public boolean submit(@NotNull IdeaLoggingEvent[] events,
                          @Nullable String additionalInfo,
                          @NotNull Component parentComponent,
                          @NotNull Consumer<? super SubmittedReportInfo> consumer) {
        if (events.length == 0) {
            return false;
        }
        IdeaLoggingEvent event = events[0];
        String stackText = event.getThrowableText();
        String throwableClassName = event.getThrowable() != null ? event.getThrowable().getClass().getName() : null;
        String fingerprint = ErrorFingerprint.of(throwableClassName, stackText);
        if (dedupeGate.test(fingerprint)) {
            consumer.consume(new SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.DUPLICATE));
            return true;
        }
        ErrorReportComposer.Prefill prefill = ErrorReportComposer.prefill(throwableClassName, event.getMessage(), stackText);
        openReportFlow.open(prefill, stackText != null && !stackText.isBlank() ? stackText : null, fingerprint, consumer);
        return true;
    }

    /** 生产流程：EDT 弹确认框，确认后记指纹 + 后台提交 + consumer 回调。 */
    private void openDialogAndSubmit(ErrorReportComposer.Prefill prefill,
                                     @Nullable String stackText,
                                     String fingerprint,
                                     Consumer<? super SubmittedReportInfo> consumer) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ErrorReportDialog dialog = new ErrorReportDialog(
                    prefill,
                    stackText,
                    fingerprint,
                    FeedbackReporting.ideVersion(),
                    PluginVersion.current(),
                    submission -> {
                        ErrorFingerprint.record(fingerprint);
                        FeedbackReporting.submitInBackground(submission, result -> {
                            consumer.consume(reportInfo(result));
                            FeedbackReporting.notifyResult(null, result);
                        });
                    });
            dialog.show();
        });
    }

    /** 提交结果 → 平台报告信息（纯函数，internal 供测试）。 */
    public SubmittedReportInfo reportInfo(FeedbackApi.SubmitResult result) {
        if (result instanceof FeedbackApi.SubmitResult.Success) {
            FeedbackApi.SubmitResult.Success success = (FeedbackApi.SubmitResult.Success) result;
            return new SubmittedReportInfo(
                    FeedbackApi.detailUrl(success.getPostNo()),
                    success.getPostNo(),
                    SubmittedReportInfo.SubmissionStatus.NEW_ISSUE);
        }
        return new SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED);
    }
}
