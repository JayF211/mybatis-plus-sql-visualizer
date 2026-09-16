package cn.song.mybatisplus.visualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-FeedbackBatch：批量报告分流与错峰队列的纯逻辑契约。
 *
 * 用户真机诉求「不能被轻易限流」：服务端每 IP 每小时只许 5 次提交，逐条报必撞 429。
 * 这里锁定分流规则（合并 / 首发 + 补发 / 首发载荷重写）与队列常量，网络经 [FeedbackApi] seam。
 */
class BatchSubmitterTest {

    private fun item(index: Int, attach: Boolean = true) = BatchSubmitter.BatchItem(
        "cn.song.mybatisplus.visualizer.Boom$index",
        "boom $index",
        "java.lang.RuntimeException: boom $index\n\tat cn.song.mybatisplus.visualizer.log.P$index.r(P$index.kt:1)",
        attach,
    )

    private fun template() = FeedbackApi.Submission(
        FeedbackApi.PostRequest(
            "RuntimeException: boom 1",
            "boom 1",
            2,
            "other",
            null,
            null,
            true,
            FeedbackApi.SOURCE_IDE,
            "IntelliJ IDEA 2026.2.1",
            "2026.9.14",
            emptyList(),
        ),
        FeedbackApi.StackAttachment(
            FeedbackApi.STACK_ATTACHMENT_NAME,
            "旧附件".toByteArray(Charsets.UTF_8),
        ),
    )

    // ---------- 首发载荷重写（多条 → 合并附件） ----------

    @Test
    fun `多条勾选_附件换成多节合并内容`() {
        val merged = BatchSubmitter.composeFirstSubmission(template(), listOf(item(1), item(2), item(3)))
        val content = String(merged.stackAttachment!!.content, Charsets.UTF_8)
        assertTrue(content.contains("异常条数: 3"))
        assertTrue(content.contains("----- #1 -----"))
        assertTrue(content.contains("boom 3"))
        assertEquals(FeedbackApi.STACK_ATTACHMENT_NAME, merged.stackAttachment!!.fileName)
    }

    @Test
    fun `单条勾选_载荷原样返回`() {
        val original = template()
        val same = BatchSubmitter.composeFirstSubmission(original, listOf(item(1)))
        assertEquals(original, same)
    }

    @Test
    fun `条目全不带堆栈_首发不带附件`() {
        val merged = BatchSubmitter.composeFirstSubmission(
            template(),
            listOf(item(1, attach = false), item(2, attach = false)),
        )
        assertNull(merged.stackAttachment)
    }

    @Test
    fun `勾选取消的条目不进合并附件`() {
        val merged = BatchSubmitter.composeFirstSubmission(
            template(),
            listOf(item(1), item(2, attach = false)),
        )
        val content = String(merged.stackAttachment!!.content, Charsets.UTF_8)
        assertTrue(content.contains("异常条数: 1"))
        assertEquals(false, content.contains("boom 2"))
    }

    // ---------- 错峰补发分流 ----------

    @Test
    fun `五条及以内_不排补发_首发即全部`() {
        for (count in 1..ErrorReportComposer.BATCH_MERGE_MAX) {
            val followUp = BatchSubmitter.followUpSubmissions(template(), (1..count).map { item(it) })
            assertTrue("count=$count 不应补发", followUp.isEmpty())
        }
    }

    @Test
    fun `超过五条_除首条外逐条成独立帖子`() {
        val items = (1..8).map { item(it) }
        val followUp = BatchSubmitter.followUpSubmissions(template(), items)
        assertEquals(7, followUp.size)
        assertEquals("Boom2: boom 2", followUp.first().request.title)
        assertEquals("Boom8: boom 8", followUp.last().request.title)
        // 载荷带环境信息与附件
        assertEquals("2026.9.14", followUp.first().request.pluginVersion)
        assertNotNull(followUp.first().stackAttachment)
        assertEquals("ide", followUp.first().request.source)
    }

    @Test
    fun `补发条目按自身勾选决定附件`() {
        val followUp = BatchSubmitter.followUpSubmissions(
            template(),
            (1..6).map { item(it, attach = it % 2 == 0) },
        )
        assertNull("第 3 条未勾选堆栈，不应带附件", followUp[1].stackAttachment)
        assertNotNull("第 2 条已勾选，应带附件", followUp[0].stackAttachment)
    }

    @Test
    fun `补发描述取条目消息且满足服务端下限`() {
        val followUp = BatchSubmitter.followUpSubmissions(template(), (1..6).map { item(it) })
        val description = followUp.first().request.description
        assertTrue(description.contains("boom 2"))
        assertTrue(description.length >= ErrorReportComposer.DESCRIPTION_MIN)
    }

    // ---------- 队列常量 ----------

    @Test
    fun `错峰间隔与服务端窗口匹配`() {
        // 服务端 5 次/小时 → 720s 一条才刚好用满；取更保守的值保证同 IP 多源提交也不打满
        assertTrue(PendingReportScheduler.INTERVAL_SECONDS >= 720)
        assertEquals(12 * 60L, PendingReportScheduler.INTERVAL_SECONDS)
    }
}
