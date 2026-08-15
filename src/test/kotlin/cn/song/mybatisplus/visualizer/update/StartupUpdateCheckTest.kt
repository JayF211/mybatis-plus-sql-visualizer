package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动自动检查决策单测（T-M28-05）：开关联动 + 单次守卫（纯函数；
 * 通知 UI 与真实检查链路由 runIde 沙盒端到端验证）。
 *
 * T-M28.3 追加：通知内容纯函数（只保留变更日志标题行——用户要求）。
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

    // ---------------- T-M28.3 通知内容（只保留标题行） ----------------

    @Test
    fun notificationContentKeepsFirstLineOnly() {
        val notes = "v2026.8.15：官方 API 覆盖差距清零；批量操作增强\nv2026.8.14：Wrapper 全覆盖"
        val content = StartupUpdateCheck.notificationContent(notes)
        assertTrue("只保留首行标题", content.startsWith("v2026.8.15"))
        assertFalse("不得包含后续版本行", content.contains("v2026.8.14"))
    }

    @Test
    fun notificationContentTruncatesLongTitle() {
        val long = "标".repeat(300)
        val content = StartupUpdateCheck.notificationContent(long)
        assertTrue("超长标题应截断（≤101 字符）", content.length <= 101)
        assertTrue("截断应带省略号", content.endsWith("…"))
    }

    @Test
    fun notificationContentFallsBackForMissingNotes() {
        val fallback = MpSqlVisualizerBundle.message("update.notification.content")
        assertEquals(fallback, StartupUpdateCheck.notificationContent(null))
        assertEquals(fallback, StartupUpdateCheck.notificationContent(""))
        assertEquals(fallback, StartupUpdateCheck.notificationContent("   \n  "))
    }
}
