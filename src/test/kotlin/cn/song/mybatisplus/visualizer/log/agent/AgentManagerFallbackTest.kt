package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import cn.song.mybatisplus.visualizer.log.SqlLogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

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
        super.tearDown()
    }

    fun test增强未开启_attach入口与进程回调均为空操作() {
        val fakeHandler = FakeProcessHandler(nativePid = 424242L)
        manager.onProcessAppeared(fakeHandler) // 守卫分支：不 attach 不抛错
        assertFalse("增强关闭时闸门不得闭合", SqlCaptureGate.paused.get())
        assertFalse("增强关闭时不在线", manager.online)
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
