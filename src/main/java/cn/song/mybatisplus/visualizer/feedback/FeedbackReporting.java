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
 */
public final class FeedbackReporting {

    /** Kotlin internal object 单例等价。 */
    public static final FeedbackReporting INSTANCE = new FeedbackReporting();

    private FeedbackReporting() {
    }

    /**
     * 后台提交（任何线程可调）；完成后 EDT 回调 {@code onResult}。
     * 网络异常折入 {@link FeedbackApi.SubmitResult.Failure}，不抛出。
     */
    public static void submitInBackground(FeedbackApi.Submission submission,
                                          Consumer<FeedbackApi.SubmitResult> onResult) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            FeedbackApi.SubmitResult result;
            try {
                result = FeedbackApi.submit(submission);
            } catch (Throwable t) {
                result = new FeedbackApi.SubmitResult.Failure(t.getMessage() != null ? t.getMessage() : "submit failed");
            }
            FeedbackApi.SubmitResult finalResult = result;
            ApplicationManager.getApplication().invokeLater(
                    () -> onResult.accept(finalResult),
                    ModalityState.any());
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

    /** 手动入口（Help 菜单 / 设置页按钮）：打开手动形态报告框，提交后结果走通知。 */
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
                    submission -> submitInBackground(submission, result -> notifyResult(project, result)));
            dialog.show();
        });
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
