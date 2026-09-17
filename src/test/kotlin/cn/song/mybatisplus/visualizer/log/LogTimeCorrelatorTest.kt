package cn.song.mybatisplus.visualizer.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LogTimeCorrelator] 纯逻辑测试（T-AgentDialectLoop 建，T-AgentLogTime 扩）：
 * 命中即消费、未命中 null 且计数、同键 FIFO 排队、参数签名优先配对、陈旧项时间窗
 * （按**入册时刻**判定，日志行时间偏移不误判）、容量逐出、清空、空键忽略。
 */
class LogTimeCorrelatorTest {

    private fun now() = System.currentTimeMillis()

    @After
    fun tearDown() {
        LogTimeCorrelator.clear()
    }

    @Test
    fun `put后take命中即消费`() {
        LogTimeCorrelator.clear()
        val ts = now()
        LogTimeCorrelator.put("fp1", ts)
        assertEquals(ts, LogTimeCorrelator.take("fp1")!!)
        assertNull("命中即消费：二次 take 应为 null", LogTimeCorrelator.take("fp1"))
        assertEquals(0, LogTimeCorrelator.size())
    }

    @Test
    fun `take未命中返回null并计数`() {
        LogTimeCorrelator.clear()
        val before = LogTimeCorrelator.missCount()
        assertNull(LogTimeCorrelator.take("nope"))
        assertEquals("未命中计数递增（诊断口径）", before + 1, LogTimeCorrelator.missCount())
    }

    @Test
    fun `同键重复put按FIFO排队而非覆盖`() {
        LogTimeCorrelator.clear()
        // T-AgentLogTime：旧实现同键 remove+put（后者顶掉前者），同一条 SQL 连续执行时先来的时间会丢
        val first = now() - 20
        val second = now()
        LogTimeCorrelator.put("fp", first)
        LogTimeCorrelator.put("fp", second)
        assertEquals("先入先出", first, LogTimeCorrelator.take("fp")!!)
        assertEquals("后入仍在", second, LogTimeCorrelator.take("fp")!!)
        assertEquals("队列取空即移除键", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `同键参数签名优先配对`() {
        LogTimeCorrelator.clear()
        val t1 = now() - 5000
        val t2 = now()
        LogTimeCorrelator.put("fp", t1, "1")
        LogTimeCorrelator.put("fp", t2, "2")
        assertEquals("签名一致的那条优先（不被队首错配）", t2, LogTimeCorrelator.take("fp", "2")!!)
        assertEquals("剩下的仍可取", t1, LogTimeCorrelator.take("fp", "1")!!)
    }

    @Test
    fun `签名对不上退队首不错失`() {
        LogTimeCorrelator.clear()
        val t1 = now() - 10
        LogTimeCorrelator.put("fp", t1, "1")
        assertEquals("渲染口径分叉时退化为队首配对（近似优于空）", t1, LogTimeCorrelator.take("fp", "不认识的签名")!!)
    }

    @Test
    fun `超窗陈旧项被丢弃`() {
        LogTimeCorrelator.clear()
        // 陈旧判定只看入册时刻（本机时钟）——30s 前入册、至今无人认领的残留条丢弃，
        // 防空转一场后配到下一次同 SQL 执行（宁可未命中回落，不可配错时间）。
        LogTimeCorrelator.putAt("fp", now(), "", now() - 60_000L)
        assertNull("超出 30s 时间窗的陈旧项不与本次执行配对", LogTimeCorrelator.take("fp"))
        assertEquals("陈旧项已出清", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `日志行时间偏移不误判陈旧`() {
        LogTimeCorrelator.clear()
        // 应用 JVM 时区与 IDE 不同时日志行时间会整体偏移（如差 8h）——陈旧判定若拿它比墙钟，
        // 全部条目会被误判陈旧、日志时间静默全失。故只看入册时刻。
        val shifted = now() - 8 * 3600_000L
        LogTimeCorrelator.put("fp", shifted, "")
        assertEquals("偏移动日志时间仍正常配对", shifted, LogTimeCorrelator.take("fp")!!)
    }

    @Test
    fun `容量上限_最老键被逐出`() {
        LogTimeCorrelator.clear()
        // 超量写入：0..512 共 513 个键，键上限 512 → 最早的 "k0" 被逐出
        val base = now()
        (0..512).forEach { LogTimeCorrelator.put("k$it", base) }
        assertEquals(512, LogTimeCorrelator.size())
        assertNull("最老条目被逐出", LogTimeCorrelator.take("k0"))
        assertEquals("次老条目仍在", base, LogTimeCorrelator.take("k1")!!)
        assertEquals("最新条目仍在", base, LogTimeCorrelator.take("k512")!!)
    }

    @Test
    fun `单键队列超量丢最老`() {
        LogTimeCorrelator.clear()
        val base = now()
        (0..20).forEach { LogTimeCorrelator.put("fp", base, "sig$it") }
        assertEquals("单键队列上限 8 条", 8, LogTimeCorrelator.size())
        assertEquals("最老的被丢弃、留下的仍按签名配对", base, LogTimeCorrelator.take("fp", "sig13")!!)
    }

    @Test
    fun `T-PS4-05_超长参数截断后两侧同签名仍配对命中`() {
        LogTimeCorrelator.clear()
        // 超长 blob 参数（>256 字符）：两侧共用 logTimeParamSignature 单函数，截断后签名一致
        val longValue = "x".repeat(PARAM_SIGNATURE_MAX_CHARS)
        assertEquals(
            "两侧同源截断，签名应逐字符一致",
            logTimeParamSignature(listOf(LogSqlParam(longValue, "String"))),
            logTimeParamSignature(listOf(LogSqlParam(longValue, "String"))),
        )
        assertEquals(
            "截断后签名长度有界（首个值 = 上限 + 分隔符前缀）",
            PARAM_SIGNATURE_MAX_CHARS,
            logTimeParamSignature(listOf(LogSqlParam(longValue, "String"))).length,
        )
        // 配对语义：同键同（截断后）签名命中而非退队首
        val t1 = now() - 3000
        val t2 = now()
        LogTimeCorrelator.put("fpLong", t1, logTimeParamSignature(listOf(LogSqlParam(longValue, "String"))))
        LogTimeCorrelator.put("fpLong", t2, logTimeParamSignature(listOf(LogSqlParam("other", "String"))))
        assertEquals(
            "超长参数经截断签名仍精确配对",
            t1,
            LogTimeCorrelator.take("fpLong", logTimeParamSignature(listOf(LogSqlParam(longValue, "String")))),
        )
    }

    /** 测试内引用截断上限（避免测试硬编码 256 与实现漂移）。 */

    @Test
    fun `clear清空`() {
        LogTimeCorrelator.put("fp", now())
        LogTimeCorrelator.clear()
        assertEquals(0, LogTimeCorrelator.size())
        assertNull(LogTimeCorrelator.take("fp"))
    }

    @Test
    fun `空键忽略`() {
        LogTimeCorrelator.clear()
        LogTimeCorrelator.put("", now())
        assertEquals(0, LogTimeCorrelator.size())
    }
}
