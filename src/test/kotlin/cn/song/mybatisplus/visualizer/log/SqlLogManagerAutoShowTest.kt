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
 *  - 决策纯函数：设置开启 + 距上次展开超过防抖窗口 + 未被用户关闭抑制才展开；
 *  - 接线与面板解耦：buffer 无条件监听（未订阅 UI 时首条捕获也触发）；
 *  - 防抖：防抖窗口内多条 SQL 只展开一次；
 *  - T-AutoShowSuppress：用户手动关闭后本会话抑制（转换沿判定 + 设置重启用解除）。
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
        assertFalse("设置关闭不展开", SqlLogManager.shouldAutoShow(false, Long.MAX_VALUE, false))
        assertFalse("防抖窗口内不展开", SqlLogManager.shouldAutoShow(true, d - 1, false))
        assertTrue("超防抖且开启则展开", SqlLogManager.shouldAutoShow(true, d, false))
        // T-AutoShowSuppress：用户手动关闭抑制优先于其余条件
        assertFalse("用户关闭后本会话抑制，不展开", SqlLogManager.shouldAutoShow(true, Long.MAX_VALUE, true))
    }

    fun testUserCloseSuppressesUntilFlagCleared() {
        PluginSettings.getInstance().state.logAutoOpenOnCapture = true
        val manager = manager()
        var calls = 0
        manager.onAutoShowRequest = { calls++ }
        // 模拟用户手动关闭窗口（转换沿监听已在真实 ToolWindow 上置位，此处直接验证抑制态生效）
        manager.autoShowSuppressedByUserClose = true
        manager.entries.addEntry(entry())
        assertEquals("用户关闭后条目到达不再自动展开", 0, calls)
        // 清除抑制（设置重启用 / 会话重置）后恢复展开能力
        manager.autoShowSuppressedByUserClose = false
        manager.lastAutoShowAt = 0L
        manager.entries.addEntry(entry("SELECT 2"))
        assertEquals("抑制解除后恢复自动展开", 1, calls)
    }

    fun testUserCloseTransitionEdgeOnlyOnVisibleToInvisible() {
        // 只有 可见→不可见 沿是手动关闭；其余组合（初值/展示/保持）都不触发
        assertTrue("可见→不可见 = 手动关闭", SqlLogManager.isUserCloseTransition(true, false))
        assertFalse("不可见→可见 = 展开（含自动展开），非关闭", SqlLogManager.isUserCloseTransition(false, true))
        assertFalse("持续可见非关闭", SqlLogManager.isUserCloseTransition(true, true))
        assertFalse("持续不可见非关闭（初值/布局中间态）", SqlLogManager.isUserCloseTransition(false, false))
    }

    fun testSettingReenableClearsSuppression() {
        PluginSettings.getInstance().state.logAutoOpenOnCapture = true
        val manager = manager()
        manager.autoShowSuppressedByUserClose = true
        // 用户关闭开关再重新开启（false→true 重启用沿）→ 会话抑制解除
        PluginSettings.getInstance().state.logAutoOpenOnCapture = false
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        PluginSettings.getInstance().state.logAutoOpenOnCapture = true
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        assertFalse("设置重新开启应解除会话抑制", manager.autoShowSuppressedByUserClose)
        // 开关保持 true 的普通设置变更不误清抑制
        manager.autoShowSuppressedByUserClose = true
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        assertTrue("开关未经历重启用沿时抑制保持", manager.autoShowSuppressedByUserClose)
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
