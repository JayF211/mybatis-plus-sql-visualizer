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
        // 心跳 10s（§4.3）
        assertTrue(loop.contains("10_000L"))
        // CONFIG 处理（禁用开关下发）
        assertTrue(loop.contains("handleInbound"))
        val sender = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java")
        assertTrue(sender.contains("ArrayBlockingQueue"))
    }
}
