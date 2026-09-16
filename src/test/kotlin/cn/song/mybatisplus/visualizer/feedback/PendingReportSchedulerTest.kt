package cn.song.mybatisplus.visualizer.feedback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * 待发报告调度器测试（T-PerfSweep2-05）。
 *
 * 调度形态从「drain 循环 + Thread.sleep(720s) 占线程」改单拍链式后，锁定四条契约：
 * 队列按序消费全量、发送期间新批次并入不丢（本轮行为变更点——此前静默拒收）、
 * 两拍间隔不提前（错峰红线）、专属 daemon 线程。测试用 [enqueue] 的 delaySeconds
 * 参数走真实单线程池（0=即时消费），sender 换 fake 不触网。
 */
class PendingReportSchedulerTest {

    /** 可门控的 fake sender：记录帖次序、发送线程与时间戳；[gateFirstPost] 开启时首帖挂起（仅并发用例）。 */
    private class GatedSender : FeedbackApi.HttpSender {
        @Volatile
        var gateFirstPost = false

        val postedJson = mutableListOf<String>()
        val posterThreads = mutableListOf<String>()
        val posterDaemon = mutableListOf<Boolean>()
        val postTimestamps = mutableListOf<Long>()
        val firstPostEntered = CountDownLatch(1)
        val releaseFirstPost = CountDownLatch(1)

        override fun postJson(url: String, json: String): String {
            synchronized(this) {
                postedJson += json
                posterThreads += Thread.currentThread().name
                posterDaemon += Thread.currentThread().isDaemon
                postTimestamps += System.nanoTime()
            }
            if (gateFirstPost && postedJson.size == 1) {
                firstPostEntered.countDown()
                releaseFirstPost.await()
            }
            return """{"code":0,"message":"ok","data":{"postNo":"FR-0042"}}"""
        }

        override fun postMultipart(url: String, fileName: String, mimeType: String, content: ByteArray): String =
            """{"code":0,"data":{"key":"feed/x.txt","fileName":"s.txt","mimeType":"text/plain","fileSize":9}}"""
    }

    /** 从请求体提取 title 值（Gson 字段序不可依赖，正则直取）。 */
    private fun titleOf(json: String): String =
        Regex("\"title\":\"(.*?)\"").find(json)?.groupValues?.get(1) ?: ""

    private val sender = GatedSender()
    private val scheduler = PendingReportScheduler()

    @Before
    fun setUp() {
        // 接线 fake sender——不接则 tick 走默认 HttpRequestsSender 真触网（生产需求池！）
        FeedbackApi.setSender(sender)
    }

    private fun submission(mark: String) = FeedbackApi.Submission(
        FeedbackApi.PostRequest(
            "RuntimeException: $mark",
            "boom $mark",
            2,
            "sql-log",
            null,
            null,
            true,
            FeedbackApi.SOURCE_IDE,
            "test",
            "test",
            emptyList(),
        ),
        null,
    )

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @After
    fun tearDown() {
        FeedbackApi.setSender(HttpRequestsSender.INSTANCE)
    }

    @Test
    fun `空列表入队不排程`() {
        scheduler.enqueue(emptyList(), 0L)
        assertEquals("idle", scheduler.describeQueue())
        assertTrue(sender.postedJson.isEmpty())
    }

    @Test
    fun `队列按序全量消费后归闲`() {
        scheduler.enqueue(listOf(submission("a"), submission("b"), submission("c")), 0L)
        assertTrue("应在超时内消费完并归闲", awaitUntil { scheduler.describeQueue() == "idle" })
        assertEquals(3, sender.postedJson.size)
        assertEquals(
            listOf("a", "b", "c"),
            sender.postedJson.map { titleOf(it).substringAfter("RuntimeException: ") },
        )
    }

    @Test
    fun `发送期间新批次并入不丢_此前被静默拒收`() {
        sender.gateFirstPost = true
        scheduler.enqueue(listOf(submission("first")), 0L)
        assertTrue("首帖应已进入发送", sender.firstPostEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 首帖挂起期间入队第二批——旧行为（drain 占线程 + scheduled 未完成）会在这里静默丢弃
        assertEquals("queued", scheduler.describeQueue())
        scheduler.enqueue(listOf(submission("second")), 0L)

        sender.releaseFirstPost.countDown()
        assertTrue("新批次应顺延补发", awaitUntil { sender.postedJson.size == 2 })
        assertTrue(titleOf(sender.postedJson[0]).endsWith("first"))
        assertTrue(titleOf(sender.postedJson[1]).endsWith("second"))
        assertTrue("全部发完后归闲", awaitUntil { scheduler.describeQueue() == "idle" })
    }

    @Test
    fun `两拍间隔不提前_错峰红线`() {
        scheduler.enqueue(listOf(submission("a"), submission("b")), 1L)
        assertTrue(awaitUntil { scheduler.describeQueue() == "idle" })
        assertEquals(2, sender.postTimestamps.size)
        val gapMs = (sender.postTimestamps[1] - sender.postTimestamps[0]) / 1_000_000
        assertTrue("两拍间隔 ${gapMs}ms 不应早于 1s 错峰（留测量余量）", gapMs >= 800)
    }

    @Test
    fun `发送跑在专属daemon线程`() {
        scheduler.enqueue(listOf(submission("a")), 0L)
        assertTrue(awaitUntil { scheduler.describeQueue() == "idle" })
        assertEquals("sql-visualizer-feedback-queue", sender.posterThreads.single())
        assertTrue(sender.posterDaemon.single())
    }
}
