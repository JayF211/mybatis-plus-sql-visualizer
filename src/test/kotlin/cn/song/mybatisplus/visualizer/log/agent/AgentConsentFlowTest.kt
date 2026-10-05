package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.EnhancedCaptureConsent
import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-PS4-01 确认流语义修正守护测试：
 * - 拒绝 → 持久化「拒绝时刻」，24h（可注入短窗）冷却期内 N 个进程启动零弹窗；
 * - 冷却过期后再弹一次；
 * - 同意 → confirmed 持久化，后续主配置/非主配置进程永不弹；
 * - 「关闭增强捕获并不再询问」（第三动作）→ enabled=false 持久化，任何进程不再弹；
 * - 未确认态非主配置进程静默跳过（不弹窗、不入 attach 队列）；
 * - 设置页「关闭 → 再开启」上升沿重置拒绝冷却。
 *
 * 冷却只作用于弹窗频率、不改变同意门槛（保守口径）——由「冷却过期后再弹一次」用例锁定。
 */
class AgentConsentFlowTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private lateinit var savedProvider: java.util.function.Supplier<EnhancedCaptureConsent>
    private var askedCount = 0

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        // 防御性还原默认时间源（实例可能跨测试类复用，防前类注入的 clockMs 闭包污染冷却判定）
        manager.clockMs = { System.currentTimeMillis() }
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.rejectCooldownMs = AgentManager.DEFAULT_REJECT_COOLDOWN_MS
        // R2 去重窗：本类用例自带「冷却过期后再弹」等瞬时连发场景，默认关闭去重（专类专测）
        manager.consentAskGuardMs = 0
        manager.lastConsentAskAtMs.set(0)
        savedProvider = manager.consentProvider
        askedCount = 0
        manager.consentProvider = { askedCount++; EnhancedCaptureConsent.REJECTED }
    }

    override fun tearDown() {
        manager.consentProvider = savedProvider
        manager.shutdownAll()
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        super.tearDown()
    }

    private fun flushEdt() {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    }

    fun test拒绝后冷却期内_N个进程启动零弹窗() {
        // 第 1 个主配置进程：弹一次，拒绝 → 拒绝时刻持久化
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_991L))
        flushEdt()
        assertEquals("首次未确认应弹一次", 1, askedCount)
        assertTrue(
            "拒绝时刻应持久化",
            PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs > 0,
        )
        // 冷却期内再起 5 个主配置进程：零弹窗、不入队
        repeat(5) { i ->
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_992L + i))
        }
        flushEdt()
        assertEquals("冷却期内不得重复弹窗", 1, askedCount)
        assertTrue("冷却期内不得入 attach 队列", manager.pendingPids.isEmpty())
    }

    fun test冷却过期后再弹一次() {
        manager.rejectCooldownMs = 1_000L // 注入短冷却窗
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_991L))
        flushEdt()
        assertEquals("首次未确认应弹一次", 1, askedCount)
        // 拒绝时刻回拨到冷却窗之外（等价冷却过期）
        PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs =
            System.currentTimeMillis() - 2_000L
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_993L))
        flushEdt()
        assertEquals("冷却过期后应再弹一次", 2, askedCount)
    }

    fun test同意后confirmed持久化_后续永不弹() {
        val saved = manager.consentProvider
        manager.consentProvider = { askedCount++; EnhancedCaptureConsent.ACCEPTED }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_999L))
            flushEdt()
            assertEquals("首次应弹一次", 1, askedCount)
            assertTrue(
                "同意后 confirmed 应持久化",
                PluginSettings.getInstance().state.enhancedCaptureConfirmed,
            )
            // 后续主配置 + 非主配置进程：永不弹
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_998L))
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_997L), false)
            flushEdt()
            assertEquals("已确认后不得再弹", 1, askedCount)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test关闭增强捕获_总开关持久化false_任何进程不再弹() {
        val state = PluginSettings.getInstance().state
        val saved = manager.consentProvider
        manager.consentProvider = { askedCount++; EnhancedCaptureConsent.DISABLE_CAPTURE }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_991L))
            flushEdt()
            assertEquals("首次应弹一次", 1, askedCount)
            assertFalse(
                "「关闭增强捕获」应持久化 enabled=false",
                state.enhancedCaptureEnabled,
            )
            assertFalse("关闭增强不得置 confirmed", state.enhancedCaptureConfirmed)
            // 此后任何进程启动（主配置/非主配置）都不弹窗、不 attach
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_992L))
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_992L), false)
            flushEdt()
            assertEquals("总开关关闭后不得再弹", 1, askedCount)
            assertTrue("总开关关闭后不得入队", manager.pendingPids.isEmpty())
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test未确认态_非主配置进程静默跳过() {
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_996L), false)
        flushEdt()
        assertEquals("未确认态非主配置进程不得弹窗", 0, askedCount)
        assertTrue("未确认态非主配置进程不得入队", manager.pendingPids.isEmpty())
        assertFalse(
            "静默跳过不得写拒绝时刻",
            PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs > 0,
        )
    }

    fun test设置页关闭再开启_拒绝冷却重置() {
        // 模拟已有拒绝冷却记忆
        PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs = System.currentTimeMillis()
        assertTrue(manager.isInRejectCooldown())
        // 关闭 → 设置变更（监听下降沿：不重置）
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        assertTrue("仅关闭不应重置冷却", manager.isInRejectCooldown())
        // 再开启 → 上升沿：重置冷却（保留用户改主意的入口）
        PluginSettings.getInstance().state.enhancedCaptureEnabled = true
        project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
        assertEquals(
            "「关闭→再开启」应重置拒绝时刻",
            0L,
            PluginSettings.getInstance().state.enhancedCaptureRejectedAtMs,
        )
        assertFalse(manager.isInRejectCooldown())
    }

    /** 反射缝假 ProcessHandler：可控暴露 getNativePid（同 AgentManagerFallbackTest 惯例）。 */
    private class FakeProcessHandler(private val nativePid: Long?) {
        fun getNativePid(): Long? = nativePid
    }
}
