package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 首用锚点引导开关接线测试（T-UxOnboard T-UX-01）：设置页界面节复选框的
 * isModified/apply/reset 归属正确性——防「勾选后重开未勾选」（读写判定必须随 UI 分区）
 * 与「漏挂 isModified 改动被静默丢弃」两类历史事故回归。开关默认开的出厂值在
 * OnboardingAnchorTipStateTest 的持久化往返用例覆盖。
 */
class OnboardingAnchorTipSettingBindingTest : BasePlatformTestCase() {

    fun `test开关勾选_检测到变化且apply写回`() {
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.state.onboardingAnchorTipEnabled
        try {
            settings.state.onboardingAnchorTipEnabled = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            configurable.reset()
            assertFalse("reset 后表单与配置一致（无变化）", configurable.isModified())

            panel.setOnboardingAnchorTipCheck(true)
            assertTrue("仅勾选首用引导开关也应检测到变化（漏挂 isModified → Apply 不启用）", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.onboardingAnchorTipEnabled 应写回 true", settings.state.onboardingAnchorTipEnabled)

            panel.setOnboardingAnchorTipCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state 应回 false", settings.state.onboardingAnchorTipEnabled)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.onboardingAnchorTipEnabled = oldEnabled
        }
    }

    fun `test界面节勾选后重开_设置保持勾选_不回滚`() {
        // 用户踩坑回归形态：勾选 → apply → 重新打开设置页（reset 读 state）→ 仍勾选
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.state.onboardingAnchorTipEnabled
        try {
            settings.state.onboardingAnchorTipEnabled = true
            val first = AllSectionsConfigurable()
            first.createComponent()
            first.reset()
            first.panelForTest().setOnboardingAnchorTipCheck(false)
            first.apply()

            // 模拟重开设置页：全新 configurable + reset（从 state 回读表单）
            val reopened = AllSectionsConfigurable()
            reopened.createComponent()
            reopened.reset()
            assertFalse("重开设置页后开关应保持关闭（勾选不回滚）", reopened.isModified())
            reopened.panelForTest().apply()
            assertFalse("reset 后直接 apply 不应改变 state（表单已同步）", settings.state.onboardingAnchorTipEnabled)
        } finally {
            settings.state.onboardingAnchorTipEnabled = oldEnabled
        }
    }
}
