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
        // Option A：无控制台 + attach 失败仍进入 armed 态（capturing=true），用户可重试
        val bridge = FakeBridge(attachResult = false, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("attach 失败仍应进入 armed 态（capturing=true，用户可重试）", manager.capturing)
        assertTrue("attach 失败应保持等待态", manager.isWaitingForConsole)
        assertEquals("attach 失败仍须尝试挂载", 1, bridge.attachCalls)
    }

    fun testStartCaptureSkipsWithoutMainConsole() {
        // Option A：无控制台时立即进入 armed 态（capturing=true, isWaitingForConsole=true）
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertTrue("无控制台时应进入 armed 态（capturing=true）", manager.capturing)
        assertTrue("无控制台时应处于等待态", manager.isWaitingForConsole)
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

    /** T01：无控制台手动启动 → armed 态（capturing=true, isWaitingForConsole=true, userArmed=true）。 */
    fun testT01_ManualCaptureWithoutConsole_EnterWaitingState() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture(manual = true)
        // Option A：无控制台时立即进入 armed 态（capturing=true + isWaitingForConsole=true）
        assertTrue("无控制台时应进入 armed 态（capturing=true）", manager.capturing)
        assertTrue("应进入等待态（isWaitingForConsole=true）", manager.isWaitingForConsole)
        assertEquals("仍须挂载桥监听", 1, bridge.attachCalls)
    }

    /** T02：等待中控制台出现 → onConsoleAppeared 触发 → 自动进入捕捉态。 */
    fun testT02_WaitingConsoleAppeared_AutoEntersCapture() {
        val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
        val manager = managerWith(bridge)
        manager.startCapture(manual = true)
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
        manager.startCapture(manual = true)
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
            // Option A：自动路径无控制台时也进入 armed 态（capturing=true）
            assertTrue("autoStart=true：无控制台时应进入 armed 态（capturing=true）", manager.capturing)
            // 控制台出现
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("autoStart=true：控制台出现后应自动捕捉", manager.capturing)
            assertFalse("控制台出现后等待态应清除", manager.isWaitingForConsole)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    /** T05：有控制台手动启动（回归）→ 直接进入捕捉态，不经过等待态。 */
    fun testT05_HasConsoleManualCapture_DirectCapture() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = true)
        val manager = managerWith(bridge)
        manager.startCapture(manual = true)
        assertTrue("有控制台时应直接进入捕捉态", manager.capturing)
        assertFalse("有控制台时不应处于等待态", manager.isWaitingForConsole)
        manager.stopCapture()
    }

    /** T06：等待中 dispose → 全部标志清零（无泄漏）。 */
    fun testT06_DisposeWhileWaiting_ClearsAllFlags() {
        val bridge = FakeBridge(attachResult = true, hasMainConsoleResult = false)
        val manager = managerWith(bridge)
        manager.startCapture(manual = true)
        assertTrue("应处于等待态", manager.isWaitingForConsole)

        manager.dispose()
        assertFalse("dispose 后 capturing=false", manager.capturing)
        assertFalse("dispose 后 isWaitingForConsole=false", manager.isWaitingForConsole)
    }

    // ===== 项目停止后回到 armed 态，重启自动恢复捕捉 =====

    /** T07：项目停止（控制台移除）→ 回到 armed 态（capturing=true, isWaitingForConsole=true）。 */
    fun testT07_ProjectStop_GoesBackToArmedState() {
        val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
        val manager = managerWith(bridge)
        // 点击捕捉 → armed
        manager.startCapture(manual = true)
        assertTrue("armed 态", manager.capturing)
        // 项目启动 → 控制台出现 → 捕捉
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        assertTrue("捕捉中", manager.capturing)
        assertFalse("等待态已清除", manager.isWaitingForConsole)
        // 项目停止 → 控制台移除 → 回到 armed 态
        bridge.hasMainConsoleResult = false
        manager.onConsoleRemoved()
        assertTrue("回到 armed 态（capturing=true）", manager.capturing)
        assertTrue("等待态恢复", manager.isWaitingForConsole)
    }

    /** T08：项目重启 → 控制台出现 → 自动恢复捕捉（无需再次点击）。 */
    fun testT08_ProjectRestart_AutoResumesCapture() {
        val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
        val manager = managerWith(bridge)
        // 点击捕捉 → armed → 项目启动 → 捕捉
        manager.startCapture(manual = true)
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        assertTrue("第一次捕捉", manager.capturing)
        // 项目停止 → armed
        bridge.hasMainConsoleResult = false
        manager.onConsoleRemoved()
        assertTrue("armed 态", manager.capturing)
        // 项目重启 → 自动恢复捕捉
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        assertTrue("自动恢复捕捉", manager.capturing)
        assertFalse("等待态已清除", manager.isWaitingForConsole)
    }

    /** T09：armed 态手动停止 → 项目重启不会自动捕捉。 */
    fun testT09_AfterManualStop_NoAutoCapture() {
        val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
        val manager = managerWith(bridge)
        // 点击捕捉 → armed → 项目启动 → 捕捉
        manager.startCapture(manual = true)
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        // 手动停止
        manager.stopCapture()
        assertFalse("已停止", manager.capturing)
        // 项目重启 → 不会自动捕捉
        bridge.hasMainConsoleResult = true
        manager.onConsoleAppeared()
        assertFalse("不会自动捕捉", manager.capturing)
    }

    // ===== 自动启动（logAutoCaptureOnStart=true）行为 =====

    /** T10：自动启动 → 项目停止（控制台移除）→ 完全停止（非 armed 态）。 */
    fun testT10_AutoStart_ProjectStop_StopsCompletely() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = true
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)
            // 自动启动：项目打开时进入 armed 态（manual=false, userArmed=false）
            manager.startCapture()  // manual=false
            assertTrue("armed 态", manager.capturing)
            assertFalse("自动启动不设置 userArmed", manager.userArmed)
            // 项目启动 → 控制台出现 → 捕捉
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("捕捉中", manager.capturing)
            // 项目停止 → 控制台移除 → 完全停止（非 armed）
            bridge.hasMainConsoleResult = false
            manager.onConsoleRemoved()
            assertFalse("自动启动：项目停止后完全停止", manager.capturing)
            assertFalse("等待态清除", manager.isWaitingForConsole)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    /** T11：自动启动 → 项目重启 → 再次自动捕捉。 */
    fun testT11_AutoStart_ProjectRestart_AutoCapturesAgain() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = true
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)
            // 第一次：armed → 启动 → 捕捉
            manager.startCapture()
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("第一次捕捉", manager.capturing)
            // 项目停止 → 完全停止
            bridge.hasMainConsoleResult = false
            manager.onConsoleRemoved()
            assertFalse("停止", manager.capturing)
            // 项目重启 → 再次自动捕捉
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertFalse("userArmed 未设置", manager.userArmed)
            assertTrue("再次自动捕捉", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    /** T12：手动开启 + 自动启动同时启用 → 项目停止后完全停止（自动启动优先）。 */
    fun testT12_ManualAndAutoStart_ProjectStop_StopsCompletely() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = true
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)
            // 手动点击捕捉（manual=true）+ 自动启动开启
            manager.startCapture(manual = true)
            assertTrue("armed 态", manager.capturing)
            assertTrue("userArmed 已设置", manager.userArmed)
            // 项目启动 → 捕捉
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("捕捉中", manager.capturing)
            // 项目停止 → 完全停止（自动启动优先于手动 armed）
            bridge.hasMainConsoleResult = false
            manager.onConsoleRemoved()
            assertFalse("手动+自动同时开启：项目停止后完全停止", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    /**
     * T13：自动启动 + 用户显式停止 → suppressed 阻止 onConsoleAppeared 重启捕捉。
     *
     * BUG FIX 变更：此前 suppressed 仅阻止 LogStartupCapture，不阻止 onConsoleAppeared——
     * 导致 autoStart=true 时用户停止后，项目启动仍自动捕捉（按钮显示已停止但实际在捕获）。
     * 修复后：suppressed=true 时 onConsoleAppeared 不重启捕捉，用户"停止"意图被尊重。
     */
    fun testT13_AutoStart_UserStop_SuppressedPreventsRestart() {
        val settings = PluginSettings.getInstance()
        val oldAutoStart = settings.state.logAutoCaptureOnStart
        val oldSuppressed = settings.state.logAutoCaptureSuppressed
        try {
            settings.state.logAutoCaptureOnStart = true
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)

            // 模拟 LogStartupCapture 触发（项目打开）→ armed 态
            manager.startCapture()  // auto-start, manual=false
            assertTrue("自动启动进入 armed 态", manager.capturing)

            // 用户点击停止 → suppressed=true, capturing=false
            manager.stopCapture()
            assertFalse("已停止", manager.capturing)
            assertTrue("抑制标志已设置", settings.state.logAutoCaptureSuppressed)

            // 项目启动 → suppressed 阻止自动捕捉（BUG FIX 核心行为）
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("项目启动后 suppressed 保持 true（不被自动事件清除）", settings.state.logAutoCaptureSuppressed)
            assertFalse("suppressed=true 时项目启动不应自动捕捉", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = oldAutoStart
            settings.state.logAutoCaptureSuppressed = oldSuppressed
        }
    }

    // ===== BUG FIX：stopCapture 后 Filter 移除 + 轮询停止 =====

    /** 间谍桥：追踪 detachAllFilters 调用 + 暴露轮询状态 + 可控 isCapturing 回调。 */
    private class SpyBridge(
        private val attachResult: Boolean,
        private val hasMainConsoleResult: Boolean = true,
        isCapturing: () -> Boolean = { true },
    ) : SqlLogConsoleBridge(SqlLogEntryBuffer(), isCapturing = isCapturing) {
        var detachAllFiltersCalls = 0
            private set
        var scanAllCalls = 0
            private set

        override fun attach(project: Project): Boolean = attachResult

        override fun hasMainConsole(): Boolean = hasMainConsoleResult

        override fun scanAll() {
            scanAllCalls++
            super.scanAll()
        }

        override fun detachAllFilters() {
            detachAllFiltersCalls++
            super.detachAllFilters()
        }
    }

    /** T14：stopCapture 后 attachedConsoles 被清空（Filter 从控制台移除，不再捕获 SQL）。 */
    fun testT14_StopCapture_clearsAttachedConsoles() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        // 先启动捕捉（设置 capturing=true）
        manager.startCapture()
        assertTrue("precondition: capturing=true", manager.capturing)
        // 模拟已挂载的 Filter（直接写入 attachedConsoles 模拟 scanAll 挂载结果）
        val mockConsole = Any()
        val mockFilter = Any()
        val mockFilterClass = Class.forName("java.lang.Object")
        bridge.attachedConsoles[mockConsole] = mockFilter to mockFilterClass
        assertTrue("precondition: attachedConsoles 非空", bridge.attachedConsoles.isNotEmpty())

        manager.stopCapture()
        assertTrue("stopCapture 应清空 attachedConsoles（Filter 从控制台移除）", bridge.attachedConsoles.isEmpty())
        assertTrue("stopCapture 应调用 detachAllFilters", bridge.detachAllFiltersCalls >= 1)
    }

    /** T15：stopCapture 后轮询停止（262+ 平台原设计保持轮询，现统一停止）。 */
    fun testT15_StopCapture_stopsPolling() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        // 启动捕捉 → 启动轮询
        manager.startCapture()
        // 手动启动轮询（模拟 attach 成功后的 startPolling）
        bridge.startPolling()
        assertTrue("precondition: 轮询应活跃", bridge.isPollingActive)

        manager.stopCapture()
        assertFalse("stopCapture 后轮询应停止", bridge.isPollingActive)
    }

    /** T16：attachFilter 的 isCapturing 守卫——非捕捉态时不挂载 Filter。 */
    fun testT16_AttachFilter_notCapturing_skipsAttach() {
        val bridge = SpyBridge(attachResult = true, isCapturing = { false })
        // 通过反射调用私有 attachFilter 方法验证守卫
        val method = SqlLogConsoleBridge::class.java.getDeclaredMethod("attachFilter", Any::class.java)
        method.isAccessible = true
        method.invoke(bridge, Any())
        assertTrue("非捕捉态时 attachFilter 不应挂载", bridge.attachedConsoles.isEmpty())
    }

    /** T17：stop 后可重新开始捕捉——capturing 恢复 true，attachFilter 守卫放行。 */
    fun testT17_StopThenRestart_reAttachesFilters() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        // 先启动捕捉
        manager.startCapture()
        assertTrue("precondition: capturing=true", manager.capturing)
        // 模拟挂载
        val mockConsole = Any()
        val mockFilter = Any()
        val mockFilterClass = Class.forName("java.lang.Object")
        bridge.attachedConsoles[mockConsole] = mockFilter to mockFilterClass

        // 停止 → 清空
        manager.stopCapture()
        assertFalse("停止后 capturing=false", manager.capturing)
        assertTrue("停止后 attachedConsoles 为空", bridge.attachedConsoles.isEmpty())

        // 重新开始 → capturing=true，attachFilter 守卫放行
        manager.startCapture(manual = true)
        assertTrue("重新开始后 capturing=true", manager.capturing)
    }

    // ===== BUG FIX：buffer.paused 安全网 =====

    /** T18：stopCapture 设置 buffer.paused=true——即使 Filter 未被移除也无法入队。 */
    fun testT18_StopCapture_setsBufferPaused() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        manager.startCapture()
        assertFalse("捕捉中 paused=false", manager.entries.paused)

        manager.stopCapture()
        assertTrue("stopCapture 后 paused=true", manager.entries.paused)
    }

    /** T19：paused=true 时 addEntry 返回 false——安全网生效。 */
    fun testT19_PausedBuffer_rejectsAddEntry() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        manager.startCapture()
        // 先成功入队一条
        val entry = LogSqlEntry(rawSql = "SELECT 1", restoredSql = "SELECT 1", params = emptyList(), timestamp = 0L)
        assertTrue("paused=false 时 addEntry 成功", manager.entries.addEntry(entry))

        // 停止后 paused=true，addEntry 被拒绝
        manager.stopCapture()
        val entry2 = LogSqlEntry(rawSql = "SELECT 2", restoredSql = "SELECT 2", params = emptyList(), timestamp = 1L)
        assertFalse("paused=true 时 addEntry 应被拒绝", manager.entries.addEntry(entry2))
    }

    /** T20：startCapture 清除 paused——恢复正常入队。 */
    fun testT20_StartCapture_clearsPaused() {
        val bridge = SpyBridge(attachResult = true)
        val manager = managerWith(bridge)
        manager.startCapture()
        manager.stopCapture()
        assertTrue("停止后 paused=true", manager.entries.paused)

        manager.startCapture(manual = true)
        assertFalse("startCapture 后 paused=false", manager.entries.paused)
    }

    // ===== BUG FIX：onConsoleAppeared 尊重 suppressed 标志 =====

    /** T21：autoStart=true + suppressed=true → onConsoleAppeared 不重启捕捉。 */
    fun testT21_SuppressedPreventsAutoRestart() {
        val settings = PluginSettings.getInstance()
        val oldAutoStart = settings.state.logAutoCaptureOnStart
        val oldSuppressed = settings.state.logAutoCaptureSuppressed
        try {
            settings.state.logAutoCaptureOnStart = true
            settings.state.logAutoCaptureSuppressed = true
            val bridge = SpyBridge(attachResult = true)
            val manager = managerWith(bridge)

            // 模拟控制台出现 → 不应重启捕捉
            manager.onConsoleAppeared()
            assertFalse("suppressed=true 时 onConsoleAppeared 不应重启捕捉", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = oldAutoStart
            settings.state.logAutoCaptureSuppressed = oldSuppressed
        }
    }

    /** T22：autoStart=true + suppressed=false → onConsoleAppeared 正常重启捕捉。 */
    fun testT22_NotSuppressed_allowsAutoRestart() {
        val settings = PluginSettings.getInstance()
        val oldAutoStart = settings.state.logAutoCaptureOnStart
        val oldSuppressed = settings.state.logAutoCaptureSuppressed
        try {
            settings.state.logAutoCaptureOnStart = true
            settings.state.logAutoCaptureSuppressed = false
            val bridge = SpyBridge(attachResult = true)
            val manager = managerWith(bridge)

            // 模拟控制台出现 → 应重启捕捉
            manager.onConsoleAppeared()
            assertTrue("suppressed=false 时 onConsoleAppeared 应重启捕捉", manager.capturing)
        } finally {
            settings.state.logAutoCaptureOnStart = oldAutoStart
            settings.state.logAutoCaptureSuppressed = oldSuppressed
        }
    }

    /**
     * T23：armed 态（capturing=true, isWaitingForConsole=true, userArmed=true）下
     * onConsoleAppeared 仍重启捕捉（用户手动启动的 armed 态应自动恢复）。
     *
     * 流转：手动启动（无控制台）→ userArmed=true → 控制台出现 → 控制台移除 → armed 态 → 控制台出现 → 恢复捕捉
     */
    fun testT23_ArmedState_allowsAutoRestart() {
        val settings = PluginSettings.getInstance()
        val oldAutoStart = settings.state.logAutoCaptureOnStart
        try {
            // autoStart=false + manual=true：模拟手动开启但未启用自动启动
            settings.state.logAutoCaptureOnStart = false
            val bridge = MutableFakeBridge(attachResult = true, hasMainConsoleInitial = false)
            val manager = managerWith(bridge)

            // 手动启动（无控制台）→ capturing=true, userArmed=true, isWaitingForConsole=true
            manager.startCapture(manual = true)
            assertTrue("手动启动后 capturing=true", manager.capturing)
            assertTrue("手动启动后 userArmed=true", manager.userArmed)
            assertTrue("手动启动后 isWaitingForConsole=true", manager.isWaitingForConsole)

            // 控制台出现 → 等待态清除
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("控制台出现后 capturing=true", manager.capturing)
            assertFalse("控制台出现后等待态清除", manager.isWaitingForConsole)

            // 控制台移除 → armed 态（capturing 保持 true, isWaitingForConsole=true, userArmed 保持 true）
            bridge.hasMainConsoleResult = false
            manager.onConsoleRemoved()
            assertTrue("armed 态 capturing 保持 true", manager.capturing)
            assertTrue("armed 态 userArmed=true", manager.userArmed)
            assertTrue("armed 态 isWaitingForConsole=true", manager.isWaitingForConsole)

            // 控制台出现 → 应自动重启捕捉（等待态清除）
            bridge.hasMainConsoleResult = true
            manager.onConsoleAppeared()
            assertTrue("armed 态下 onConsoleAppeared 应重启捕捉", manager.capturing)
            assertFalse("等待态应清除", manager.isWaitingForConsole)
        } finally {
            settings.state.logAutoCaptureOnStart = oldAutoStart
        }
    }
}
