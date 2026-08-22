package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢查询检测器单元测试（O-1 Phase C）：基于阈值判定慢查询。
 *
 * 覆盖场景：阈值上（慢查询）、阈值下（正常）、耗时缺失、边界值。
 */
class SlowQueryDetectorTest {

    @Test
    fun `耗时超过阈值判定为慢查询`() {
        assertTrue(SlowQueryDetector.isSlow(1500L, 1000L))
    }

    @Test
    fun `耗时低于阈值判定为正常`() {
        assertFalse(SlowQueryDetector.isSlow(500L, 1000L))
    }

    @Test
    fun `耗时等于阈值判定为慢查询`() {
        assertTrue(SlowQueryDetector.isSlow(1000L, 1000L))
    }

    @Test
    fun `耗时缺失判定为正常`() {
        assertFalse(SlowQueryDetector.isSlow(null, 1000L))
    }

    @Test
    fun `计数统计正确`() {
        val counter = SlowQueryCounter(thresholdMs = 1000L)
        assertEquals(0, counter.slowCount)

        counter.report(1500L)  // 慢
        assertEquals(1, counter.slowCount)

        counter.report(500L)   // 正常
        assertEquals(1, counter.slowCount)

        counter.report(2000L)  // 慢
        assertEquals(2, counter.slowCount)
    }

    @Test
    fun `重置计数器归零`() {
        val counter = SlowQueryCounter(thresholdMs = 1000L)
        counter.report(1500L)
        counter.report(2000L)
        assertEquals(2, counter.slowCount)

        counter.reset()
        assertEquals(0, counter.slowCount)
    }

    @Test
    fun `阈值 0 时任何耗时都判定为慢查询`() {
        assertTrue(SlowQueryDetector.isSlow(1L, 0L))
    }
}
