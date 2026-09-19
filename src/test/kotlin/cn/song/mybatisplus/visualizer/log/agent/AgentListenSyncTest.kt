package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-AgentListenSync：监听开关联动 agent 采集开关。
 *
 * 三边界守卫：
 * - isListenActive 跟随 SqlLogManager.capturing（B2：pushDesensitize 的 disabled 依据）
 * - AgentSession.sendConfig 帧体按 disabled 正确编码（CONFIG 帧协议，agent 侧极简 contains 解析依赖无空格格式）
 * - pushCaptureActive 无会话安全（空遍历不抛）
 */
class AgentListenSyncTest : BasePlatformTestCase() {

    private val NO_OP_RUNNABLE = Runnable {}

    /** T-TestIsolation：本类读写的是**项目服务级**共享 SqlLogManager（light 项目片内跨类复用）——
     *  存初值供 tearDown 复位，兼防「邻居留下的脏捕获态」被本类当作初值再传下去。 */
    private lateinit var serviceState: SqlLogManagerProjectState.Snapshot

    override fun setUp() {
        super.setUp()
        serviceState = SqlLogManagerProjectState.capture(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "setUp.capture")
        )
    }

    override fun tearDown() {
        SqlLogManagerProjectState.restore(
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "tearDown.restore"),
            serviceState,
        )
        super.tearDown()
    }

    fun testisListenActive_跟随监听状态() {
        val logManager =
            AlreadyDisposedDiagnostics.projectService(project, SqlLogManager::class.java, "test.isListenActive")
        val manager = AgentManager.getInstance(project)
        val before = logManager.capturing
        try {
            logManager.setCapturingForTest(false)
            assertFalse("监听关闭 → 不活跃（agent 应停采）", manager.isListenActive())
            logManager.setCapturingForTest(true)
            assertTrue("监听开启 → 活跃（agent 应采集）", manager.isListenActive())
        } finally {
            logManager.setCapturingForTest(before)
        }
    }

    fun testpushCaptureActive_无会话安全() {
        val manager = AgentManager.getInstance(project)
        manager.pushCaptureActive(true) // sessions 为空：空遍历不抛
        manager.pushCaptureActive(false)
    }

    fun testsendConfig_disabled帧体正确编码() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        try {
            // AgentSession 同包可构造；init 读线程阻塞在 accepted 输入流（无妨）
            val session = AgentSession(project, 4242L, accepted, NO_OP_RUNNABLE)
            val input = DataInputStream(client.getInputStream())

            session.sendConfig(true, false)
            val len1 = input.readInt()
            val cmd1 = input.readUnsignedByte()
            val body1 = String(ByteArray(len1).also { input.readFully(it) }, Charsets.UTF_8)
            assertEquals(AgentProtocol.CMD_CONFIG, cmd1)
            assertTrue("disabled=true 应入帧体: $body1", body1.contains("\"disabled\":true"))
            assertEquals(AgentProtocol.configBody(true, false), body1)

            session.sendConfig(false, true)
            val len2 = input.readInt()
            input.readUnsignedByte()
            val body2 = String(ByteArray(len2).also { input.readFully(it) }, Charsets.UTF_8)
            assertTrue("disabled=false 应入帧体: $body2", body2.contains("\"disabled\":false"))
        } finally {
            client.close()
            accepted.close()
            server.close()
        }
    }
}
