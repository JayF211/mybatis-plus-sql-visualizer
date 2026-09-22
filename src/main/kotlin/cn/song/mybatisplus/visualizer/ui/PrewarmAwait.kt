package cn.song.mybatisplus.visualizer.ui

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T-PerfTail #2：预热产物等待的异步化载体（消除原 `awaitPrewarmedTempFile` 的 EDT
 * `Thread.sleep` 轮询形态）。预热线程产出本载体的完成态，消费侧两分支：
 *  - **已就绪（热态）**：`joinOrNull` 同步取产物，零等待（主路径行为不变）；
 *  - **未就绪（冷启动）**：`scheduleOnce` 注册完成回调——超时预算语义保留
 *    （`completeOnTimeout`，与 orTimeout 等价实现），完成/失败/超时统一经 [dispatcher]
 *    派发回 EDT 消费，EDT 零阻塞。超时与失败同落 onFallback（T-PreviewExecDs
 *    三级降级链与快速降级语义保留）。
 *
 * 纯调度逻辑、与平台解耦（dispatcher 注入）：headless 可模拟预热线程做三分支回调时序断言。
 */
internal class PrewarmAwait<T : Any>(
    /** 超时预算毫秒（生产 = SqlPreviewPanel.PREWARM_WAIT_MS）。 */
    private val timeoutMs: Long,
    /**
     * 回调派发通道：生产 = EDT `invokeLater(写安全模态 defaultModalityState())`；测试 = 同步直调或记录。
     *
     * T-FixN1（N1）：生产模态由 `ModalityState.any()` 改 `defaultModalityState()`——回调体（装配）
     * **会写文档**，而 `any()` 恒 write-unsafe（平台契约见 `execute/DocWriteContext` KDoc），
     * 写文档必触发 `Write-unsafe context!`。代价是模态对话框挂起期间派发推迟到模态关闭。
     */
    private val dispatcher: (Runnable) -> Unit,
) {

    private val future = CompletableFuture<T>()

    private val scheduled = AtomicBoolean()

    /**
     * T-PrewarmLateRecover F1：迟到产物接收方——消费侧已放弃（超时以 null 完成 / 失败完成）之后
     * 产物才到达时，由 [complete] 同步回调（**在预热线程上**，线程归位由登记方负责）。
     * 与 [scheduleOnce] 的 onFallback 互补：onFallback 负责「先装配成降级态」，本通道负责
     * 「产物随后到了再换装」。
     */
    @Volatile
    private var lateProductListener: ((T) -> Unit)? = null

    /**
     * 预热线程成功 → 完成。
     *
     * @return true = 本次交付给了消费侧（[scheduleOnce] 的 onReady 或热态 [joinOrNull]）；
     *   false = 消费侧已放弃，产物转入 [onLateProduct] 通道（无人登记则丢弃，语义同改动前）。
     */
    fun complete(value: T): Boolean {
        val delivered = future.complete(value)
        if (!delivered) lateProductListener?.invoke(value)
        return delivered
    }

    /** 预热线程判定失败 → 快速降级（原 prewarmFailed 标志同语义）。 */
    fun fail(error: Throwable) {
        future.completeExceptionally(error)
    }

    /** 是否已出结果（成功或失败；区分用 [joinOrNull]）。 */
    fun isDone(): Boolean = future.isDone

    /** 同步取产物：已就绪且成功 → 产物；失败 → null；未就绪 → null（不等待）。 */
    fun joinOrNull(): T? = if (future.isDone) runCatching { future.join() }.getOrNull() else null

    /**
     * 注册异步消费回调（幂等——仅首次注册生效，重复调用不重复派发）。
     * `completeOnTimeout(null, timeoutMs)` 给等待加超时预算：超时后回调以 null 完成，
     * 原预热线程稍后完成不再触发二次派发（一次性语义）。
     *
     * @return 是否由本次调用注册（false = 已有先前注册在挂起）
     */
    fun scheduleOnce(onReady: (T) -> Unit, onFallback: () -> Unit): Boolean {
        if (!scheduled.compareAndSet(false, true)) return false
        future.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete { value, _ ->
                dispatcher {
                    if (value != null) onReady(value) else onFallback()
                }
            }
        return true
    }

    /**
     * T-PrewarmLateRecover F1：登记迟到产物接收方（覆盖式——面板每发起一轮预热登记一次）。
     * 登记须在预热线程可能调用 [complete] 之前完成（[SqlPreviewPanel.prewarmPreviewTempFile]
     * 在构造 await 之后立刻登记，早于任何超时可达时刻）。
     */
    fun onLateProduct(listener: (T) -> Unit) {
        lateProductListener = listener
    }

    /**
     * 同步有界等待（headless 测试模式专用后门——单元测试无真实 EDT 泵，需保留
     * 「showResult 返回时编辑器即就绪」的既有测试语义；生产路径不走此方法）。
     * 超时或失败返回 null。
     */
    fun awaitOrNullForTest(): T? {
        // T-PrewarmLateRecover F1：测试后门同样武装超时预算——否则「超时降级后产物迟到」这一态
        // 在 headless 下不可达（`get` 超时不会完成 future，迟到产物会被判成「首次交付」），
        // 同一份代码在测试与生产上就分叉在这条通道上。语义与生产 scheduleOnce 一致。
        future.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS)
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }
}
