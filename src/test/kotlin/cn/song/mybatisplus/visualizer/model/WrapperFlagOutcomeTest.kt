package cn.song.mybatisplus.visualizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R49：Wrapper 布尔条件探镜纯函数测试（V73 证伪结论落地）。
 *
 * 覆盖：
 *  - 识别率：flagSource 四档（null / "true" / "false" / 运行时占位符）的派生结果；
 *  - 缺省 true 文案：字面量 true → 「恒真」、字面量 false → 「恒假」、
 *    运行时占位符（MP condition 缺省 true）→ 「恒真·缺省」——把「缺省当求值」的误导风险显式分开；
 *  - 边界：null 一律不标注（非布尔重载 / EasyQuery 字面量 true 折叠成 null 不可区分，宁可漏标不误标）。
 *
 * bundle 在无平台服务环境下兜底中文（见 [cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle] KDoc），
 * 断言用中文特征串；文案键缺失时 message() 返回 key 本身，断言即失败。
 */
class WrapperFlagOutcomeTest {

    // ---------- 识别率（V73①②） ----------

    @Test
    fun test非布尔重载_null不标注() {
        assertNull("flagSource=null（无布尔重载/无标志）不得标注", wrapperFlagOutcome(null))
    }

    @Test
    fun test字面量true_恒真() {
        assertEquals(WrapperFlagOutcome.ALWAYS_TRUE, wrapperFlagOutcome("true"))
        assertEquals("恒真", wrapperFlagOutcomeText(WrapperFlagOutcome.ALWAYS_TRUE))
    }

    @Test
    fun test字面量false_恒假() {
        assertEquals(WrapperFlagOutcome.ALWAYS_FALSE, wrapperFlagOutcome("false"))
        assertEquals("恒假", wrapperFlagOutcomeText(WrapperFlagOutcome.ALWAYS_FALSE))
    }

    @Test
    fun test运行时占位符_恒真缺省() {
        for (flag in listOf("isActive", "StringUtils.isNotBlank(name)", "dto.getStationId() != null")) {
            assertEquals(
                "运行时占位符 $flag 应按缺省 true 标注（MP condition 缺省语义）",
                WrapperFlagOutcome.DEFAULT_TRUE,
                wrapperFlagOutcome(flag),
            )
        }
        assertEquals("恒真·缺省", wrapperFlagOutcomeText(WrapperFlagOutcome.DEFAULT_TRUE))
    }

    @Test
    fun test恒真与恒真缺省_文案必须不同() {
        // 缺省 true 与求值 true 是两件事——文案分开防「缺省当求值」的误导
        val always = wrapperFlagOutcomeText(WrapperFlagOutcome.ALWAYS_TRUE)
        val byDefault = wrapperFlagOutcomeText(WrapperFlagOutcome.DEFAULT_TRUE)
        assertTrue("恒真 与 恒真·缺省 文案不得同串（防缺省语义被当成求值结果）: $always vs $byDefault", always != byDefault)
    }

    @Test
    fun testtooltip带出标志原文() {
        val tip = wrapperFlagOutcomeTooltip("StringUtils.isNotBlank(name)", WrapperFlagOutcome.DEFAULT_TRUE)
        assertTrue("tooltip 应带出布尔标志原文：$tip", "StringUtils.isNotBlank(name)" in tip)
        assertTrue("tooltip 应带出标注文案：$tip", "恒真·缺省" in tip)
    }
}
