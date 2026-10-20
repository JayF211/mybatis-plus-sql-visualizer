package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * PrepareSingleFlightGate.kt 顶层辅助函数的 Java 承载（端口约定：Kotlin 顶层函数 → {@code XxxKt}）。
 */
public final class PrepareSingleFlightGateKt {

    private PrepareSingleFlightGateKt() {
    }

    /** 准备失败可能发生在消费排队请求之前或之后，两种形态都结算当前和排队请求。 */
    public static void failPrepareRequests(@NotNull PrepareSingleFlightGate gate,
                                           @Nullable QueuedExecuteIntent expectedOwner,
                                           @Nullable Consumer<ExecutionSubmission> current,
                                           @Nullable Consumer<String> currentError,
                                           @Nullable QueuedExecuteIntent consumed,
                                           @NotNull String message) {
        if (expectedOwner != null) {
            gate.abort(expectedOwner, ExecutionSubmission.BLOCKED, message);
        } else {
            if (current != null) {
                current.accept(ExecutionSubmission.BLOCKED);
            }
            if (currentError != null) {
                currentError.accept(message);
            }
        }
        if (consumed != null) {
            consumed.complete(ExecutionSubmission.BLOCKED, message);
        }
    }

    /** 后到请求替代当前显式执行，当前不能借用后到请求的 submitted。 */
    @Nullable
    public static Consumer<ExecutionSubmission> selectPrepareSubmission(
            @Nullable QueuedExecuteIntent queued,
            @Nullable Consumer<ExecutionSubmission> current) {
        if (queued == null) {
            return current;
        }
        if (current != null) {
            current.accept(ExecutionSubmission.CANCELLED);
        }
        return queued.onSubmission();
    }
}
