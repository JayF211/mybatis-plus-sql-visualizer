package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-JumpPath-04：逐模块注入聚合决策（[SqlSourceInterceptorAutoInjector.decideAggregate]）表驱动单测。
 *
 * 聚合规则（计划 §4.4）：至少一模块新注入 → Success；无新注入但有已存在模块 → AlreadyExists；
 * 全失败 → UnsupportedProject。文件系统实写链不新建覆盖（沿用现有边界）。
 */
class InjectionAggregateDecisionTest {

    /** 注入 SAM 构造器形态（Kotlin 函数类型变量不能隐式转 Java Function 参数）。 */
    private val msg = java.util.function.Function<List<String>, String> { "已注入: ${it.joinToString(",")}" }

    @Test
    fun `多模块全部新注入_Success 消息含模块清单`() {
        val r = SqlSourceInterceptorAutoInjector.decideAggregate(
            listOf("mybatis-plus-demo", "mybatis-plus-join-demo"),
            emptyList(),
            emptyList(),
            msg,
        )
        assertTrue("应聚合为 Success，实为 $r", r is InjectionResult.Success)
        assertEquals("已注入: mybatis-plus-demo,mybatis-plus-join-demo", (r as InjectionResult.Success).message)
    }

    @Test
    fun `部分新注入_成功优先_失败模块不阻断`() {
        val r = SqlSourceInterceptorAutoInjector.decideAggregate(
            listOf("mod-a"),
            listOf("mod-b"),
            listOf("mod-c"),
            msg,
        )
        assertTrue("任一成功即 Success（失败模块由调用方日志留痕）", r is InjectionResult.Success)
    }

    @Test
    fun `全部已存在_AlreadyExists`() {
        val r = SqlSourceInterceptorAutoInjector.decideAggregate(
            emptyList(),
            listOf("mod-a", "mod-b"),
            emptyList(),
            msg,
        )
        assertEquals(InjectionResult.AlreadyExists.INSTANCE, r)
    }

    @Test
    fun `已存在与失败并存_幂等语义优先于失败提示`() {
        val r = SqlSourceInterceptorAutoInjector.decideAggregate(
            emptyList(),
            listOf("mod-a"),
            listOf("mod-b"),
            msg,
        )
        assertEquals(InjectionResult.AlreadyExists.INSTANCE, r)
    }

    @Test
    fun `全部失败_UnsupportedProject`() {
        val r = SqlSourceInterceptorAutoInjector.decideAggregate(
            emptyList(),
            emptyList(),
            listOf("mod-a"),
            msg,
        )
        assertEquals(InjectionResult.UnsupportedProject.INSTANCE, r)
    }
}
