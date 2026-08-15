package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动自动检查决策单测（T-M28-05）：开关联动 + 单次守卫（纯函数；
 * 通知 UI 与真实检查链路由 runIde 沙盒端到端验证）。
 */
class StartupUpdateCheckTest {

    @Test
    fun checkEnabledAndNotCheckedRuns() {
        assertTrue(StartupUpdateCheck.shouldStartupCheck(autoCheck = true, alreadyChecked = false))
    }

    @Test
    fun checkDisabledSkips() {
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = false, alreadyChecked = false))
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = false, alreadyChecked = true))
    }

    @Test
    fun alreadyCheckedSkips() {
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = true, alreadyChecked = true))
    }
}
