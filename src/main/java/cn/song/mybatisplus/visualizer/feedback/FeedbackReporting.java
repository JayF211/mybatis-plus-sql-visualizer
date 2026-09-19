package cn.song.mybatisplus.visualizer.feedback;

import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.SafeBrowser;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * 报告提交编排（T-ErrReport-04/05）：错误通道（IdeErrorReportSubmitter）与手动入口
 * （Help 菜单 / 设置页按钮）共用的后台提交 + 结果通知 + 手动对话框打开。
 *
 * <p>网络一律后台线程；回调 {@code invokeLater} 显式 {@link ModalityState#any()}——报告对话框/设置页
 * 模态存续期挂起默认 modality 的回调（设置页反馈红线，真机实证）。
 *
 * <p>T-FeedbackErr（2026-09-14 真机）：提交结果不再只有 toast——对话框保持打开并内联展示
 * 「提交中 / 失败原因（服务端文案）/ 重试按钮」，失败时用户能当场改内容再试；
 * 附件上传失败降级为「不带附件提交」并另行提示，不再连坐整帖。
 */
public final class FeedbackReporting {

    /** Kotlin internal object 单例等价。 */
    public static final FeedbackReporting INSTANCE = new FeedbackReporting();

    private FeedbackReporting() {
    }

    /**
     * 后台提交（任何线程可调）；完成后 EDT 回调 {@code onResult}。
     * 网络异常折入 {@link FeedbackApi.SubmitResult.Failure}，不抛出。
     *
     * <p>T-FeedbackErr：回调升格为 {@link FeedbackApi.SubmissionOutcome}（结果 + 附件降级提示），
     * 并新增 {@code onProgress}——提交进度由调用方决定怎么显示（对话框内联状态行），
     * 真机教训是「提交中/提交失败」全靠 toast，用户看不到任何可操作的东西。
     */
    public static void submitInBackground(FeedbackApi.Submission submission,
                                          Consumer<FeedbackApi.SubmissionOutcome> onResult) {
        submitInBackground(submission, progress -> { }, onResult);
    }

    /** 后台提交（带进度回调的完整形态；进度结束后以 {@code false} 收尾）。 */
    public static void submitInBackground(FeedbackApi.Submission submission,
                                          Consumer<Boolean> onProgress,
                                          Consumer<FeedbackApi.SubmissionOutcome> onResult) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            FeedbackApi.SubmissionOutcome outcome;
            try {
                outcome = FeedbackApi.submit(submission);
            } catch (Throwable t) {
                outcome = new FeedbackApi.SubmissionOutcome(
                        new FeedbackApi.SubmitResult.Failure(t.getMessage() != null ? t.getMessage() : "submit failed"),
                        null);
            }
            FeedbackApi.SubmissionOutcome finalOutcome = outcome;
            // write-safe: UI-only（纯 Swing/只读，无文档写）
            ApplicationManager.getApplication().invokeLater(() -> {
                onProgress.accept(false);
                onResult.accept(finalOutcome);
            }, ModalityState.any());
        });
    }

    /** 提交结果 toast：成功带 FR 编号与「查看详情」跳转，失败透出原因。 */
    public static void notifyResult(@Nullable Project project, FeedbackApi.SubmitResult result) {
        com.intellij.notification.NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup("cn.song.mybatisplus.visualizer.feedback");
        if (result instanceof FeedbackApi.SubmitResult.Success) {
            FeedbackApi.SubmitResult.Success success = (FeedbackApi.SubmitResult.Success) result;
            group.createNotification(
                            MpSqlVisualizerBundle.message("feedback.notify.success.title"),
                            MpSqlVisualizerBundle.message("feedback.notify.success.content", success.getPostNo()),
                            NotificationType.INFORMATION)
                    .addAction(new AnAction(MpSqlVisualizerBundle.message("feedback.notify.view")) {
                        @Override
                        public void actionPerformed(@org.jetbrains.annotations.NotNull AnActionEvent e) {
                            SafeBrowser.browse(FeedbackApi.detailUrl(success.getPostNo()));
                        }
                    })
                    .notify(project);
            return;
        }
        if (result instanceof FeedbackApi.SubmitResult.Failure) {
            group.createNotification(
                            MpSqlVisualizerBundle.message("feedback.notify.failed.title"),
                            ((FeedbackApi.SubmitResult.Failure) result).getMessage(),
                            NotificationType.ERROR)
                    .notify(project);
        }
    }

    /** 附件降级提示 toast（T-FeedbackErr）：帖子已发出、但堆栈附件没带上。 */
    public static void notifyAttachmentWarning(@Nullable Project project, String warning) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("cn.song.mybatisplus.visualizer.feedback")
                .createNotification(
                        MpSqlVisualizerBundle.message("feedback.notify.attachmentSkipped.title"),
                        warning,
                        NotificationType.WARNING)
                .notify(project);
    }

    /** 手动入口（Help 菜单 / 设置页按钮）：打开手动形态报告框，提交后结果走通知。
     *  堆栈区可编辑（自主粘贴，T-FeedbackDlg3）；错误池候选已随 T-MarketInternal 摘除——
     *  MessagePool 系在 262 标 @ApiStatus.Internal，Marketplace 阻断。 */
    public static void openManualDialog() {
        openManualDialog(null);
    }

    /** 手动入口（{@code project = null} 默认参数的显式重载）。 */
    public static void openManualDialog(@Nullable Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ErrorReportDialog dialog = new ErrorReportDialog(
                    null,
                    null,
                    null,
                    ideVersion(),
                    PluginVersion.current(),
                    (submission, selectedItems, onResult) ->
                            submitInBackground(submission, onResult::accept));
            dialog.show();
        });
    }

    /** 报告结果通知（对话框内联状态行的共用呈现，T-FeedbackErr）：成功/失败都落到项目通知组。 */
    public static void notifyOutcome(@Nullable Project project, FeedbackApi.SubmissionOutcome outcome) {
        // 附件降级提醒只在成功语境成立（标题是「反馈已提交（未附堆栈）」）——失败时该提示
        // 与失败 toast 同屏自相矛盾（真机第二轮实证），失败现场的状态行/toast 已含附件原因
        if (outcome.getResult() instanceof FeedbackApi.SubmitResult.Success
                && outcome.getWarning() != null) {
            notifyAttachmentWarning(project, outcome.getWarning());
        }
        notifyResult(project, outcome.getResult());
    }

    /**
     * T-MarketInternal（2026-09-14）：{@code pluginErrorPoolStacks()} / {@code isPluginError()} 整体删除。
     * 读取 IDE 错误池必须走 {@code MessagePool} / {@code AbstractMessage}，二者在 262 平台标
     * {@code @ApiStatus.Internal}（243 无标注——编译基准看不见，verifyMinimal 才暴露），
     * Plugin Verifier 报 6 条 Internal API，Marketplace 审核阻断级。平台侧无公开替代
     * （整条诊断链含 ErrorReportSubmitter/IdeErrorsDialog 均 Internal），错误池候选体验
     * 无合规实现路径，手动形态收敛为自主粘贴（来源引导见堆栈区占位文案）。
     */

    /** 报告时间戳（批量组装与单条附件共用同一格式）。 */
    public static String nowStamp() {
        return java.time.ZonedDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"));
    }

    /** IDE 版本描述（如 {@code IntelliJ IDEA 2024.3.7.1 Ultimate}）；平台读取失败返回 null。 */
    public static @Nullable String ideVersion() {
        try {
            return ApplicationInfo.getInstance().getFullApplicationName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
