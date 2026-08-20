package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * SqlLogManager 设置生效性行为测试（T-xx 设置生效性审计）：
 *
 *  - capturing 状态机（D3）：桥 attach 失败**不得**进入「已捕捉但无监听」的假状态
 *    （修复前 capturing 先置 true、attach 失败静默无监听且无感知无重试）；
 *  - 自动打开决策与面板解耦（D2）：窗口从未打开（未 addUiListener）时首条捕获也触发自动打开
 *    （修复前自动打开监听仅在面板创建时接线——启动自动监听场景永不触发，logAutoOpenOnCapture 形同虚设）；
 *  - 项目生命周期语义（D4）：启动自动监听仅由 LogStartupCapture（postStartupActivity）在项目打开时触发，
 *    项目关闭（dispose）后 capturing 自动重置——设置页勾选后不立即监听已打开项目。
 */
class SqlLogManagerBehaviorTest : BasePlatformTestCase() {

    /** 受控桥（生产桥 open 化——内部测试缝）：attach 结果注入 + 主控制台存在性注入，验证 capturing 状态机。 */
    private class FakeBridge(
        private val attachResult: Boolean,
        private val hasMainConsoleResult: Boolean = true,
    ) : SqlLogConsoleBridge(SqlLogEntryBuffer()) {
        var attachCalls = 0
            private set

        override fun attach(project: Project): Boolean {
            attachCalls++
            return attachResult
        }

        override fun hasMainConsole(): Boolean = hasMainConsoleResult
    }

    /** 可变主控制台存在性桥（T02/T04 模拟控制台动态出现）。 */
    private class MutableFakeBridge(
        private val attachResult: Boolean,
        hasMainConsoleInitial: Boolean = false,
    ) : SqlLogConsoleBridge(SqlLogEntryBuffer()) {
        var hasMainConsoleResult = hasMainConsoleInitial
        var attachCalls = 0
            private set

        override fun attach(project: Project): Boolean {
            attachCalls++
            return attachResult
        }

        override fun hasMainConsole(): Boolean = hasMainConsoleResult
    }

    /** 装配注入受控桥的 manager（生产构造 + 测试缝替换桥——不触碰轻服务构造函数解析）。 */
    private fun managerWith(bridge: SqlLogConsoleBridge): SqlLogManager =
        SqlLogManager(project).apply { installBridgeForTest(bridge) }

    fun testStartCaptureKeepsCapturingOffWhenAttachFails() {
        val manager = managerWith(FakeBridge(attachResult = false))
        manager.startCapture()
        assertFalse(
            "attach 失败不得进入「已捕捉但无监听」的假状态（修复前 capturing 恒被置 true，静默失败）",
            manager.capturing,
        )
    }

    fun testStartCaptureSkipsWithoutMainConsole() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertFalse(
            "无运行中的主控制台时不得进入捕捉态（项目打开未运行不监听，等待主控制台出现）",
            manager.capturing,
        )
        assertEquals(
            "无主控制台时仍须挂载桥监听（否则主控制台出现时检测不到——图标不刷新，日志实证）",
            1,
            bridge.attachCalls,
        )
    }

    fun testStartCaptureSetsCapturingWhenAttachSucceeds() {
        val manager = managerWith(FakeBridge(attachResult = true))
        manager.startCapture()
        assertTrue("attach 成功且存在主控制台才进入捕捉态", manager.capturing)
        manager.stopCapture()
        assertFalse("停止后恢复未捕捉态", manager.capturing)
    }

    fun testAutoOpenDecisionFiresWithoutUiListener() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoOpenOnCapture
        try {
            settings.state.logAutoOpenOnCapture = true
            // 不调用 addUiListener——模拟工具窗口从未打开（启动自动监听旗舰场景）
            val manager = SqlLogManager(project)
            assertFalse(manager.autoOpenFiredForTest())
            manager.entries.addEntry(
                LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 0L),
            )
            assertTrue(
                "窗口未打开（无 UI 订阅）时首条捕获也应触发自动打开决策（修复前监听仅在面板创建时接线）",
                manager.autoOpenFiredForTest(),
            )
        } finally {
            settings.state.logAutoOpenOnCapture = old
        }
    }

    fun testAutoOpenDisabledStaysSilent() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoOpenOnCapture
        try {
            settings.state.logAutoOpenOnCapture = false
            val manager = SqlLogManager(project)
            manager.entries.addEntry(
                LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 0L),
            )
            assertFalse("开关关闭时自动打开决策不触发", manager.autoOpenFiredForTest())
        } finally {
            settings.state.logAutoOpenOnCapture = old
        }
    }

    /**
     * 项目生命周期语义：startCapture 进入捕捉态后，dispose（项目关闭）应自动停止并重置 capturing。
     * 回归防护：启动自动监听仅由 LogStartupCapture（postStartupActivity）在项目打开时触发，
     * 不得在设置页 apply 时立即传播到已打开项目。
     */
    fun testDisposeResetsCapturing() {
        val manager = managerWith(FakeBridge(attachResult = true))
        manager.startCapture()
        assertTrue("startCapture 后应进入捕捉态", manager.capturing)
        manager.dispose()
        assertFalse("dispose（项目关闭）后 capturing 应重置为 false", manager.capturing)
    }

    // ===== T-13：手动捕捉无需等待项目运行（userRequestedCapture 意图传递） =====

    /** T01：无控制台手动启动 → 等待态，桥已挂载。 */
    fun testT01_ManualCaptureWithoutConsole_EnterWaitingState() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertFalse("无控制台时不得进入捕捉态", manager.capturing)
        assertTrue("应进入等待态（isWaitingForConsole=true）", manager.isWaitingForConsole)
        assertEquals("仍须挂载桥监听", 1, bridge.attachCalls)
    }

    /** T02：等待中控制台出现 → onConsoleAppeared 触发 → 自动进入捕捉态。 */
    fun testT02_WaitingConsoleAppeared_AutoEntersCapture() {
        val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("应处于等待态", manager.isWaitingForConsole)

        // 模拟控制台出现
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        assertTrue("控制台出现后应自动进入捕捉态", manager.capturing)
        assertFalse("等待态应清除", manager.isWaitingForConsole)
    }

    /** T03：等待中手动停止 → 全部标志清零。 */
    fun testT03_WaitingManualStop_ClearsAllFlags() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("应处于等待态", manager.isWaitingForConsole)

        manager.stopCapture()
        assertFalse("stop 后 capturing=false", manager.capturing)
        assertFalse("stop 后 isWaitingForConsole=false", manager.isWaitingForConsole)
    }

    /** T04：自动启动设置不受影响——autoStart=true 时 onConsoleAppeared 独立触发捕捉。 */
    fun testT04_AutoStartSetting_UnaffectedByNewFeature() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = true
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)
            // 自动路径：LogStartupCapture 触发 startCapture（无控制台）
            manager.startCapture()
            assertFalse("无控制台时自动路径也不得进入捕捉态", manager.capturing)
            // 控制台出现
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("autoStart=true：控制台出现后应自动进入捕捉态", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    /** T05：有控制台手动启动（回归）→ 直接进入捕捉态，不经过等待态。 */
    fun testT05_HasConsoleManualCapture_DirectCapture() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = true)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("有控制台时应直接进入捕捉态", manager.capturing)
        assertFalse("有控制台时不应处于等待态", manager.isWaitingForConsole)
        manager.stopCapture()
    }

    /** T06：等待中 dispose → 全部标志清零（无泄漏）。 */
    fun testT06_DisposeWhileWaiting_ClearsAllFlags() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("应处于等待态", manager.isWaitingForConsole)

        manager.dispose()
        assertFalse("dispose 后 capturing=false", manager.capturing)
        assertFalse("dispose 后 isWaitingForConsole=false", manager.isWaitingForConsole)
    }
}
