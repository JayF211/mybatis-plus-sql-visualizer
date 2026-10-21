// 自 master ui/PreviewDegradationReason.kt 移植（T-DegradedPreviewUx P0：带原因的预热失败）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-DegradedPreviewUx P0：带原因的预热失败。
 *
 * <p>只在 {@code prewarmPreviewTempFile} 的三条已知失败分支使用，让消费侧
 * （{@code resolvePreviewEditorSource} 的 onFallback / 热态取产物）不必再靠字符串猜原因；
 * 未归类的异常仍按 {@link PreviewDegradationReason#PREWARM_ERROR} 兜底。</p>
 */
public class PreviewPrewarmException extends IllegalStateException {

    private final PreviewDegradationReason reason;

    public PreviewPrewarmException(@NotNull PreviewDegradationReason reason, @NotNull String message) {
        this(reason, message, null);
    }

    public PreviewPrewarmException(@NotNull PreviewDegradationReason reason, @NotNull String message,
                                   @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    @NotNull
    public PreviewDegradationReason getReason() {
        return reason;
    }
}
