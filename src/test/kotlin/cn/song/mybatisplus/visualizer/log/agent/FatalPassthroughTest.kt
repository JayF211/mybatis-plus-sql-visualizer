package cn.song.mybatisplus.visualizer.log.agent

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * fatal 透传**行为**用例（T-FatalPassthrough T-FP-02 / 验收 2、验收 4）。
 *
 * 落地子集里绝大多数捕获点没有可注入的测试缝（`object DataSourceSelector` 的私有函数、
 * 监听器的私有判定、invokeLater lambda 内的抛点——异常交给平台 EDT 处理器，断言侧接不住），
 * 那部分由 [cn.song.mybatisplus.visualizer.convention.BannedApiSourceScanTest] 的**逐站点结构断言**兜住。
 * 本类覆盖 agent 长驻链上一个可注入的真实站点——[AgentSession.sendConfig] 的 CONFIG 下发捕获块：
 *
 *  - fatal（真递归撞栈得到的 StackOverflowError、构造的 NoClassDefFoundError）**原样透传**，
 *    且透传路径不执行降级副作用（`alive` 不置 false、不触发状态回调）——原状是静默掉线；
 *  - 普通异常（IOException）**逐字保持既有降级**（alive=false + 状态回调），防「顺手全改」回归。
 */
class FatalPassthroughTest : BasePlatformTestCase() {

    fun test会话CONFIG下发_致命错误透传而非静默掉线() {
        val soe = realStackOverflow()
        val stateChanges = AtomicInteger()
        val session = AgentSession(
            project,
            PID_SOE,
            socketFailingOnWrite(soe),
            onStateChange = stateChangeCounter(stateChanges),
        )
        try {
            val thrown = try {
                session.sendConfig(disabled = false, desensitize = false)
                null
            } catch (t: Throwable) {
                t
            }
            assertSame("fatal 必须原样透传（原状：被吞成静默掉线，崩溃现场只剩一行日志）", soe, thrown)
            assertTrue("透传路径不得执行降级副作用：alive 应保持 true", session.alive)
            assertEquals("透传路径不得触发状态回调（不假装掉线）", 0, stateChanges.get())
        } finally {
            interruptSessionThread(PID_SOE)
        }
    }

    fun test会话CONFIG下发_链接错误透传() {
        val ncdfe = NoClassDefFoundError("cn/song/mybatisplus/visualizer/agent/EventBus")
        val stateChanges = AtomicInteger()
        val session = AgentSession(
            project,
            PID_NCDFE,
            socketFailingOnWrite(ncdfe),
            onStateChange = stateChangeCounter(stateChanges),
        )
        try {
            val thrown = try {
                session.sendConfig(disabled = false, desensitize = false)
                null
            } catch (t: Throwable) {
                t
            }
            assertSame("LinkageError 属白名单，须原样透传", ncdfe, thrown)
            assertTrue("透传路径不得置 alive=false", session.alive)
        } finally {
            interruptSessionThread(PID_NCDFE)
        }
    }

    fun test会话CONFIG下发_普通异常仍走既有降级() {
        val stateChanges = AtomicInteger()
        val session = AgentSession(
            project,
            PID_IO,
            socketFailingOnWrite(IOException("broken pipe")),
            onStateChange = stateChangeCounter(stateChanges),
        )
        try {
            session.sendConfig(disabled = false, desensitize = false) // 不得抛
            assertFalse("普通异常仍走既有降级：alive=false（回落桥）", session.alive)
            assertEquals("普通异常仍触发状态回调（零劣化回落）", 1, stateChanges.get())
        } finally {
            interruptSessionThread(PID_IO)
        }
    }

    // ---------- 夹具 ----------

    private fun socketFailingOnWrite(t: Throwable): Socket = FakeSocket { throw t }

    private fun stateChangeCounter(counter: AtomicInteger): () -> Unit = { counter.incrementAndGet() }

    /** 让会话读线程停在阻塞读上退出（daemon 线程，避免跨用例残留）。 */
    private fun interruptSessionThread(pid: Long) {
        Thread.getAllStackTraces().keys
            .firstOrNull { it.name == "mps-agent-session-$pid" }
            ?.interrupt()
    }

    /** 真递归撞栈得到**真实** StackOverflowError（含真实现场栈，非构造实例）。 */
    private fun realStackOverflow(): StackOverflowError {
        fun dive(n: Int): Int = dive(n + 1) + 1
        return try {
            dive(0)
            error("unreachable: 递归未溢出")
        } catch (e: StackOverflowError) {
            e
        }
    }

    /**
     * 假 socket：输入流为**阻塞**管道（会话读线程停在 `readInt`，不干扰断言）、
     * 输出流按 [onWrite] 抛错；覆写 `setSoTimeout` 避开未连接 socket 的 SocketException。
     */
    private class FakeSocket(private val onWrite: () -> Unit) : Socket() {
        private val blockingInput = PipedInputStream()

        override fun getInputStream(): InputStream = blockingInput

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int): Unit = onWrite()
            override fun write(b: ByteArray, off: Int, len: Int): Unit = onWrite()
        }

        override fun setSoTimeout(timeout: Int) = Unit

        override fun close() {
            blockingInput.close()
        }
    }

    private companion object {
        const val PID_SOE = 987654L
        const val PID_NCDFE = 987655L
        const val PID_IO = 987656L
    }
}
