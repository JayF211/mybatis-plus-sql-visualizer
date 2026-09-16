package cn.song.mybatisplus.visualizer.feedback

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * T-ErrReport-04：errorHandler EP 提交器编排单测（seam 注入，无 EDT/平台依赖）。
 * IdeaLoggingEvent(String, Throwable) 为公开构造（util jar）。
 *
 * T-FeedbackBatch2：262 平台「报告全部」逐簇调 submit、每次恒 1 条事件——
 * 提交器把去抖窗口内的调用累积合并成一波落框，此处覆盖合并/去重/复合回调/兜底时序。
 */
class IdeErrorReportSubmitterTest {

    private val throwable = RuntimeException("boom")
        .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Foo", "bar", "Foo.kt", 1)) }

    private fun event() = IdeaLoggingEvent("boom", throwable)

    /** 捕获 delay 与任务的假调度器（生产路径防真触网/真定时；测试直调 flush 或手动跑任务）。 */
    private class FakeScheduler : ScheduledExecutorService {
        var lastDelayMs: Long = -1
        var lastTask: Runnable? = null
        var scheduledCount = 0

        fun runPendingTask() {
            lastTask?.run()
            lastTask = null
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduledCount++
            lastDelayMs = unit.toMillis(delay)
            lastTask = command
            return doneFuture
        }

        override fun <V : Any?> schedule(callable: java.util.concurrent.Callable<V>?, delay: Long, unit: TimeUnit?): ScheduledFuture<V> =
            throw UnsupportedOperationException()

        override fun scheduleAtFixedRate(task: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()

        override fun scheduleWithFixedDelay(task: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()

        override fun shutdown() = throw UnsupportedOperationException()
        override fun shutdownNow(): MutableList<Runnable> = throw UnsupportedOperationException()
        override fun isShutdown(): Boolean = throw UnsupportedOperationException()
        override fun isTerminated(): Boolean = throw UnsupportedOperationException()
        override fun awaitTermination(timeout: Long, unit: TimeUnit?): Boolean = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: java.util.concurrent.Callable<T>?): java.util.concurrent.Future<T> = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: Runnable?, result: T?): java.util.concurrent.Future<T> = throw UnsupportedOperationException()
        override fun submit(task: Runnable?): java.util.concurrent.Future<*> = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out java.util.concurrent.Callable<T>>?): MutableList<java.util.concurrent.Future<T>> = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out java.util.concurrent.Callable<T>>?, timeout: Long, unit: TimeUnit?): MutableList<java.util.concurrent.Future<T>> = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out java.util.concurrent.Callable<T>>?): T = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out java.util.concurrent.Callable<T>>?, timeout: Long, unit: TimeUnit?): T = throw UnsupportedOperationException()
        override fun execute(command: Runnable?) = throw UnsupportedOperationException()

        companion object {
            val doneFuture = object : ScheduledFuture<Unit> {
                override fun getDelay(unit: TimeUnit): Long = 0
                override fun compareTo(other: Delayed): Int = 0
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
                override fun isCancelled(): Boolean = false
                override fun isDone(): Boolean = false
                override fun get(): Unit = Unit
                override fun get(timeout: Long, unit: TimeUnit?): Unit = Unit
            }
        }
    }

    private fun submitterWith(scheduler: FakeScheduler = FakeScheduler(), clock: () -> Long = { 0L }): IdeErrorReportSubmitter =
        IdeErrorReportSubmitter().apply {
            dedupeGate = { false }
            this.scheduler = scheduler
            this.clock = clock
        }

    @Test
    fun `无事件返回false且不弹框`() {
        val submitter = submitterWith()
        var flowCalled = false
        submitter.openReportFlow = { _, _, _, _, _ -> flowCalled = true }
        assertFalse(submitter.submit(emptyArray(), null, javax.swing.JPanel(), Consumer { }))
        assertFalse(flowCalled)
    }

    @Test
    fun `指纹命中去重_直接DUPLICATE回调且不弹框`() {
        val submitter = submitterWith()
        var flowCalled = false
        submitter.openReportFlow = { _, _, _, _, _ -> flowCalled = true }
        submitter.dedupeGate = { true }
        var received: SubmittedReportInfo? = null
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { received = it })
        assertTrue(accepted)
        assertFalse(flowCalled)
        assertEquals(SubmittedReportInfo.SubmissionStatus.DUPLICATE, received?.status)
    }

    @Test
    fun `单次调用_flush后流程收到预填堆栈与指纹`() {
        val submitter = submitterWith()
        var capturedPrefill: ErrorReportComposer.Prefill? = null
        var capturedStack: String? = null
        var capturedFingerprints: List<String>? = null
        var capturedBatch: List<BatchSubmitter.BatchItem>? = null
        submitter.openReportFlow = { prefill, stackText, fingerprints, batch, _ ->
            capturedPrefill = prefill
            capturedStack = stackText
            capturedFingerprints = fingerprints
            capturedBatch = batch
        }
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertTrue(accepted)
        submitter.flush()
        assertEquals("RuntimeException: boom", capturedPrefill?.title)
        assertTrue(capturedStack?.contains("Foo.bar") == true)
        val expected = ErrorFingerprint.of(throwable.javaClass.name, throwable.stackTraceToString())
        assertEquals(listOf(expected), capturedFingerprints)
        // T-FeedbackBatch：勾选的每条错误都作为批次条目传到对话框
        assertEquals(1, capturedBatch?.size)
        assertEquals("boom", capturedBatch?.first()?.message)
    }

    @Test
    fun `报告全部N次调用_合并为一次弹窗流程且consumer全数回调`() {
        val submitter = submitterWith()
        var flowCount = 0
        var capturedBatch: List<BatchSubmitter.BatchItem>? = null
        var capturedPrefill: ErrorReportComposer.Prefill? = null
        var composite: Consumer<in SubmittedReportInfo>? = null
        submitter.openReportFlow = { prefill, _, _, batch, consumers ->
            flowCount++
            capturedPrefill = prefill
            capturedBatch = batch
            composite = consumers
        }
        val received = mutableMapOf<Int, SubmittedReportInfo?>()
        (1..3).forEach { index ->
            val t = RuntimeException("boom $index")
                .apply {
                    stackTrace = arrayOf(
                        StackTraceElement("cn.song.mybatisplus.visualizer.log.Bar$index", "baz", "Bar.kt", index),
                    )
                }
            val accepted = submitter.submit(
                arrayOf(IdeaLoggingEvent("boom $index", t)),
                null,
                javax.swing.JPanel(),
                Consumer { received[index] = it },
            )
            assertTrue(accepted)
        }
        submitter.flush()
        assertEquals("三次独立 submit 只落一次框", 1, flowCount)
        assertEquals("批次条目=全部事件（T-FeedbackBatch 全含契约）", 3, capturedBatch?.size)
        assertEquals("主预填取首事件", "RuntimeException: boom 1", capturedPrefill?.title)
        // 复合 consumer：本波每个平台簇收到同一结果信息（提交结束后全部标记已提交）
        composite!!.consume(submitter.reportInfo(FeedbackApi.SubmitResult.Success("FR-0042")))
        assertEquals(setOf(1, 2, 3), received.keys)
        received.values.forEach { assertEquals(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE, it?.status) }
    }

    @Test
    fun `同波内指纹命中条目被剔除_其余照常合并`() {
        val submitter = submitterWith()
        val dedupFingerprint = ErrorFingerprint.of(throwable.javaClass.name, throwable.stackTraceToString())
        submitter.dedupeGate = { it == dedupFingerprint }
        val other = RuntimeException("other")
            .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Baz", "qux", "Baz.kt", 3)) }
        var dupReceived: SubmittedReportInfo? = null
        submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { dupReceived = it })
        var freshReceived: SubmittedReportInfo? = null
        submitter.submit(arrayOf(IdeaLoggingEvent("other", other)), null, javax.swing.JPanel(), Consumer { freshReceived = it })
        var capturedBatch: List<BatchSubmitter.BatchItem>? = null
        submitter.openReportFlow = { _, _, _, batch, _ -> capturedBatch = batch }
        submitter.flush()
        assertEquals(1, capturedBatch?.size)
        assertEquals("other", capturedBatch?.single()?.message)
        assertEquals("命中指纹的整条调用直接 DUPLICATE", SubmittedReportInfo.SubmissionStatus.DUPLICATE, dupReceived?.status)
        assertEquals("新鲜条目未提前回调（等提交结束走复合 consumer）", null, freshReceived)
    }

    @Test
    fun `多事件调用中部分去重_剔除后余量照常入队`() {
        val submitter = submitterWith()
        submitter.dedupeGate = { fingerprint -> fingerprint == ErrorFingerprint.of(throwable.javaClass.name, throwable.stackTraceToString()) }
        val other = RuntimeException("other")
            .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Baz", "qux", "Baz.kt", 3)) }
        var capturedBatch: List<BatchSubmitter.BatchItem>? = null
        submitter.openReportFlow = { _, _, _, batch, _ -> capturedBatch = batch }
        submitter.submit(arrayOf(event(), IdeaLoggingEvent("other", other)), null, javax.swing.JPanel(), Consumer { })
        submitter.flush()
        assertEquals(1, capturedBatch?.size)
        assertEquals("other", capturedBatch?.single()?.message)
    }

    @Test
    fun `去抖自最后一次调用起算_每次入队重排`() {
        val scheduler = FakeScheduler()
        val submitter = submitterWith(scheduler)
        submitter.openReportFlow = { _, _, _, _, _ -> }
        submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertEquals(IdeErrorReportSubmitter.DEBOUNCE_MS, scheduler.lastDelayMs)
        submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertEquals("第二次入队取消重排，窗口仍为 DEBOUNCE_MS", IdeErrorReportSubmitter.DEBOUNCE_MS, scheduler.lastDelayMs)
    }

    @Test
    fun `自首条起超时_强制立即flush`() {
        val scheduler = FakeScheduler()
        var now = 0L
        val submitter = submitterWith(scheduler) { now }
        submitter.openReportFlow = { _, _, _, _, _ -> }
        submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        now = IdeErrorReportSubmitter.MAX_WAIT_MS + 1
        submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertEquals("超兜底窗口的后续调用排 0ms 立即落框", 0L, scheduler.lastDelayMs)
    }

    @Test
    fun `空队列flush为无操作`() {
        val submitter = submitterWith()
        var flowCalled = false
        submitter.openReportFlow = { _, _, _, _, _ -> flowCalled = true }
        submitter.flush()
        assertFalse(flowCalled)
    }

    @Test
    fun `提交结果映射_Success为NEW_ISSUE并带详情链接`() {
        val submitter = submitterWith()
        val info = submitter.reportInfo(FeedbackApi.SubmitResult.Success("FR-0042"))
        assertEquals(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE, info.status)
        assertEquals("FR-0042", info.linkText)
        assertTrue(info.url.contains("postNo=FR-0042"))
    }

    @Test
    fun `提交结果映射_Failure为FAILED`() {
        val submitter = submitterWith()
        val info = submitter.reportInfo(FeedbackApi.SubmitResult.Failure("429"))
        assertEquals(SubmittedReportInfo.SubmissionStatus.FAILED, info.status)
        assertNull(info.url)
    }

    @Test
    fun `勾选多条事件_每条都成为批次条目`() {
        val submitter = submitterWith()
        val second = RuntimeException("second")
            .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Bar", "baz", "Bar.kt", 2)) }
        val items = submitter.batchItemsOf(arrayOf(event(), IdeaLoggingEvent("second", second)))
        assertEquals(2, items.size)
        assertEquals(RuntimeException::class.java.name, items[0].title)
        assertEquals("boom", items[0].message)
        assertTrue("条目默认带堆栈", items[0].attachStack)
        assertEquals("second", items[1].message)
    }

    @Test
    fun `报告按钮与隐私文案来自bundle非键名`() {
        val submitter = submitterWith()
        assertFalse(submitter.reportActionText.isNullOrBlank())
        assertFalse(submitter.reportActionText.startsWith("error.report."))
        assertFalse(submitter.privacyNoticeText.startsWith("error.report."))
    }
}
