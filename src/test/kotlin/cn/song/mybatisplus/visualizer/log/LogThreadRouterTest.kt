package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogThreadRouter] 纯逻辑测试（T-LogParseConc）：线程 token 提取（三种日志形态 + 干扰段排除）、
 * 按 token 隔离、无线程行归属「等待态桶」优先、桶上限淘汰空闲桶。
 */
class LogThreadRouterTest {

    /** 测试用桶：仅需「是否处于中间态 / 是否空闲」两个标志。 */
    private class Bucket {
        var waiting = false
        var idle = true
    }

    private fun router(max: Int = 64) = LogThreadRouter(
        factory = { Bucket() },
        isCollecting = { it.waiting },
        isIdle = { it.idle },
        maxBuckets = max,
    )

    // ---------- token 提取 ----------

    @Test
    fun `token提取_三种日志形态`() {
        assertEquals(
            "http-nio-8088-exec-4",
            LogThreadRouter.threadTokenOf("2026-09-15 13:47:17.843 [TID: AE51C] [http-nio-8088-exec-4] DEBUG c.Foo - Preparing: x"),
        )
        assertEquals(
            "main",
            LogThreadRouter.threadTokenOf("2026-08-21 10:00:00.124 TRACE [main] o.h.h.i.QueryTranslatorImpl : select"),
        )
        assertEquals(
            "cache-preheat-1",
            LogThreadRouter.threadTokenOf("2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE c.Foo - x"),
        )
    }

    @Test
    fun `token提取_干扰段排除与无法提取`() {
        assertNull("无级别关键词锚点 → 无法可靠提取", LogThreadRouter.threadTokenOf("[main] 只有方括号没有级别词"))
        assertEquals(
            "exec-9",
            LogThreadRouter.threadTokenOf("2026-09-16 14:35:00.413 [TID: FED5CD4B] [exec-9] DEBUG x - y"),
        )
        assertNull("纯数字方括号段不当作线程名", LogThreadRouter.threadTokenOf("2026-09-16 10:00:00.000 DEBUG x [12345] y"))
    }

    // ---------- 分桶 ----------

    @Test
    fun `同token同桶_异token异桶`() {
        val r = router()
        val a1 = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-1] x - a")
        val a2 = r.bucketFor("2026-09-16 10:00:00.001 DEBUG [exec-1] x - b")
        val b1 = r.bucketFor("2026-09-16 10:00:00.002 DEBUG [exec-2] x - c")
        assertSame(a1, a2)
        assertNotSame(a1, b1)
        assertEquals(2, r.bucketCount())
    }

    @Test
    fun `无线程行归属等待态桶_否则回退桶`() {
        val r = router()
        val fallback1 = r.bucketFor("纯文本无 token 行")
        val fallback2 = r.bucketFor("又一条纯文本无 token 行")
        assertSame("两条无线程行都落回退桶（同一实例）", fallback1, fallback2)

        val waiting = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-7] x - a")
        waiting.waiting = true
        assertSame("无线程行优先归属处于等待态的桶（多行续行/等待下一行场景）", waiting, r.bucketFor("FROM user"))
    }

    @Test
    fun `桶上限_全忙时不丢状态_有空闲时淘汰空闲桶`() {
        val r = router(max = 2)
        val keep = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [keep] x - a").apply { waiting = true; idle = false }
        r.bucketFor("2026-09-16 10:00:00.001 DEBUG [busy] x - b").idle = false

        val third = r.bucketFor("2026-09-16 10:00:00.002 DEBUG [third] x - c").apply { idle = false }
        assertSame("等待态桶不被淘汰", keep, r.bucketFor("2026-09-16 10:00:00.003 DEBUG [keep] x - d"))
        assertSame("非空闲桶不被淘汰", third, r.bucketFor("2026-09-16 10:00:00.004 DEBUG [third] x - e"))
        assertEquals("全部非空闲时宁超上限也不丢状态（淘汰只打空闲桶）", 3, r.bucketCount())

        val busy = r.bucketFor("2026-09-16 10:00:00.005 DEBUG [busy] x - f")
        busy.idle = true
        r.bucketFor("2026-09-16 10:00:00.006 DEBUG [fourth] x - g")
        assertEquals("有空闲桶时先淘汰再新建，桶数不增长", 3, r.bucketCount())
        assertNotSame("空闲桶确实被淘汰（同 token 再取是新桶）", busy, r.bucketFor("2026-09-16 10:00:00.007 DEBUG [busy] x - h"))
        assertSame("等待态桶始终在册", keep, r.bucketFor("2026-09-16 10:00:00.008 DEBUG [keep] x - i"))
    }

    @Test
    fun `clear清空分桶`() {
        val r = router()
        r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-1] x - a")
        r.clear()
        assertEquals(0, r.bucketCount())
    }

    // ---------- 日志行判定 ----------

    @Test
    fun `日志行判定_参数裸文本不算`() {
        assertTrue(looksLikeLogLine("2026-09-16 10:00:00.100 DEBUG [exec-1] x - y", "Preparing:"))
        assertTrue(looksLikeLogLine("==> Parameters: 7(Long)", "Parameters:"))
        assertTrue(looksLikeLogLine("<==      Total: 1", "Preparing:"))
        assertTrue("含语句关键字即日志行", looksLikeLogLine("2026-09-16 10:00:00.100 DEBUG x - Preparing: SELECT 1", "Preparing:"))
        assertEquals(false, looksLikeLogLine("42(Long), abc(String)", "Preparing:", "Parameters:"))
        assertEquals(false, looksLikeLogLine("", "Preparing:"))
    }
}
