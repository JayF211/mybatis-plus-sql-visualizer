package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * M56 T-M56-01 门禁④：attach 失败 → 回落桥零劣化（闸门不闭合、桥继续捕获）；
 * 幂等表与惰性挂载入口的守卫分支（增强未开启/未确认时空操作）。
 */
class AgentManagerFallbackTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        PluginSettings.getInstance().state.enhancedCaptureConfirmed = false
    }

    override fun tearDown() {
        manager.shutdownAll()
        PluginSettings.getInstance().state.enhancedCaptureEnabled = false
        PluginSettings.getInstance().state.enhancedCaptureConfirmed = false
        PluginSettings.getInstance().state.enhancedCaptureConsentCooldownUntil = 0
        super.tearDown()
    }

    fun test增强未开启_attach入口与进程回调均为空操作() {
        val fakeHandler = FakeProcessHandler(nativePid = 424242L)
        manager.onProcessAppeared(fakeHandler) // 守卫分支：不 attach 不抛错
        assertFalse("增强关闭时闸门不得闭合", SqlCaptureGate.paused.get())
        assertFalse("增强关闭时不在线", manager.online)
    }

    // ---------- 默认值与首次挂载确认（2026-09-16 定案：增强捕获默认开、脱敏默认关） ----------

    fun test默认值_增强捕获开_脱敏关_确认未达成() {
        val fresh = PluginSettings.State()
        assertTrue("增强捕获默认应开启", fresh.enhancedCaptureEnabled)
        assertFalse("增强捕获脱敏默认应关闭", fresh.enhancedCaptureDesensitize)
        assertFalse(
            "确认默认未达成（默认开启态下首次运行项目弹一次注入确认，知情同意不放宽）",
            fresh.enhancedCaptureConfirmed,
        )
    }

    // ---------- T-PS4-01：确认流触发面收窄 + 拒绝冷却（性能第六轮「高」级发现） ----------

    fun test非主配置启动_未确认时静默跳过确认弹窗() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureConsentCooldownUntil = 0
        var asked = false
        val saved = manager.consentProvider
        manager.consentProvider = { asked = true; false }
        try {
            // HTTP 请求/测试/profiler 等非模块型进程（mainLaunch=false）：不弹确认
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 424242L), mainLaunch = false)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse("非主配置启动不得弹确认弹窗", asked)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test拒绝后进入冷卄_冷卄期内主配置启动零弹窗() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        // 拒绝后的冷卄截止（未来 1 小时）
        state.enhancedCaptureConsentCooldownUntil = System.currentTimeMillis() + 3_600_000L
        var asked = false
        val saved = manager.consentProvider
        manager.consentProvider = { asked = true; false }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 424242L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse("冷卄期内不得再弹确认", asked)
            assertFalse("冷卄不改变确认门槛（confirmed 仍未置位）", state.enhancedCaptureConfirmed)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test冷卄过期后_主配置启动再次弹确认() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        // 冷卄已过去（截止时刻在过去）
        state.enhancedCaptureConsentCooldownUntil = System.currentTimeMillis() - 1_000L
        var asked = false
        val saved = manager.consentProvider
        manager.consentProvider = { asked = true; false }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 424242L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue("冷卄过期后主配置启动应再次询问", asked)
            // 再拒绝 → 冷卄重新起算（拒绝即进冷卄）
            assertTrue("拒绝后冷卄截止应设到未来", state.enhancedCaptureConsentCooldownUntil > System.currentTimeMillis())
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test同意后清冷却_confirmed持久化() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        // 冷卄已过期（弹窗可达）；同意后应清零
        state.enhancedCaptureConsentCooldownUntil = System.currentTimeMillis() - 1_000L
        val saved = manager.consentProvider
        manager.consentProvider = { true }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_999L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue("同意后确认持久化", state.enhancedCaptureConfirmed)
            assertEquals("同意即清冷卄", 0L, state.enhancedCaptureConsentCooldownUntil)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test已开启未确认_首次挂载请求确认_拒绝则不注入() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        var asked = false
        val saved = manager.consentProvider
        manager.consentProvider = { asked = true; false }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 424242L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() // 冲洗 EDT：确认请求在此执行
            assertTrue("未确认时应征求注入确认", asked)
            assertFalse("拒绝后不得置 confirmed", state.enhancedCaptureConfirmed)
            assertFalse("拒绝后闸门不得闭合", SqlCaptureGate.paused.get())
            assertFalse("拒绝后不在线", manager.online)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test已开启未确认_同意后置确认并继续挂载() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        val saved = manager.consentProvider
        manager.consentProvider = { true }
        try {
            // pid 不可达 → attach 必失败，但「同意后继续挂载」路径必须走到（confirmed 置位即证据）
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_999L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue("同意后应置 confirmed", state.enhancedCaptureConfirmed)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test已确认_不再弹确认直接挂载() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
        var asked = false
        val saved = manager.consentProvider
        manager.consentProvider = { asked = true; true }
        try {
            manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_999_999L))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse("已确认不应重复弹确认框", asked)
        } finally {
            manager.consentProvider = saved
        }
    }

    fun test开启但attach不可达_回落桥零劣化() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
        // 不存在的 pid（本机不可能有 4 个 9 的 pid 场景按失败处理）→ 回落桥
        val attached = manager.attachToPid(999_999_999L)
        assertFalse("不可达 pid 应 attach 失败", attached)
        assertFalse("失败后闸门不得闭合（桥继续捕获）", SqlCaptureGate.paused.get())
        assertFalse(manager.online)
    }

    fun testpid解析_getNativePid能力探测与回退() {
        assertEquals(424242L, manager.resolvePid(FakeProcessHandler(nativePid = 424242L)))
        assertEquals(777L, manager.resolvePid(FakeProcessHandler(processPid = 777L)))
        assertEquals(null, manager.resolvePid(FakeProcessHandler()))
        assertEquals(null, manager.resolvePid(null))
    }

    fun test会话与幂等表_关闭开关回落() {
        // 无会话时 shutdownAll 安全（幂等）
        manager.shutdownAll()
        assertFalse(SqlCaptureGate.paused.get())
        assertFalse(manager.online)
    }

    /** 反射缝假 ProcessHandler：可控暴露 getNativePid / getProcess().pid()。 */
    private class FakeProcessHandler(
        private val nativePid: Long? = null,
        private val processPid: Long? = null,
    ) {
        fun getNativePid(): Long? = nativePid

        fun getProcess(): Process? = processPid?.let { pid ->
            object : Process() {
                override fun pid(): Long = pid
                override fun getOutputStream() = throw UnsupportedOperationException()
                override fun getInputStream() = throw UnsupportedOperationException()
                override fun getErrorStream() = throw UnsupportedOperationException()
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
            }
        }
    }
}
