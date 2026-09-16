package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PerfSweep3 #1 守护测试：分桶路由线程 token 提取的级别关键词正则必须以 companion 常量在位。
 *
 * 背景：性能第五轮报告 §5.1 发现 `feed()` 每行经 bucketFor → threadTokenOf 现场
 * `Regex("""\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""")` 编译（EDT 捕捉回调上每行一次
 * Pattern.compile，高吞吐控制台随行数线性累积分配压力）。该点已由 T-HibConc
 * （3c744d7，LogThreadRouter 下沉时）收口为常量——本次核实属实，本测试防回潮：
 * 若正则被移回函数体内联编译（常量删除），反射断言即红。
 */
class LogRouterHotPathRegexGuardTest {

    @Test
    fun `分桶路由级别关键词正则走companion常量_防回潮`() {
        // Kotlin 把 companion 属性编译为外部类静态字段（javap 实证：LEVEL_KEYWORD_PATTERN
        // 在 LogThreadRouter 上，Companion 类无字段）——反射按外部类静态字段断言
        val field = LogThreadRouter::class.java.getDeclaredField("LEVEL_KEYWORD_PATTERN")
        field.isAccessible = true
        val regex = field.get(null) as? Regex
        assertNotNull("LEVEL_KEYWORD_PATTERN 须以 companion 常量在位（逐行热路径复用）", regex)
        assertEquals(
            "级别关键词 pattern 未被改动",
            """\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""",
            regex?.pattern,
        )
        // 行为抽检：常量对真实日志行形态正常命中（守护测试顺带锚定语义不漂移）
        assertTrue(
            regex!!.containsMatchIn("2026-09-16 10:00:00.123 [http-nio-8080-exec-4] DEBUG o.s.JdbcTemplate : sql"),
        )
    }
}
