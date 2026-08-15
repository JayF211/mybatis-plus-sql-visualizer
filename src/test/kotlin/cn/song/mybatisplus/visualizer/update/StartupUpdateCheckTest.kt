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

    // ---------------- T-M28.3 通知内容（每行一个标题） ----------------

    @Test
    fun notificationContentShowsOneTitlePerLine() {
        // 用户要求「更新标题每行显示一个」：每个版本一行（<br> 连接，HTML 换行）
        val notes = "v2026.8.15：XML/注解动态 SQL 预览\nv2026.8.14：Wrapper 全覆盖"
        val content = StartupUpdateCheck.notificationContent(notes)
        assertEquals("v2026.8.15：XML/注解动态 SQL 预览<br>v2026.8.14：Wrapper 全覆盖", content)
    }

    @Test
    fun notificationContentTruncatesEachLongLine() {
        val long = "标".repeat(300)
        val content = StartupUpdateCheck.notificationContent(long)
        assertTrue("超长标题应截断（≤61 字符）", content.length <= 61)
        assertTrue("截断应带省略号", content.endsWith("…"))
    }

    @Test
    fun notificationContentEscapesHtml() {
        // 变更日志可能含 SQL 动态标签（<if> 等）——HTML 转义防渲染破坏（文本节点引号无需转义）
        val content = StartupUpdateCheck.notificationContent("v1：<if test=\"x & y\">")
        assertEquals("v1：&lt;if test=\"x &amp; y\"&gt;", content)
    }

    @Test
    fun notificationContentFallsBackForMissingNotes() {
        val fallback = MpSqlVisualizerBundle.message("update.notification.content")
        assertEquals(fallback, StartupUpdateCheck.notificationContent(null))
        assertEquals(fallback, StartupUpdateCheck.notificationContent(""))
        assertEquals(fallback, StartupUpdateCheck.notificationContent("   \n  "))
    }
}
