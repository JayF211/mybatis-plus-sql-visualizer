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

    // ---------------- T-M28.3/28.6 通知内容（只显示最新版、每行一个标题、有序列表编号） ----------------

    @Test
    fun notificationContentShowsLatestVersionTitlesWithOrderedList() {
        // 用户要求「每行一个标题 + 有序列表编号」：按行拆分（清单约定每行一个标题）、
        // <ol><li> 自动编号、只显示最新版（旧版本行不显示）
        val notes = "v2026.8.15：\nXML 动态 SQL 预览（M24）\n注解 SQL 预览（M25）\nMP 占位展开与多候选（M26）\n" +
            "v2026.8.14：官方 API 覆盖差距清零"
        val content = StartupUpdateCheck.notificationContent(notes)
        assertTrue(
            "应渲染有序列表",
            content.startsWith("<ol><li>XML 动态 SQL 预览（M24）</li><li>注解 SQL 预览（M25）</li>"),
        )
        assertTrue("列表应以 </ol> 结尾", content.contains("</ol>"))
        assertFalse("不得包含旧版本内容", content.contains("v2026.8.14"))
    }

    @Test
    fun stripVersionPrefixVariants() {
        // 容忍有无 v 前缀与中英文冒号
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("v2026.8.15：功能A"))
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("2026.8.15: 功能A"))
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("功能A"))
        assertEquals("", StartupUpdateCheck.stripVersionPrefix("v2026.8.15："))
    }

    @Test
    fun notificationContentTruncatesEachLongLine() {
        val long = "标".repeat(300)
        val content = StartupUpdateCheck.notificationContent(long)
        assertTrue("标题应截断为 60 字符加省略号", content.startsWith("<ol><li>" + "标".repeat(60) + "…"))
        assertTrue("截断后应带 Gitee 链接", content.contains("href="))
    }

    @Test
    fun notificationContentEscapesHtml() {
        // 变更日志可能含 SQL 动态标签（<if> 等）——HTML 转义防渲染破坏（文本节点引号无需转义）
        val content = StartupUpdateCheck.notificationContent("v1：<if test=\"x & y\">")
        assertTrue(content.startsWith("<ol><li>v1：&lt;if test=\"x &amp; y\"&gt;</li>"))
        assertTrue(content.contains("href="))
    }

    @Test
    fun notificationContentAppendsGiteeLink() {
        // T-M28.4：内容末尾附 Gitee 发布页链接（可点击跳转）
        val content = StartupUpdateCheck.notificationContent("v2026.8.15：测试")
        assertTrue("应包含 Gitee 链接", content.contains("href=\"" + cn.song.mybatisplus.visualizer.update.UpdateSource.GITEE_RELEASES_URL + "\""))
        assertTrue("应包含链接文案", content.contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("update.notification.viewGitee")))
        // 无标题时默认文案同样带链接
        val fallback = StartupUpdateCheck.notificationContent(null)
        assertTrue(fallback.contains("href="))
    }

    @Test
    fun notificationContentFallsBackForMissingNotes() {
        val expected = MpSqlVisualizerBundle.message("update.notification.content") +
            "<br>\n<a href=\"" + cn.song.mybatisplus.visualizer.update.UpdateSource.GITEE_RELEASES_URL + "\">" +
            MpSqlVisualizerBundle.message("update.notification.viewGitee") + "</a>"
        assertEquals(expected, StartupUpdateCheck.notificationContent(null))
        assertEquals(expected, StartupUpdateCheck.notificationContent(""))
        assertEquals(expected, StartupUpdateCheck.notificationContent("   \n  "))
    }
}
