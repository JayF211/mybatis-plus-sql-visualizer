package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 首用锚点引导「一次性」语义测试（T-UxOnboard T-UX-01）：tryClaimAndMark 在真实
 * PluginSettings（application 级服务）上的状态翻转——展示一次即持久化置位、二次领取失败
 * （重开项目不再出现）、开关关闭全程静默。headless 下 isUnitTestMode 守卫只拦通知，
 * 不拦持久化语义（本测试即在该降级路径上断言状态）。
 */
class OnboardingAnchorTipStateTest : BasePlatformTestCase() {

    private var oldEnabled = true
    private var oldShown = false

    override fun setUp() {
        super.setUp()
        val s = PluginSettings.getInstance().state
        oldEnabled = s.onboardingAnchorTipEnabled
        oldShown = s.onboardingAnchorTipShown
    }

    override fun tearDown() {
        try {
            val s = PluginSettings.getInstance().state
            s.onboardingAnchorTipEnabled = oldEnabled
            s.onboardingAnchorTipShown = oldShown
        } finally {
            super.tearDown()
        }
    }

    fun `test首次领取成功并翻转已展示字段`() {
        val s = PluginSettings.getInstance().state
        s.onboardingAnchorTipEnabled = true
        s.onboardingAnchorTipShown = false
        assertTrue("首次命中应领取展示权", OnboardingAnchorTip.tryClaimAndMark())
        assertEquals("已展示字段应翻转为 true（持久化）", true, s.onboardingAnchorTipShown)
    }

    fun `test二次领取失败_重开项目不再出现`() {
        val s = PluginSettings.getInstance().state
        s.onboardingAnchorTipEnabled = true
        s.onboardingAnchorTipShown = true
        assertFalse("已展示后再次领取应失败（一次性语义）", OnboardingAnchorTip.tryClaimAndMark())
        assertEquals("已展示字段保持 true", true, s.onboardingAnchorTipShown)
    }

    fun `test开关关闭全程静默_不翻转状态`() {
        val s = PluginSettings.getInstance().state
        s.onboardingAnchorTipEnabled = false
        s.onboardingAnchorTipShown = false
        assertFalse("开关关 → 领取失败（全程静默）", OnboardingAnchorTip.tryClaimAndMark())
        assertEquals("静默路径不应翻转已展示字段", false, s.onboardingAnchorTipShown)
    }

    fun `test新字段随State持久化往返保留`() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.onboardingAnchorTipEnabled = false
            settings.state.onboardingAnchorTipShown = true
            val saved = settings.getState()
            assertEquals("getState 应反映最新开关", false, saved.onboardingAnchorTipEnabled)
            assertEquals("getState 应反映最新已展示", true, saved.onboardingAnchorTipShown)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后开关保留", false, fresh.state.onboardingAnchorTipEnabled)
            assertEquals("loadState 后已展示保留（重开项目不再出现）", true, fresh.state.onboardingAnchorTipShown)
            // 出厂默认：开 / 未展示
            val defaults = PluginSettings.State()
            assertTrue("引导开关默认应开启", defaults.onboardingAnchorTipEnabled)
            assertFalse("已展示默认应为 false", defaults.onboardingAnchorTipShown)
        } finally {
            settings.loadState(snapshot)
        }
    }
}
