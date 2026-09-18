package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-PS4-03 attach 链治理守护测试：
 * - pendingPids 去抖真实化——同 pid 在去抖窗（可注入）内重复启动不重复排队（时间源可注入，不依赖真实 3s）；
 *   窗口过期后重新排队；不同 pid 互不去抖；pid 解析失败不排队；
 * - sweepOffline 摘除死会话时顺带清 attachedPids（幂等表不随死进程残留）；
 * - shutdownAll 清表语义保留（attachedPids 全清 + pendingPids 一并清）。
 */
class AgentAttachChainTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        // 直接走 attach 链（确认流语义由 AgentConsentFlowTest / AgentManagerFallbackTest 覆盖）
        state.enhancedCaptureConfirmed = true
        state.enhancedCaptureRejectedAtMs = 0
    }

    override fun tearDown() {
        // 实例可能跨测试类复用（本组实测）：注入的时间源/窗口必须还原默认，
        // 否则陈旧闭包污染后续类的冷却/去抖判定
        manager.clockMs = { System.currentTimeMillis() }
        manager.attachDebounceMs = AgentManager.DEFAULT_ATTACH_DEBOUNCE_MS
        manager.rejectCooldownMs = AgentManager.DEFAULT_REJECT_COOLDOWN_MS
        manager.shutdownAll()
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        super.tearDown()
    }

    fun test同pid去抖窗内重复启动不重复排队() {
        var now = 1_000_000L
        manager.clockMs = { now }
        manager.attachDebounceMs = 3_000L
        val handler = FakeProcessHandler(nativePid = 999_888_777L)

        manager.onProcessAppeared(handler)
        assertEquals("首次启动应排队", 1_000_000L, manager.pendingPids[999_888_777L])

        now = 1_001_500L // 距上次排队 1.5s < 3s：去抖命中
        manager.onProcessAppeared(handler)
        assertEquals("去抖命中不得重复排队/刷新时刻", 1_000_000L, manager.pendingPids[999_888_777L])
        assertEquals("去抖命中不得产生第二条排队", 1, manager.pendingPids.size)

        now = 1_004_000L // 距上次排队 4s >= 3s：窗口过期
        manager.onProcessAppeared(handler)
        assertEquals("窗口过期应重新排队（任务完成后条目已摘除，此处为过期重排路径）", 1_004_000L, manager.pendingPids[999_888_777L])
    }

    fun test不同pid互不去抖() {
        var now = 2_000_000L
        manager.clockMs = { now }
        manager.attachDebounceMs = 3_000L
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_888_770L))
        now = 2_000_001L
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 999_888_771L))
        assertEquals(2_000_000L, manager.pendingPids[999_888_770L])
        assertEquals(2_000_001L, manager.pendingPids[999_888_771L])
    }

    fun testpid解析失败不排队() {
        var now = 3_000_000L
        manager.clockMs = { now }
        manager.onProcessAppeared(FakeProcessHandler(nativePid = null))
        assertTrue("pid 解析失败不得入队", manager.pendingPids.isEmpty())
        manager.onProcessAppeared(null)
        assertTrue(manager.pendingPids.isEmpty())
    }

    fun testsweepOffline摘死会话顺带清pid表() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        try {
            // AgentSession internal 同包可构造（同 AgentListenSyncTest 惯例）
            val session = AgentSession(project, pid = 5555L, socket = accepted, onStateChange = {})
            manager.sessions.add(session)
            manager.attachedPids[5555L] = 123L
            session.close() // 置 alive=false（死会话）
            manager.sweepOffline()
            assertTrue("死会话应被摘除", manager.sessions.isEmpty())
            assertFalse("摘除死会话应顺带清幂等表（T-PS4-03）", manager.attachedPids.containsKey(5555L))
        } finally {
            client.close()
            accepted.close()
            server.close()
        }
    }

    fun testshutdownAll清表语义保留() {
        manager.attachedPids[111L] = 1L
        manager.pendingPids[222L] = 2L
        manager.shutdownAll()
        assertTrue("attachedPids 全清语义保留", manager.attachedPids.isEmpty())
        assertTrue("pendingPids 应一并清空（T-PS4-03）", manager.pendingPids.isEmpty())
        assertFalse(manager.online)
    }

    /** 反射缝假 ProcessHandler：可控暴露 getNativePid（同既有测试惯例）。 */
    private class FakeProcessHandler(private val nativePid: Long?) {
        fun getNativePid(): Long? = nativePid
    }
}
