package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M56 协议两侧一致性锁定：agent 侧 Java 源码（EventBus/SenderLoop）的命令常量与帧格式
 * 须与 IDE 侧 [AgentProtocol] 逐值一致——两侧独立实现（agent 禁依赖 IDE 类），以源码
 * 文本扫描防漂移（改任一侧常量须同步另一侧，本测试强制把关）。
 */
class AgentProtocolSyncTest {

    /**
     * 从 user.dir 逐级上溯定位仓库根（含 src/main/kotlin 的目录，与 BundleParityGuardTest.repoRoot 同款）。
     * agent 子项目已迁至 sub-projects/sql-visualizer-agent（a1bf63e）；本查找链兼容
     * 测试工作目录在仓库根/子目录两种情况。
     */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }

    private fun readAgentSource(relative: String): String {
        // a1bf63e 迁移后路径：sub-projects/sql-visualizer-agent/<relative>；保留旧 agent/ 相对路径兼容
        val root = repoRoot()
        val candidates = listOf(
            File(root, "sub-projects/sql-visualizer-agent/$relative"),
            File("sub-projects/sql-visualizer-agent/$relative"),
            File(relative),
            File("agent/$relative"),
            File("../agent/$relative"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("agent 源文件未找到: $relative (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun 命令常量两侧一致() {
        val bus = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java")
        fun agentConst(name: String): Int {
            val m = Regex("CMD_$name\\s*=\\s*(\\d+)").find(bus)
                ?: throw AssertionError("EventBus 缺少 CMD_$name 常量")
            return m.groupValues[1].toInt()
        }
        assertEquals(AgentProtocol.CMD_EVENT, agentConst("EVENT"))
        assertEquals(AgentProtocol.CMD_HEARTBEAT, agentConst("HEARTBEAT"))
        assertEquals(AgentProtocol.CMD_CONFIG, agentConst("CONFIG"))
        assertEquals(AgentProtocol.CMD_HELLO, agentConst("HELLO"))
    }

    @Test
    fun 帧格式_长度前缀加单字节命令() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        // 写帧形态：writeInt(len) + writeByte(cmd) + body
        assertTrue(loop.contains("writeInt(body.length)"))
        assertTrue(loop.contains("out.writeByte(cmd)"))
        // CONFIG 处理（禁用开关下发）
        assertTrue(loop.contains("handleInbound"))
        val sender = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java")
        assertTrue(sender.contains("ArrayBlockingQueue"))
    }

    /** 从 agent 侧 SenderLoop 源码解析默认心跳间隔（`DEFAULT_HEARTBEAT_MS = 10_000L`）。 */
    private fun agentHeartbeatIntervalMs(loop: String): Long {
        val m = Regex("DEFAULT_HEARTBEAT_MS\\s*=\\s*([0-9_]+)L").find(loop)
            ?: throw AssertionError("SenderLoop 缺少 DEFAULT_HEARTBEAT_MS 常量（跨侧节奏不变量失去锚点）")
        val v = m.groupValues[1].replace("_", "").toLong()
        assertTrue("agent 侧心跳间隔应为正数: $v", v > 0L)
        return v
    }

    /**
     * T-AgentHeartbeat T-02：跨侧**节奏不变量**——IDE 在线窗口必须 ≥ 3 倍 agent 侧心跳间隔。
     *
     * 取代原 `帧格式_长度前缀加单字节命令` 里的 `assertTrue(loop.contains("10_000L"))`：那条只证明
     * 源码里存在这个字面量，对「有效节奏是否 ≤ 在线窗口」完全不可见（agent 侧把间隔改成 60s、
     * 或窗口改成 10s，它照样绿）——真实缺陷现场正是「窗口 30s 照旧执行 + agent 侧实际 60s 才发心跳」，
     * 两侧各自的常量都没写错，错的是它们之间的关系。本断言锁的就是这个关系：任一侧漂移必转红。
     */
    @Test
    fun 心跳节奏_窗口不小于三倍间隔() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        val agentIntervalMs = agentHeartbeatIntervalMs(loop)
        assertTrue(
            "IDE 在线窗口 ${AgentProtocol.ONLINE_WINDOW_MS}ms 必须 ≥ 3 倍 agent 心跳间隔 ${agentIntervalMs}ms" +
                "（否则一个静默期就会摘除会话；改任一侧常量请同步另一侧）",
            AgentProtocol.ONLINE_WINDOW_MS >= 3 * agentIntervalMs,
        )
        // IDE 侧窗口自身由心跳间隔推导（不变量写进代码，不是两个各自写死的数）
        assertEquals(
            "ONLINE_WINDOW_MS 应为 3 倍 HEARTBEAT_INTERVAL_MS",
            AgentProtocol.HEARTBEAT_INTERVAL_MS * 3,
            AgentProtocol.ONLINE_WINDOW_MS,
        )
    }

    /**
     * T-AgentHeartbeat T-01 反漂移：agent 侧心跳必须按**真实流逝时间**判定。
     *
     * 判据是「基于最近出站帧时刻的墙钟差」，而不是「假定每轮 200ms」的累加计时——后者在真实
     * 每轮≈1.2s 的循环里会把 10s 拖成 60s（缺陷根因）。回到累加形态即转红。
     */
    @Test
    fun 心跳计时_按真实流逝时间() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        // 注释里会引述旧形态（说明缺陷根因），故判据只看代码文本
        val code = loop
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        assertTrue(
            "SenderLoop 心跳须以最近出站帧时刻（lastOutboundMs）的墙钟差判定",
            code.contains("lastOutboundMs"),
        )
        assertTrue(
            "SenderLoop 不得再用「假定每轮耗时」累加计时（每轮 += 200 的形态会把 10s 拖成 60s）",
            !code.contains("idleMs"),
        )
        // 连接建立后立即补发心跳（首轮迭代被 CONFIG 读超时拖住时，IDE 侧 lastSeen 也从头新鲜）：
        // HELLO 写帧之后、主循环体（handleInbound 调用）之前须有一条 HEARTBEAT 写帧
        val helloIdx = code.indexOf("writeFrame(out, EventBus.CMD_HELLO")
        val hbIdx = code.indexOf("writeFrame(out, EventBus.CMD_HEARTBEAT")
        val bodyIdx = code.indexOf("handleInbound(cmd, readPayload(in, cmd))")
        assertTrue(
            "SenderLoop 须在写完 HELLO 后（主循环之前）立即补发一条 HEARTBEAT",
            helloIdx >= 0 && hbIdx > helloIdx && (bodyIdx < 0 || hbIdx < bodyIdx),
        )
        // 心跳间隔可注入（系统属性），供节奏实测把间隔压到小值
        assertTrue(
            "SenderLoop 心跳间隔须可经系统属性注入（sqlvis.agent.heartbeatMs）",
            code.contains("sqlvis.agent.heartbeatMs"),
        )
    }
}
