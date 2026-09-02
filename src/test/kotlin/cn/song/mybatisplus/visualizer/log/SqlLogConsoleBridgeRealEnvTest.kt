package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent

/**
 * 日志捕捉链路真实环境模拟测试（T-LogPerf 真实环境回归网，源自《性能分析 20260902-0054》）。
 *
 * 与纯逻辑测试（SqlLogConsoleBridgePerfTest）的分工：本类用**真实平台要素**复现问题形态——
 *  - 真实平台 fixture（BasePlatformTestCase：真项目 + 真 EDT + 真应用服务）；
 *  - 真实 `javax.swing.Timer` 墙钟调度（复现 armed 态 10Hz 常驻轮询问题）；
 *  - 真实 messageBus 事件发布（复现逐行配置同步改事件驱动后的丢失/失效风险）；
 *  - 真实 `com.intellij.execution.ui.ConsoleView` 接口实现 + 反射 Filter 代理链
 *    （复现控制台重复挂载 / 停止后仍捕获问题）。
 * 唯一的替身是控制台组件本体（真实 ConsoleViewImpl 依赖完整运行 tab 生命周期，headless 不可建）。
 */
class SqlLogConsoleBridgeRealEnvTest : BasePlatformTestCase() {

    /**
     * 实现 243 平台 ConsoleView 接口的桩控制台：真实 Swing 组件（进组件树）、
     * addMessageFilter/removeMessageFilter 计数——验证真实反射挂载链路与幂等/摘除。
     */
    private class StubConsoleView : JComponent(), ConsoleView {
        val addedFilters = mutableListOf<Filter>()
        val removedFilters = mutableListOf<Filter>()

        override fun addMessageFilter(filter: Filter) {
            addedFilters += filter
        }

        // 非 override：243 平台 ConsoleView 接口未声明 removeMessageFilter（2026.2 实现类才有），
        // 桩上以普通方法提供，供 detachAllFilters 反射成功摘除
        fun removeMessageFilter(filter: Filter) {
            removedFilters += filter
        }

        override fun getComponent(): JComponent = this
        override fun getPreferredFocusableComponent(): JComponent = this
        override fun dispose() {}
        override fun print(text: String, contentType: ConsoleViewContentType) {}
        override fun clear() {}
        override fun scrollTo(offset: Int) {}
        override fun attachToProcess(processHandler: ProcessHandler) {}
        override fun setOutputPaused(value: Boolean) {}
        override fun isOutputPaused(): Boolean = false
        override fun hasDeferredOutput(): Boolean = false
        override fun performWhenNoDeferredOutput(runnable: Runnable) {}
        override fun setHelpId(helpId: String) {}
        override fun printHyperlink(hyperlinkText: String, hyperlinkInfo: HyperlinkInfo?) {}
        override fun getContentSize(): Int = 0
        override fun canPause(): Boolean = false
        override fun createConsoleActions(): Array<AnAction> = AnAction.EMPTY_ARRAY
        override fun allowHeavyFilters() {}
    }

    private lateinit var manager: SqlLogManager
    private lateinit var console: StubConsoleView

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        console = StubConsoleView()
    }

    override fun tearDown() {
        runCatching { manager.stopCapture() }
        runCatching { manager.dispose() }
        super.tearDown()
    }

    /** 经真实 Filter 代理反射喂一行（对齐控制台 flush 线程逐行 applyFilter 的真实调用形态）。 */
    private fun invokeFilter(line: String) {
        val attached = manager.attachedConsoles[console] ?: error("console not attached")
        invokeOn(attached.first, attached.second, line)
    }

    private fun invokeOn(filter: Any, iface: Class<*>, line: String) {
        val method = iface.getMethod("applyFilter", String::class.java, Int::class.javaPrimitiveType)
        method.invoke(filter, line, line.length)
    }

    /** 泵空 EDT 队列（对齐既有测试惯例：invokeLater 调度的刷新必须 dispatch 才执行）。 */
    private fun pumpEdt() {
        UIUtil.dispatchAllInvocationEvents()
    }

    fun test真实控制台反射挂载_重复扫描幂等_停止后不再捕获() {
        // armed 态：用户点「捕捉」但项目尚未运行（无主控制台）——真实 startCapture 状态机
        manager.startCapture(manual = true)
        pumpEdt()
        assertTrue(manager.capturing)

        // 新控制台出现（轮询/contentAdded 检测到）→ 反射挂载 Filter
        manager.consoleBridge.scanComponent(console)
        assertEquals("ConsoleView 接口实现应经真实反射链挂载恰好一个 Filter", 1, console.addedFilters.size)
        assertTrue(manager.attachedConsoles.containsKey(console))

        // 逐行喂 MyBatis 默认前缀（Preparing: / Parameters:）→ 配对成条目
        invokeFilter("Preparing: SELECT * FROM user WHERE id = ?")
        invokeFilter("Parameters: 1(Long)")
        assertEquals(1, manager.entries.snapshot().size)

        // 重复扫描（轮询每拍都会触发）→ 幂等，不得重复挂载（重复挂载 = 重复解析 + 重复条目）
        manager.consoleBridge.scanComponent(console)
        assertEquals("已挂载控制台重复扫描不得二次挂载", 1, console.addedFilters.size)
        assertEquals(1, manager.entries.snapshot().size)

        // 「停止后仍捕获」问题回归：stop 后 Filter 反射摘除 + 挂载记录清空 + 缓冲暂停。
        // 持有停捕前的代理引用模拟"已摘除但仍被控制台 flush 队列引用"的极端形态——喂行不得入列。
        val (filterProxy, filterIface) = manager.attachedConsoles[console]!!
        manager.stopCapture()
        pumpEdt()
        assertEquals("stop 后 Filter 应经反射摘除", 1, console.removedFilters.size)
        assertTrue("stop 后挂载记录应清空", manager.attachedConsoles.isEmpty())
        invokeOn(filterProxy, filterIface, "Preparing: SELECT * FROM orders WHERE id = ?")
        invokeOn(filterProxy, filterIface, "Parameters: 2(Long)")
        assertEquals("停止捕捉后喂行不得再产生条目", 1, manager.entries.snapshot().size)
    }

    fun test真实messageBus设置变更_解析器配置即时刷新() {
        manager.startCapture(manual = true)
        pumpEdt()
        manager.consoleBridge.scanComponent(console)

        // 默认前缀正常捕获
        invokeFilter("Preparing: SELECT * FROM user WHERE id = ?")
        invokeFilter("Parameters: 1(Long)")
        assertEquals(1, manager.entries.snapshot().size)

        // 用户改前缀（真实路径：设置页 apply → syncPublisher 发布 SETTINGS_CHANGE_TOPIC）
        val state = PluginSettings.getInstance().state
        val oldSqlPrefix = state.logSqlPrefix
        val oldParamPrefix = state.logParamPrefix
        try {
            state.logSqlPrefix = "SQL#"
            state.logParamPrefix = "BIND#"
            project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()

            // 新前缀立即生效（事件驱动刷新取代逐行同步后的关键回归点）
            invokeFilter("SQL# SELECT * FROM orders WHERE id = ?")
            invokeFilter("BIND# 2(Integer)")
            assertEquals("设置变更事件后新前缀应立即生效", 2, manager.entries.snapshot().size)

            // 旧前缀不再匹配（快照已整体切换，非新旧并存）
            invokeFilter("Preparing: SELECT * FROM legacy")
            invokeFilter("Parameters: 3(Long)")
            assertEquals("切换后旧前缀行不得再被解析", 2, manager.entries.snapshot().size)
        } finally {
            // 还原全局设置，避免污染同 JVM 后续测试
            state.logSqlPrefix = oldSqlPrefix
            state.logParamPrefix = oldParamPrefix
            project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        }
    }

    fun test真实EDT快速轮询退避_armed态不再10Hz常驻() {
        // 记录拍时间戳的受控桥（真实 scanAll 链路，仅插桩记录）
        val tickTimes = CopyOnWriteArrayList<Long>()
        val recordingBridge = object : SqlLogConsoleBridge(
            manager.entries,
            parserConfig = { ParserConfig("Preparing:", "Parameters:", true, 20000) },
            syncBufferConfig = {},
            onConsoleAppeared = {},
            isCapturing = { manager.capturing },
        ) {
            override fun scanAll() {
                tickTimes += System.currentTimeMillis()
                super.scanAll()
            }
        }
        manager.installBridgeForTest(recordingBridge)

        // armed 态启动轮询（无主控制台 → 快速轮询起步）；attach 失败的测试环境兜底直接启动轮询
        manager.startCapture(manual = true)
        pumpEdt()
        assertTrue(manager.capturing)
        if (!recordingBridge.isPollingActive) recordingBridge.startPolling()

        try {
            // 真实墙钟 2.6s + 真实 EDT 调度泵：javax.swing.Timer 在 EDT 到期派发，
            // 本循环持续 dispatch 让真实定时器按真实间隔触发（非 mock 时钟）
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2600) {
                pumpEdt()
                Thread.sleep(20)
            }
        } finally {
            manager.stopCapture()
            pumpEdt()
        }

        // 复现原问题：旧实现 100ms 常驻约 26 拍；退避后 500ms→1000ms 仅 2~3 拍
        assertTrue(
            "armed 态 2.6s 内拍数应 <=4（实测 ${tickTimes.size} 拍）——退避未生效或退回 10Hz 常驻",
            tickTimes.size in 1..4,
        )
        // 相邻拍间隔 >= 400ms（500ms 起步；100ms 旧实现此断言必失败）
        for (i in 1 until tickTimes.size) {
            val gap = tickTimes[i] - tickTimes[i - 1]
            assertTrue("相邻拍间隔应 >= 400ms（实测第 $i 拍间隔 ${gap}ms）", gap >= 400)
        }
        // 停止后轮询彻底停止：再泵 700ms 真实墙钟，不得出现任何新拍（快速轮询间隔 500ms 内必到期）
        val lastCount = tickTimes.size
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < 700) {
            pumpEdt()
            Thread.sleep(20)
        }
        assertEquals("stopCapture 后轮询应完全停止", lastCount, tickTimes.size)
        assertFalse(manager.capturing)
    }
}
