package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 执行耗时解析器单元测试（O-1 Phase B）：从日志文本中解析执行耗时（毫秒）。
 *
 * 覆盖场景：
 * - p6spy elapsed 格式：`elapsed 123 ms` 或 `consumed 456ms`
 * - MP TimeCount 格式：`TimeCount: 789ms` 或 `time: 100 ms`
 * - 自定义前缀（logTimePrefix 配置）：`Time: 200ms`
 * - 无耗时信息返回 null
 */
class ExecutionTimeParserTest {

    @Test
    fun `p6spy elapsed 格式解析`() {
        assertEquals(123L, ExecutionTimeParser.parse("elapsed 123 ms"))
    }

    @Test
    fun `p6spy consumed 格式解析`() {
        assertEquals(456L, ExecutionTimeParser.parse("consumed 456ms"))
    }

    @Test
    fun `MP TimeCount 格式解析`() {
        assertEquals(789L, ExecutionTimeParser.parse("TimeCount: 789ms"))
    }

    @Test
    fun `time 前缀格式解析`() {
        assertEquals(100L, ExecutionTimeParser.parse("time: 100 ms"))
    }

    @Test
    fun `自定义前缀 Time 解析`() {
        assertEquals(200L, ExecutionTimeParser.parse("Time: 200ms", "Time:"))
    }

    @Test
    fun `自定义前缀 duration 解析`() {
        assertEquals(50L, ExecutionTimeParser.parse("duration:50ms", "duration:"))
    }

    @Test
    fun `无耗时信息返回 null`() {
        assertNull(ExecutionTimeParser.parse("SELECT * FROM user WHERE id = 1"))
    }

    @Test
    fun `空串返回 null`() {
        assertNull(ExecutionTimeParser.parse(""))
    }

    @Test
    fun `仅数字无单位返回 null`() {
        assertNull(ExecutionTimeParser.parse("12345"))
    }

    @Test
    fun `耗时后紧跟其他文本`() {
        assertEquals(300L, ExecutionTimeParser.parse("elapsed 300 ms | SELECT * FROM t"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(150L, ExecutionTimeParser.parse("ELAPSED 150 MS"))
    }
}
