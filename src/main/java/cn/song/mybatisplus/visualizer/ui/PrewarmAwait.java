// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/PrewarmAwait.kt 移植
// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/PrewarmAwait.kt 移植（T-FixN1 派发通道 KDoc：模态改 defaultModalityState 的理由）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * T-PerfTail #2：预热产物等待的异步化载体（消除原 `awaitPrewarmedTempFile` 的 EDT
 * `Thread.sleep` 轮询形态）。预热线程产出本载体的完成态，消费侧两分支：
 * <ul>
 *  <li>**已就绪（热态）**：{@link #joinOrNull} 同步取产物，零等待（主路径行为不变）；</li>
 *  <li>**未就绪（冷启动）**：{@link #scheduleOnce} 注册完成回调——超时预算语义保留
 *      （{@code completeOnTimeout}，与 orTimeout 等价实现），完成/失败/超时统一经
 *      {@code dispatcher} 派发回 EDT 消费，EDT 零阻塞。超时与失败同落 onFallback
 *      （T-PreviewExecDs 三级降级链与快速降级语义保留）。</li>
 * </ul>
 *
 * 纯调度逻辑、与平台解耦（dispatcher 注入）：headless 可模拟预热线程做三分支回调时序断言。
 */
public class PrewarmAwait<T> {

    /** 超时预算毫秒（生产 = SqlPreviewPanel.PREWARM_WAIT_MS）。 */
    private final long timeoutMs;

    /**
     * 回调派发通道：生产 = EDT `invokeLater(写安全模态 defaultModalityState())`；测试 = 同步直调或记录。
     *
     * <p>T-FixN1（N1）：生产模态由 `ModalityState.any()` 改 `defaultModalityState()`——回调体（装配）
     * <b>会写文档</b>，而 `any()` 恒 write-unsafe（平台契约见 `execute/DocWriteContext` KDoc），
     * 写文档必触发 `Write-unsafe context!`。代价是模态对话框挂起期间派发推迟到模态关闭。
     */
    private final Consumer<Runnable> dispatcher;

    private final CompletableFuture<T> future = new CompletableFuture<>();

    private final AtomicBoolean scheduled = new AtomicBoolean();

    public PrewarmAwait(long timeoutMs, Consumer<Runnable> dispatcher) {
        this.timeoutMs = timeoutMs;
        this.dispatcher = dispatcher;
    }

    /** 预热线程成功 → 完成。 */
    public void complete(T value) {
        future.complete(value);
    }

    /** 预热线程判定失败 → 快速降级（原 prewarmFailed 标志同语义）。 */
    public void fail(Throwable error) {
        future.completeExceptionally(error);
    }

    /** 是否已出结果（成功或失败；区分用 {@link #joinOrNull}）。 */
    public boolean isDone() {
        return future.isDone();
    }

    /** 同步取产物：已就绪且成功 → 产物；失败 → null；未就绪 → null（不等待）。 */
    @Nullable
    public T joinOrNull() {
        if (!future.isDone()) return null;
        try {
            return future.join();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 注册异步消费回调（幂等——仅首次注册生效，重复调用不重复派发）。
     * {@code completeOnTimeout(null, timeoutMs)} 给等待加超时预算：超时后回调以 null 完成，
     * 原预热线程稍后完成不再触发二次派发（一次性语义）。
     *
     * @return 是否由本次调用注册（false = 已有先前注册在挂起）
     */
    public boolean scheduleOnce(Consumer<T> onReady, Runnable onFallback) {
        if (!scheduled.compareAndSet(false, true)) return false;
        future.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((value, error) ->
                        dispatcher.accept(() -> {
                            if (value != null) onReady.accept(value);
                            else onFallback.run();
                        }));
        return true;
    }

    /**
     * 同步有界等待（headless 测试模式专用后门——单元测试无真实 EDT 泵，需保留
     * 「showResult 返回时编辑器即就绪」的既有测试语义；生产路径不走此方法）。
     * 超时或失败返回 null。
     */
    @Nullable
    public T awaitOrNullForTest() {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }
}
