package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首次捕获成功指路决策测试（T-UxOnboard T-UX-03）：shouldGuide 纯函数条件矩阵——
 * 会话级一次性、历史非空跳过、headless 静默；零平台依赖。
 * headless 下 onEntryAccepted 真实入口不炸且有界，见 FirstCaptureGuideSessionTest。
 */
class FirstCaptureGuideTest {

    @Before
    fun resetSessionMemory() {
        FirstCaptureGuide.resetForTest()
    }

    @Test
    fun `会话首条捕获_指路`() {
        assertTrue("未指路过 + 缓冲恰 1 条 + 非 headless → 指路", FirstCaptureGuide.shouldGuide(false, 1, false))
    }

    @Test
    fun `会话级一次性_已指路不再弹`() {
        assertFalse("已指路 → 跳过（同会话不重复）", FirstCaptureGuide.shouldGuide(true, 1, false))
    }

    @Test
    fun `历史非空跳过_防打扰`() {
        assertFalse("缓冲已有 2 条（本条非会话首条）→ 跳过", FirstCaptureGuide.shouldGuide(false, 2, false))
        assertFalse("缓冲为空（入列前的回调形态）→ 不指路", FirstCaptureGuide.shouldGuide(false, 0, false))
    }

    @Test
    fun `headless静默`() {
        assertFalse("isUnitTestMode → 不指路", FirstCaptureGuide.shouldGuide(false, 1, true))
    }
}
