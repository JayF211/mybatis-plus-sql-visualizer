package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-M56Phase2-Premain：端口前置协商、回连建会话、幂等与 attach 跳过（T-M56P2-05）。
 *
 * 覆盖 headless 可验的部分——真实 `-javaagent` 注入与 premain 阶段装载必须 runIde 真机
 * （见计划 §五 走查清单），本类只钉住 IDE 侧自己的行为：闸门、端口、回连、去重、跳过。
 */
class AgentManagerPremainTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        manager.clockMs = { System.currentTimeMillis() }
    }

    override fun tearDown() {
        try {
            manager.shutdownAll()
            val state = PluginSettings.getInstance().state
            state.enhancedCaptureEnabled = false
            state.enhancedCaptureConfirmed = false
            state.enhancedCaptureRejectedAtMs = 0
            manager.clockMs = { System.currentTimeMillis() }
        } finally {
            super.tearDown()
        }
    }

    // ---------- 闸门 ----------

    fun test未开启增强捕获_不注入不协商端口() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = true
        assertNull("未开启时不得注入", manager.preparePremainVmOption())
        assertTrue("未开启时不得协商端口", manager.premainChannels.isEmpty())
        assertFalse(SqlCaptureGate.paused.get())
    }

    fun test已开启未确认_不注入不协商端口() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = false
        assertNull("未确认时不得注入（首次知情同意仍走既有 attach 链）", manager.preparePremainVmOption())
        assertTrue("未确认时不得协商端口", manager.premainChannels.isEmpty())
        assertFalse(SqlCaptureGate.paused.get())
    }

    // ---------- 注入产物 ----------

    fun test已确认_注入_端口协商与jar落盘同在位() {
        enableAndConfirm()
        val option = manager.preparePremainVmOption()
        assertNotNull("已确认应产出 -javaagent VM 选项", option)
        assertTrue(option!!.startsWith("-javaagent:"))
        val port = portOf(option)
        assertTrue("端口应为可用 loopback 端口", port > 0)
        assertEquals(1, manager.premainChannels.size)
        assertEquals(port, manager.premainChannels[0].port)
        assertTrue("通道在场时应压制 attach", manager.hasPendingPremainChannel())

        // jar 必须在进程启动前就已落盘且非空（-javaagent 由目标 JVM 启动时读；读不到会让 JVM 终止）
        val jarPath = option.removePrefix("-javaagent:").substringBefore("=")
        val jar = File(jarPath)
        assertTrue("premain agent jar 未落盘：$jarPath", jar.isFile)
        assertTrue("premain agent jar 为空：$jarPath", jar.length() > 0L)
        // 落盘形态即「不被 deleteOnExit 提前清掉」的实现口径：插件临时目录 + 内容定名（非随机临时名）
        assertEquals("premain jar 应落在插件临时目录的 mpsv-agent 子目录", "mpsv-agent", jar.parentFile?.name)
        assertTrue(
            "premain jar 名应为内容定名的 mps-sql-agent-<crc>.jar，实际 ${jar.name}",
            Regex("""mps-sql-agent-\d+\.jar""").matches(jar.name),
        )
        // 重复准备复用同一路径（内容定名），不重复落盘
        val again = manager.preparePremainVmOption()!!
        assertEquals(jarPath, again.removePrefix("-javaagent:").substringBefore("="))
    }

    fun test挂起通道达上限_拒绝继续协商() {
        enableAndConfirm()
        val options = (1..AgentManager.MAX_PREMAIN_CHANNELS).map { manager.preparePremainVmOption() }
        assertTrue("上限内每次准备都应成功", options.all { it != null })
        assertNull("超上限应拒绝（防重复准备泄漏 ServerSocket）", manager.preparePremainVmOption())
        assertEquals(AgentManager.MAX_PREMAIN_CHANNELS, manager.premainChannels.size)
    }

    // ---------- 回连 ----------

    fun test回连_建会话_写幂等表_置在线态() {
        enableAndConfirm()
        val option = manager.preparePremainVmOption()!!
        val port = portOf(option)
        val pid = 424242L
        val socket = Socket("127.0.0.1", port)
        writeFrame(socket, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")

        awaitTrue("premain 会话未建立") { manager.sessions.isNotEmpty() }
        // sessions 先入表、updateOnline/publishStatusChanged 随后，故在线态与通道摘除各自等一次
        awaitTrue("回连后在线态未置位") { manager.online }
        awaitTrue("通道未摘除") { manager.premainChannels.isEmpty() }
        assertEquals(pid, manager.sessions[0].pid)
        assertTrue("回连后 pid 应写入幂等表（attach 链据此跳过）", manager.attachedPids.containsKey(pid))
        assertEquals("已挂载 pid=$pid", manager.statusSummary())
        assertFalse("通道认领后不再压制 attach", manager.hasPendingPremainChannel())
    }

    fun test同pid二次回连被丢弃_会话不叠加() {
        enableAndConfirm()
        val first = portOf(manager.preparePremainVmOption()!!)
        val second = portOf(manager.preparePremainVmOption()!!)
        val pid = 515151L
        val s1 = Socket("127.0.0.1", first)
        writeFrame(s1, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        awaitTrue("首条 premain 会话未建立") { manager.sessions.size == 1 }

        val s2 = Socket("127.0.0.1", second)
        s2.soTimeout = 3_000
        writeFrame(s2, AgentProtocol.CMD_HELLO, """{"pid":$pid}""")
        // 服务端应主动关闭重复连接（客户端读到 EOF）
        assertEquals("重复回连应被服务端关闭", -1, DataInputStream(s2.getInputStream()).read())
        assertEquals("同 pid 只允许一条会话", 1, manager.sessions.size)
        assertEquals(1, manager.attachedPids.size)
    }

    // ---------- attach 跳过与窗口失效 ----------

    fun testpremain通道在场_attach不排队() {
        enableAndConfirm()
        manager.preparePremainVmOption()
        assertTrue(manager.hasPendingPremainChannel())
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 777_001L))
        assertTrue("premain 在场时不得入 attach 排队表", manager.pendingPids.isEmpty())
        assertFalse("premain 在场时不得 attach", manager.attachedPids.containsKey(777_001L))
    }

    fun test跳过窗失效后_attach恢复排队() {
        enableAndConfirm()
        val base = 1_000_000L
        manager.clockMs = { base }
        manager.preparePremainVmOption()
        assertTrue(manager.hasPendingPremainChannel())
        // 推到跳过窗之外（仍在 accept 超时内）：通道还活着但不再压制 attach
        manager.clockMs = { base + AgentManager.PREMAIN_SKIP_ATTACH_WINDOW_MS + 1 }
        assertFalse("跳过窗外的通道不再压制 attach", manager.hasPendingPremainChannel())
        assertEquals("窗口外通道仍在等回连", 1, manager.premainChannels.size)
        manager.onProcessAppeared(FakeProcessHandler(nativePid = 777_002L))
        assertTrue("窗口外 attach 应恢复排队", manager.pendingPids.containsKey(777_002L))
    }

    // ---------- HELLO 首帧读取与交接 ----------

    fun test读HELLO取pid_非HELLO与畸形容错() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HELLO, """{"pid": 9876}""")
                    assertEquals(9876L, manager.readHelloPid(accepted))
                }
            }
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HEARTBEAT, "{}")
                    assertEquals("非 HELLO 帧按 pid 未知处理", 0L, manager.readHelloPid(accepted))
                }
            }
        }
    }

    fun test读HELLO后剩余帧不被吞掉() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted ->
                    writeFrame(client, AgentProtocol.CMD_HELLO, """{"pid":1234}""")
                    writeFrame(client, AgentProtocol.CMD_EVENT, """{"sql":"select 1"}""")
                    assertEquals(1234L, manager.readHelloPid(accepted))
                    // DataInputStream 无预读：随后交给 AgentSession 的读线程仍能取到 EVENT 帧
                    val input = DataInputStream(accepted.getInputStream())
                    val len = input.readInt()
                    assertEquals(AgentProtocol.CMD_EVENT, input.readUnsignedByte())
                    val body = ByteArray(len)
                    input.readFully(body)
                    assertEquals("""{"sql":"select 1"}""", String(body, Charsets.UTF_8))
                }
            }
        }
    }

    // ---------- 辅助 ----------

    private fun enableAndConfirm() {
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
    }

    private fun portOf(vmOption: String): Int =
        Regex("port=(\\d+)").find(vmOption)?.groupValues?.get(1)?.toInt()
            ?: error("VM 选项里没有 port：$vmOption")

    private fun writeFrame(socket: Socket, cmd: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(bytes.size)
        out.writeByte(cmd)
        out.write(bytes)
        out.flush()
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("等待超时：$what")
    }

    /** 反射缝假 ProcessHandler：只暴露 getNativePid（premain 测试只需「pid 可解析」这一条）。 */
    private class FakeProcessHandler(private val nativePid: Long? = null) {
        fun getNativePid(): Long? = nativePid
    }
}
