package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * M37.3 首条 SQL 自动展开工具窗测试：
 *
 *  - 决策纯函数：设置开启 + 距上次展开超过防抖窗口才展开；
 *  - 接线与面板解耦：buffer 无条件监听（未订阅 UI 时首条捕获也触发）；
 *  - 防抖：防抖窗口内多条 SQL 只展开一次。
 */
class SqlLogManagerAutoShowTest : BasePlatformTestCase() {

    private class FakeBridge : SqlLogConsoleBridge(SqlLogEntryBuffer()) {
        override fun attach(project: Project): Boolean = true
        override fun hasMainConsole(): Boolean = true
    }

    private fun manager(): SqlLogManager =
        SqlLogManager(project).apply { installBridgeForTest(FakeBridge()) }

    private fun entry(sql: String = "SELECT 1") = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 0L,
    )

    fun testDecisionRequiresEnabledAndDebounce() {
        val d = SqlLogManager.AUTO_SHOW_DEBOUNCE_MS
        assertFalse("设置关闭不展开", SqlLogManager.shouldAutoShow(false, Long.MAX_VALUE))
        assertFalse("防抖窗口内不展开", SqlLogManager.shouldAutoShow(true, d - 1))
        assertTrue("超防抖且开启则展开", SqlLogManager.shouldAutoShow(true, d))
    }

    fun testBufferEntryTriggersAutoShow() {
        PluginSettings.getInstance().state.logAutoOpenOnCapture = true
        val manager = manager()
        var calls = 0
        manager.onAutoShowRequest = { calls++ }
        // 条目入缓冲 → 触发展开请求（无需任何 UI 订阅）
        manager.entries.addEntry(entry())
        assertEquals("首条 SQL 应触发展开请求", 1, calls)
    }

    fun testDebounceSuppressesRepeat() {
        PluginSettings.getInstance().state.logAutoOpenOnCapture = true
        val manager = manager()
        var calls = 0
        manager.onAutoShowRequest = { calls++ }
        manager.entries.addEntry(entry())
        manager.entries.addEntry(entry("SELECT 2"))
        manager.entries.clear()
        manager.entries.addEntry(entry("SELECT 3"))
        assertEquals("防抖窗口内多条 SQL 只展开一次", 1, calls)
        // 重置防抖基准后再次触发
        manager.lastAutoShowAt = 0L
        manager.entries.addEntry(entry("SELECT 4"))
        assertEquals(2, calls)
    }

    fun testDisabledSettingNeverTriggers() {
        PluginSettings.getInstance().state.logAutoOpenOnCapture = false
        val manager = manager()
        var calls = 0
        manager.onAutoShowRequest = { calls++ }
        manager.entries.addEntry(entry())
        assertEquals("设置关闭时不触发展开", 0, calls)
    }
}
