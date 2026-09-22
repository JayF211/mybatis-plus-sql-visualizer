package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlCaptureGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * T-ReachAudit T-RA-01：心跳窗口守护测试。
 *
 * 缺陷现场（审计 F1）：`AgentProtocol.ONLINE_WINDOW_MS` 声明后全仓零消费、`AgentSession.lastSeen` 有写无读，
 * 在线态只看 socket `alive`（读循环退出才置 false）——agent 侧停摆（织入未生效 / 事件解析持续失败 /
 * 应用卡死）但 TCP 连接仍在时 `online` 恒 true → `SqlCaptureGate.paused` 恒 true → 控制台桥保持封口、
 * agent 又没产出 = **两条通道同时零产出且无超时恢复**，状态行还显示「已挂载 pid=…」。
 *
 * 本组用例的判据即修复目标：窗口过期后 ①不再算「已挂载」②被巡检摘除 ③桥回落（paused=false）
 * ④状态行改口：心跳停摆 / 首条心跳未到 / 连接断开三态分开叙述（T-AgentHeartbeat T-05 起，
 * 前两态由 `AgentSession.livenessFrameSeen` 区分——只有「曾收到 HELLO 之外的存活帧」才算停摆）。
 *
 * 会话构造照 `AgentAttachChainTest` 惯例：loopback 真 socket 对（`AgentSession` internal 同包可构造），
 * 时间源两侧同源注入（会话记 `lastSeen` 与管理器算过期共用 [AgentManager.clockMs]），故无需真等 30s。
 */
class AgentHeartbeatWindowTest : BasePlatformTestCase() {

    private lateinit var manager: AgentManager
    private var now = 5_000_000L
    private val sockets = mutableListOf<java.io.Closeable>()

    override fun setUp() {
        super.setUp()
        manager = AgentManager.getInstance(project)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = true
        state.enhancedCaptureConfirmed = true
        state.enhancedCaptureRejectedAtMs = 0
        now = 5_000_000L
        manager.clockMs = { now }
        manager.heartbeatWindowMs = 30_000L
        SqlCaptureGate.paused.set(false)
    }

    override fun tearDown() {
        // 实例跨测试类复用（AgentAttachChainTest 同款教训）：注入的时钟/窗口必须还原，否则污染后续类
        manager.clockMs = { System.currentTimeMillis() }
        manager.heartbeatWindowMs = AgentProtocol.ONLINE_WINDOW_MS
        manager.shutdownAll()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        SqlCaptureGate.paused.set(false)
        val state = PluginSettings.getInstance().state
        state.enhancedCaptureEnabled = false
        state.enhancedCaptureConfirmed = false
        state.enhancedCaptureRejectedAtMs = 0
        super.tearDown()
    }

    /**
     * 置「曾挂载」标记：真实路径由 [AgentManager.attachToPid] 成功时置位，本测试直连会话不经过 attach，
     * 故用反射置位（不为测试新增生产 API；项目既有测试亦用反射直改内部状态）。
     */
    private fun markEverAttached() {
        val f = AgentManager::class.java.getDeclaredField("everAttached")
        f.isAccessible = true
        (f.get(manager) as java.util.concurrent.atomic.AtomicBoolean).set(true)
    }

    /** agent 侧 socket（按 pid 记，供 [sendFrame] 补发帧用）。 */
    private val clientSockets = mutableMapOf<Long, Socket>()

    /** loopback 真会话（不发任何帧 → lastSeen 停在构造时刻，且未收到过存活帧）。 */
    private fun newSession(pid: Long): AgentSession {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        sockets += client
        sockets += accepted
        sockets += server
        clientSockets[pid] = client
        return AgentSession(project, pid, accepted, {}, { now }) // k2j：AgentSession 为 Java 类，禁具名实参
    }

    /** agent → IDE 方向补发一帧（协议帧：4 字节 len + 1 字节 cmd + UTF-8 body）。 */
    private fun sendFrame(pid: Long, cmd: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = java.io.DataOutputStream(clientSockets.getValue(pid).getOutputStream())
        out.writeInt(bytes.size)
        out.write(cmd)
        out.write(bytes)
        out.flush()
    }

    /**
     * 把会话置成「已收到过 HELLO 之外的存活帧」形态（T-AgentHeartbeat T-05 的「心跳停摆」前提）。
     * 发帧后轮询 [AgentSession.livenessFrameSeen] 等读线程真的处理完——不等就会与读线程赛跑出假绿。
     */
    private fun markLivenessFrameSeen(session: AgentSession) {
        sendFrame(session.pid, AgentProtocol.CMD_HEARTBEAT, "")
        val deadline = System.currentTimeMillis() + 5_000L
        while (!session.livenessFrameSeen && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertTrue(
            "前置：补发的 HEARTBEAT 应被读线程处理（未处理则本用例落在错误的态上）",
            session.livenessFrameSeen,
        )
    }

    // ---------- ① 窗口内：健康，不得误摘 ----------

    fun test窗口内心跳新鲜时视为健康不摘除() {
        val session = newSession(7001L)
        manager.sessions.add(session)
        manager.attachedPids[7001L] = 1L

        now += 29_999L // 距 lastSeen 29.999s < 30s
        manager.sweepOffline()

        assertTrue("窗口内会话不得被摘除（防误伤）", manager.sessions.contains(session))
        assertTrue("窗口内仍应算已挂载", manager.statusSummary()!!.contains("pid=7001"))
    }

    // ---------- ② 窗口过期：不再算已挂载（核心回归——半死会话不得显示正常） ----------

    fun test窗口过期后不再算已挂载() {
        val session = newSession(7002L)
        manager.sessions.add(session)

        now += 30_001L // 距 lastSeen 30.001s > 30s
        val text = manager.statusSummary()

        assertFalse(
            "心跳过期的会话不得再显示「已挂载」（缺陷现场：状态行报正常、实际零产出）：$text",
            text!!.contains("已挂载"),
        )
        assertFalse("在线态应为 false（桥需回落）", manager.online)
    }

    // ---------- ③ 巡检摘除 + 桥回落 ----------

    fun test窗口过期后巡检摘除会话并回落控制台桥() {
        val session = newSession(7003L)
        manager.sessions.add(session)
        manager.attachedPids[7003L] = 1L
        // 前置：模拟「agent 已接管」态（真机由 attach 成功经 updateOnline 置位；本测试直连会话，
        // 故手动置位——sweepOffline 只在「有死会话」时才重算在线态，靠它建立前置会假前置）
        SqlCaptureGate.paused.set(true)

        now += 30_001L // 会话停摆：socket 仍开，心跳不再
        assertTrue("前置：socket 未断（模拟应用卡死而非掉线）", session.alive)
        manager.sweepOffline()

        assertFalse("过期会话应被摘除", manager.sessions.contains(session))
        assertFalse("摘除后在线态应转 false", manager.online)
        assertFalse("桥必须回落（否则两条通道同时零产出且永不恢复）", SqlCaptureGate.paused.get())
        assertFalse("摘除应顺带清幂等表（既有语义不回归）", manager.attachedPids.containsKey(7003L))
    }

    // ---------- ④ 状态行改口：心跳停摆 ≠ 首条心跳未到 ≠ 连接断开 ----------

    fun test心跳过期回落文案与真断连区分() {
        markEverAttached()
        val session = newSession(7004L)
        markLivenessFrameSeen(session) // T-AgentHeartbeat T-05：曾收到存活帧 → 才是「心跳停摆」
        manager.sessions.add(session)
        manager.sweepOffline() // 窗口内：确认健康路径不置位
        now += 30_001L
        manager.sweepOffline() // 过期摘除 → 记为心跳类回落

        val text = manager.statusSummary()!!
        assertTrue("应提示未收到应用侧心跳（可核实的表述）：$text", text.contains("未收到应用侧心跳"))
        assertTrue("应带出在线窗口秒数 30：$text", text.contains("30"))
        assertFalse("不得再断言进程卡死（T-AgentHeartbeat T-05 去因果断言）：$text", text.contains("卡死"))
        assertFalse("不应与真断连文案混用：$text", text.contains("连接中断"))
    }

    fun test真断连仍走连接中断文案() {
        markEverAttached()
        val session = newSession(7005L)
        markLivenessFrameSeen(session) // 曾有心跳也改不了结论：socket 断了就是断连
        manager.sessions.add(session)
        session.close() // alive=false（读循环退出/close），lastSeen 仍新鲜 → 属连接断开而非心跳过期
        manager.sweepOffline()

        assertFalse("断连会话应被摘除", manager.sessions.contains(session))
        val text = manager.statusSummary()!!
        assertTrue("应走「连接中断」既有文案：$text", text.contains("连接中断"))
        assertFalse("不得误报心跳停摆：$text", text.contains("未收到应用侧心跳"))
        assertFalse("不得降级成连接早期叙述：$text", text.contains("连接已建立"))
    }

    // ---------- ④b T-AgentHeartbeat T-05：首条心跳未到独立成态（现场错误叙事的形态） ----------

    fun test首条心跳未到摘除走等待中文案而非心跳停摆() {
        markEverAttached()
        val session = newSession(7009L) // 未收到任何存活帧（现场：首条心跳 60.6s 才发，窗口 30s）
        manager.sessions.add(session)
        now += 30_001L
        manager.sweepOffline()

        assertFalse("窗口语义不变：过期仍应摘除", manager.sessions.contains(session))
        val text = manager.statusSummary()!!
        assertTrue("应叙述「连接已建立」这一可核实事实：$text", text.contains("连接已建立"))
        assertTrue("应说明尚未收到心跳：$text", text.contains("尚未收到应用侧心跳"))
        assertFalse("连接早期不得说成「已回落」（现场错误叙事的形态）：$text", text.contains("已回落控制台捕获"))
        assertFalse("不得误报心跳中断：$text", text.contains("心跳中断"))
        assertFalse("不得误报心跳停摆：$text", text.contains("心跳停摆"))
        assertFalse("不得断言进程卡死：$text", text.contains("卡死"))
        assertFalse("不应与真断连文案混用：$text", text.contains("连接中断"))
    }

    fun test仅收到HELLO仍算首条心跳未到() {
        markEverAttached()
        val session = newSession(7010L)
        // 前置可观测：读循环对所有帧都刷新 lastSeen，故「HELLO 被处理」可由 lastSeen 推进证明
        now += 1_000L
        val before = session.lastSeen
        sendFrame(7010L, AgentProtocol.CMD_HELLO, """{"pid":7010}""")
        val deadline = System.currentTimeMillis() + 5_000L
        while (session.lastSeen == before && System.currentTimeMillis() < deadline) Thread.sleep(10L)
        assertEquals(
            "前置：HELLO 应被读线程处理（否则本用例对「HELLO 是否算存活帧」零判别力）",
            now,
            session.lastSeen,
        )
        assertFalse("前置：HELLO 不得置存活帧标记", session.livenessFrameSeen)

        manager.sessions.add(session)
        now += 30_001L
        manager.sweepOffline()

        val text = manager.statusSummary()!!
        assertTrue("HELLO 不算存活帧 → 仍属连接早期：$text", text.contains("连接已建立"))
        assertFalse("HELLO 不得被当成「曾有心跳」：$text", text.contains("已回落控制台捕获"))
    }

    fun test收到过心跳后停摆与首条心跳未到两态文案不同() {
        markEverAttached()
        val pending = newSession(7011L)
        manager.sessions.add(pending)
        now += 30_001L
        manager.sweepOffline()
        val pendingText = manager.statusSummary()!!

        val stale = newSession(7012L)
        markLivenessFrameSeen(stale)
        manager.sessions.add(stale)
        now += 30_001L
        manager.sweepOffline()
        val staleText = manager.statusSummary()!!

        assertFalse("两态不得收敛成同一句话：pending=$pendingText stale=$staleText", pendingText == staleText)
    }

    // ---------- ⑤ 边界：恰在窗口上仍健康，过 1ms 即过期 ----------

    fun test窗口边界恰好等于时仍健康() {
        val session = newSession(7006L)
        manager.sessions.add(session)

        now += 30_000L // 恰好等于窗口
        assertTrue("恰在窗口上应仍健康（<= 判定）", manager.sessions.isNotEmpty())
        manager.sweepOffline()
        assertTrue("边界值不得被摘除", manager.sessions.contains(session))

        now += 1L // 再进 1ms
        manager.sweepOffline()
        assertFalse("越过窗口 1ms 即过期摘除", manager.sessions.contains(session))
    }

    // ---------- ⑥ 新一轮挂载清回落原因 ----------

    fun test新会话加入后状态行恢复已挂载且时钟同源() {
        markEverAttached()
        val stale = newSession(7007L)
        markLivenessFrameSeen(stale) // T-AgentHeartbeat T-05：曾收到存活帧 → 摘除记为「心跳停摆」
        manager.sessions.add(stale)
        now += 30_001L
        manager.sweepOffline()
        assertTrue("前置：已标记心跳类回落", manager.statusSummary()!!.contains("未收到应用侧心跳"))

        // 新一轮会话加入（真机路径还会经 attachToPid 清心跳标记；此处只需验证状态行回到「已挂载」）
        val fresh = newSession(7008L)
        manager.sessions.add(fresh)
        manager.sweepOffline()
        assertTrue("新会话应算已挂载", manager.statusSummary()!!.contains("pid=7008"))
        assertEquals("时钟推进后新会话 lastSeen 同源刷新", now, fresh.lastSeen)
    }
}
