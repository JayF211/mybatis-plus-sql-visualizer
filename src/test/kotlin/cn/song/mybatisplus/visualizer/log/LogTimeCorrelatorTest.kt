package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LogTimeCorrelator] 纯逻辑测试（T-AgentDialectLoop）：
 * 命中即消费、未命中 null、同指纹刷新、容量逐出、清空、空指纹忽略。
 */
class LogTimeCorrelatorTest {

    @Test
    fun `put后take命中即消费`() {
        LogTimeCorrelator.clear()
        LogTimeCorrelator.put("fp1", 1724567890123L)
        assertEquals(1724567890123L, LogTimeCorrelator.take("fp1")!!)
        assertNull("命中即消费：二次 take 应为 null", LogTimeCorrelator.take("fp1"))
        assertEquals(0, LogTimeCorrelator.size())
    }

    @Test
    fun `take未命中返回null`() {
        LogTimeCorrelator.clear()
        assertNull(LogTimeCorrelator.take("nope"))
    }

    @Test
    fun `同指纹重复put取最新值`() {
        LogTimeCorrelator.clear()
        LogTimeCorrelator.put("fp", 1L)
        LogTimeCorrelator.put("fp", 2L)
        assertEquals(2L, LogTimeCorrelator.take("fp")!!)
        assertEquals("刷新不产生重复键", 0, LogTimeCorrelator.size())
    }

    @Test
    fun `容量上限_最老被逐出`() {
        LogTimeCorrelator.clear()
        // 超量写入：0..512 共 513 条，容量 512 → 最早的 "k0" 被逐出
        (0..512).forEach { LogTimeCorrelator.put("k$it", it.toLong()) }
        assertEquals(512, LogTimeCorrelator.size())
        assertNull("最老条目被逐出", LogTimeCorrelator.take("k0"))
        assertEquals("次老条目仍在", 1L, LogTimeCorrelator.take("k1")!!)
        assertEquals("最新条目仍在", 512L, LogTimeCorrelator.take("k512")!!)
    }

    @Test
    fun `clear清空`() {
        LogTimeCorrelator.put("fp", 1L)
        LogTimeCorrelator.clear()
        assertEquals(0, LogTimeCorrelator.size())
        assertNull(LogTimeCorrelator.take("fp"))
    }

    @Test
    fun `空指纹忽略`() {
        LogTimeCorrelator.clear()
        LogTimeCorrelator.put("", 1L)
        assertEquals(0, LogTimeCorrelator.size())
    }
}
