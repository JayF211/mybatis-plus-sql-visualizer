package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-PS4-02 双路解析封口测试（EP consoleFilterProvider × 桥 addMessageFilter 双 filter 并发喂行）：
 *
 * ①主流时序（控制台先存在→点捕捉→桥挂载→行到达→EP 首行置位 epActive→桥短路）——
 *   断言条目单路化：去重关闭，靠短路本身防重（不等缓冲去重兜底）+ 短路计数探针递增；
 * ②armed-waiting 时序（捕捉先开、控制台后出现）——EP 先挂首行置位，桥后挂即被挂载面守卫
 *   （attachFilter 既有 epActive 守卫）跳过，条目同样单路；
 * ③行为语义零变化——双路时序与单路时序的最终条目集逐条一致；
 * ④不漏捕方向——epActive 未置位时桥照常解析（短路只作用于 EP 已生效之后）；
 * ⑤EP timePrefix 缓存契约——manager 桥侧缓存惰性补种 / 设置变更刷新 / blank 归 null。
 *
 * 桩控制台只满足 attachFilter 的反射面（addMessageFilter/getEditor），不进组件树——
 * 经 internal 测试缝直接挂载；filter 代理实现 `com.intellij.execution.filters.Filter`
 * （测试 classpath 存在），直接调用 applyFilter 模拟平台喂行。
 */
class EpBridgeSinglePathTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = project.getService(SqlLogManager::class.java)
        manager.entries.dedupeEnabled = false
        manager.entries.clear()
        SqlCaptureGate.epActive = false
        SqlCaptureGate.paused.set(false)
    }

    override fun tearDown() {
        SqlCaptureGate.epActive = false
        manager.setCapturingForTest(false)
        manager.entries.clear()
        super.tearDown()
    }

    /** 反射面桩控制台：记录挂载的 filter；getEditor 返回 null（重放守卫降级停用，聚焦短路逻辑）。 */
    private class FakeConsole {
        val attached = mutableListOf<Any>()
        fun addMessageFilter(filter: Filter) {
            attached += filter
        }
        @Suppress("unused")
        fun getEditor(): Any? = null
    }

    private fun newBridge(): SqlLogConsoleBridge = SqlLogConsoleBridge(manager.entries)

    private fun newEpFilter(): Filter = SqlConsoleBridgeFilterProvider().getDefaultFilters(project).single()

    private fun bridgeFilterOf(bridge: SqlLogConsoleBridge, console: FakeConsole): Filter =
        bridge.attachedConsoles[console]!!.first as Filter

    /** MyBatis 配对行（Preparing + Parameters 两行 → 一条完整条目）。 */
    private fun sqlLines(n: Int) = listOf(
        "==>  Preparing: SELECT * FROM t$n WHERE id = ?",
        "==> Parameters: $n(Long)",
    )

    /** 条目稳定投影（timestamp 等易变字段不参与——两时序对照只看捕获语义字段）。 */
    private fun stableProjection(e: LogSqlEntry) = listOf(
        e.rawSql, e.restoredSql, e.params, e.source, e.executionTimeMs, e.sourceFile, e.sourceLine,
    )

    private fun stableProjectionOf(entries: List<LogSqlEntry>) = entries.map(::stableProjection)

    // ---------- ①主流时序：桥先挂，行到达时 EP 先置位 epActive，桥侧短路 ----------

    fun test主流时序_桥先挂_EP首行置位后桥侧短路_条目单路化() {
        manager.setCapturingForTest(true)
        val bridge = newBridge()
        val console = FakeConsole()
        // 主流时序：点捕捉时 epActive 尚未置位（SqlLogManager.startCapture 先 capturing 再 scanAll）→ 桥 filter 照挂
        bridge.attachFilter(console)
        assertTrue("主流时序前提：桥 filter 已挂载", bridge.attachedConsoles.containsKey(console))
        val ep = newEpFilter()
        val bf = bridgeFilterOf(bridge, console)

        // 行到达：平台 filter 链中 EP 先于桥执行——同一行先喂 EP 后喂桥
        sqlLines(1).forEach { line ->
            ep.applyFilter(line, line.length + 1)
            bf.applyFilter(line, line.length + 1)
        }

        assertTrue("EP 首行标志已置位", SqlCaptureGate.epActive)
        assertEquals(
            "去重已关——条目仍单路（短路本身防重，非缓冲去重兜底）",
            1, manager.entries.snapshot().size,
        )
        assertEquals("两行各短路一次（计数探针）", 2L, bridge.epActiveShortCircuitCount.get())
        assertEquals("条目内容来自 EP 通道", "SELECT * FROM t1 WHERE id = ?", manager.entries.snapshot().single().rawSql)
    }

    // ---------- ②armed-waiting 时序：捕捉先开，EP 先挂首行置位，桥后挂即跳过 ----------

    fun testarmedwaiting时序_EP先置位_桥后挂即跳过_条目单路() {
        manager.setCapturingForTest(true)
        val ep = newEpFilter()
        // armed-waiting：控制台后出现——EP 先处理首行（置位 epActive，会话内粘滞）
        ep.applyFilter("==>  Preparing: SELECT * FROM t2 WHERE id = ?", 42)
        assertTrue("EP 首行标志已置位", SqlCaptureGate.epActive)

        // 桥后挂：attachFilter 挂载面守卫（既有 :270-274）直接跳过——不再双挂载
        val bridge = newBridge()
        val console = FakeConsole()
        bridge.attachFilter(console)
        assertFalse("EP 已生效时桥不再挂载 filter", bridge.attachedConsoles.containsKey(console))

        // 后续行 EP 单路产出
        ep.applyFilter("==> Parameters: 2(Long)", 26)
        assertEquals("条目单路（仅 EP 产出）", 1, manager.entries.snapshot().size)
        assertEquals("桥未挂载 → 零短路触发", 0L, bridge.epActiveShortCircuitCount.get())
    }

    // ---------- ③行为语义零变化：双路时序与单路时序最终条目集逐条一致 ----------

    fun test语义零变化_双路时序与单路时序最终条目集逐条一致() {
        manager.setCapturingForTest(true)
        val lines = sqlLines(3) + sqlLines(4)

        // 单路：只 EP 喂行
        val single = run {
            val ep = newEpFilter()
            lines.forEach { ep.applyFilter(it, it.length + 1) }
            manager.entries.snapshot().also { manager.entries.clear() }
        }

        // 双路：桥先挂（epActive 复位），EP+桥都喂同一行（主流时序完整复刻）
        SqlCaptureGate.epActive = false
        val bridge = newBridge()
        val console = FakeConsole()
        bridge.attachFilter(console)
        val ep = newEpFilter()
        val bf = bridgeFilterOf(bridge, console)
        lines.forEach { line ->
            ep.applyFilter(line, line.length + 1)
            bf.applyFilter(line, line.length + 1)
        }
        val dual = manager.entries.snapshot()

        assertEquals("两条 SQL 全部捕获", 2, dual.size)
        assertEquals(
            "双路时序与单路时序最终条目集逐条一致（不丢不重）",
            stableProjectionOf(single), stableProjectionOf(dual),
        )
    }

    // ---------- ④不漏捕方向：epActive 未置位时桥照常解析 ----------

    fun testepActive未置位时桥照常解析_短路不误伤单路桥时序() {
        manager.setCapturingForTest(true)
        val bridge = newBridge()
        val console = FakeConsole()
        bridge.attachFilter(console)
        val bf = bridgeFilterOf(bridge, console)
        // EP 未参与（如 EP 装配失败的平台形态）→ 桥是唯一通道，必须照常解析
        sqlLines(5).forEach { bf.applyFilter(it, it.length + 1) }
        assertFalse(SqlCaptureGate.epActive)
        assertEquals("桥单路时条目照常产出（短路只作用于 EP 已置位后）", 1, manager.entries.snapshot().size)
        assertEquals("零短路触发", 0L, bridge.epActiveShortCircuitCount.get())
    }

    // ---------- ⑤EP timePrefix 缓存契约（对齐桥侧 timePrefixCache 形态） ----------

    fun testEP_timePrefix缓存_惰性补种与设置变更刷新() {
        val bridge = manager.consoleBridge
        val expected = PluginSettings.getInstance().state.logTimePrefix.takeIf { it.isNotBlank() }
        // 未播种（EP 首行可早于桥挂载到达）→ 惰性读一次设置
        assertEquals("惰性补种值与设置一致", expected, bridge.currentTimePrefix())
        assertEquals(expected, bridge.timePrefixCache)
        // 已播种 → 不再读设置（返回缓存值）
        assertEquals(expected, bridge.currentTimePrefix())

        // 设置变更 → refreshConsoleContexts（manager 设置变更订阅调用的同一刷新点）→ 缓存失效更新
        val state = PluginSettings.getInstance().state
        val old = state.logTimePrefix
        try {
            state.logTimePrefix = "CostMs:"
            bridge.refreshConsoleContexts()
            assertEquals("设置变更后缓存刷新（configProvider 快照刷新时失效）", "CostMs:", bridge.currentTimePrefix())
        } finally {
            state.logTimePrefix = old
            bridge.refreshConsoleContexts()
        }
    }

    fun testEP_timePrefix为blank时归null_与原逐行读取值语义一致() {
        val bridge = manager.consoleBridge
        val state = PluginSettings.getInstance().state
        val old = state.logTimePrefix
        try {
            state.logTimePrefix = "  "
            bridge.refreshConsoleContexts()
            assertEquals(
                "blank 归 null（与 PluginSettings...takeIf { isNotBlank() } 同口径）",
                null, bridge.currentTimePrefix(),
            )
        } finally {
            state.logTimePrefix = old
            bridge.refreshConsoleContexts()
        }
    }
}
