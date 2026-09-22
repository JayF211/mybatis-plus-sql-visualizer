package cn.song.mybatisplus.visualizer.log.agent

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-PS5-04：AgentManager 常驻面解耦守护测试（loopback 真 socket，会话构造照
 * `AgentHeartbeatWindowTest` 惯例）。
 *
 * 锁定三件事：
 *  1. **服务实例化本身不排定巡检**（monitor 懒启：此前 init 即 schedule，默认开智能推断的项目
 *     「预览一次即驻留」一条 5s 守护线程）；
 *  2. 首条会话建立懒启 + 兜底清扫不误杀新会话，心跳过期摘除语义不变；
 *  3. 会话 URL 捕获 → 回写 [AgentUrlRegistry]；会话摘除 → 条目清除（推断回落数据源 URL）。
 *
 * 用独立 [AgentManager] 实例（公开构造器）而非 `getInstance`：平台测试项目跨测试类复用，
 * 共享服务上的 `monitorStarted` 会被其它类置位，断言将随执行顺序摇摆。
 */
class AgentManagerLazyMonitorTest : BasePlatformTestCase() {

    private var now = 5_000_000L
    private val sockets = mutableListOf<java.io.Closeable>()
    private val freshManagers = mutableListOf<AgentManager>()

    override fun setUp() {
        super.setUp()
        now = 5_000_000L
        AgentUrlRegistry.clearAllForTest()
    }

    override fun tearDown() {
        // Disposer.dispose 走完整子树（直接 dispose() 不会关闭 messageBus 连接——连接是
        // Disposer 子节点，残留订阅会在此后设置变更广播时对已停 executor 投任务）
        freshManagers.forEach { runCatching { Disposer.dispose(it) } }
        freshManagers.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        AgentUrlRegistry.clearAllForTest()
        super.tearDown()
    }

    private fun freshManager(): AgentManager = AgentManager(project).also {
        it.clockMs = { now }
        it.heartbeatWindowMs = 30_000L
        freshManagers += it
    }

    /** loopback 真会话（不发帧 → lastSeen 停在构造时刻）。 */
    private fun newSession(pid: Long, onUrlCaptured: () -> Unit = {}): AgentSession {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        sockets += client
        sockets += accepted
        sockets += server
        return AgentSession(
            project, pid, accepted,
            onStateChange = {},
            clockMs = { now },
            onUrlCaptured = onUrlCaptured,
        )
    }

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

    fun test服务实例化本身不排定巡检() {
        val manager = freshManager()
        assertFalse("构造（=服务实例化）不得排定 monitor 定时任务", manager.monitorStartedForTest())
        assertTrue("无会话 → 在线态 false", !manager.online)
    }

    fun test首条会话建立懒启巡检且兜底清扫不误杀() {
        val manager = freshManager()
        val session = newSession(7101L)
        manager.sessions.add(session)
        assertFalse("直连会话（不经 attach 链）不触发懒启", manager.monitorStartedForTest())

        manager.ensureMonitorStarted()
        assertTrue("首条会话建立后巡检已排定", manager.monitorStartedForTest())
        assertTrue("兜底清扫不得误杀刚建立的健康会话", manager.sessions.contains(session))

        now += 29_999L // 窗口内
        manager.sweepOffline()
        assertTrue("窗口内不摘除（既有语义回归）", manager.sessions.contains(session))

        now += 2L // 越窗
        manager.sweepOffline()
        assertFalse("越窗摘除（既有语义回归）", manager.sessions.contains(session))
    }

    fun test会话URL捕获回写持有者_摘除即清除() {
        val manager = freshManager()
        val url = "jdbc:mysql://localhost:3306/demo"
        val session = newSession(7102L, onUrlCaptured = { manager.syncInferenceUrl() })
        manager.sessions.add(session)
        assertNull("捕获前无条目（推断回落数据源 URL）", AgentUrlRegistry.lastConnectionUrl(project))

        val client = sockets.first() as Socket
        writeFrame(client, AgentProtocol.CMD_EVENT, """{"sql":"select 1","url":"$url"}""")
        awaitTrue("URL 捕获后回写持有者") { AgentUrlRegistry.lastConnectionUrl(project) == url }

        session.close()
        manager.sweepOffline()
        assertFalse("摘除后会话离表", manager.sessions.contains(session))
        assertNull("会话全摘 → 持有者条目清除", AgentUrlRegistry.lastConnectionUrl(project))
    }

    /**
     * T-PerfSweep6 T-PS6-08（性能报告发现 8）：`shutdownAll` 复位巡检。
     *
     * 缺陷面：monitor 懒启后无复位点——用过一次增强捕获的项目在关开关后仍保留一条 5s 空转任务
     * 至项目 dispose。修法是**取消已排定的定时任务**（持有 `ScheduledFuture` 并 `cancel`），
     * 而不是只翻标志（那会让下次 enable 在同一单线程执行器上再排一个 → 5s 巡检变双份）。
     *
     * 断言两件事：① `shutdownAll` 后巡检复位；② **复位后还能重新懒启**——这条正是
     * 「执行器不得被 shutdownNow」的判据（关掉执行器会让 `scheduleWithFixedDelay` 抛
     * `RejectedExecutionException`，等于把「开关关一次」变成「本会话巡检永久失效」）。
     */
    fun testshutdownAll复位巡检_且复位后仍可重新懒启() {
        val manager = freshManager()
        assertFalse("前置：未使用时巡检未启", manager.monitorStartedForTest())
        manager.ensureMonitorStarted()
        assertTrue("懒启后巡检在位", manager.monitorStartedForTest())

        manager.shutdownAll()
        assertFalse("shutdownAll 后巡检应复位（发现 8）", manager.monitorStartedForTest())

        // 再开启：复位必须只是「取消任务」，执行器本体仍在（否则这里会抛 RejectedExecutionException）
        manager.ensureMonitorStarted()
        assertTrue("复位后应能重新懒启（执行器不得被 shutdownNow）", manager.monitorStartedForTest())
        manager.shutdownAll()
        assertFalse("二次 shutdownAll 同样复位", manager.monitorStartedForTest())
    }
}
