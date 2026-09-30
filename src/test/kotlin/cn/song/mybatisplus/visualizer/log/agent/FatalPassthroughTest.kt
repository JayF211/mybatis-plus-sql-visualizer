package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.lang.reflect.InvocationTargetException
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * fatal 透传**行为**用例（T-FatalPassthrough T-FP-02 / 验收 2、验收 4）。
 *
 * 落地子集里绝大多数捕获点没有可注入的测试缝（监听器的私有判定、invokeLater lambda 内的抛点——
 * 异常交给平台 EDT 处理器，断言侧接不住），那部分由
 * [cn.song.mybatisplus.visualizer.convention.BannedApiSourceScanTest] 的**逐站点结构断言**兜住。
 * 本类覆盖可注入的真实站点（T-K2JTail5 补第三组：执行链 runRunner 解包锚点收口的行为判别）：
 *
 *  - agent 长驻链 [AgentSession.sendConfig] 的 CONFIG 下发捕获块：
 *    fatal（真递归撞栈得到的 StackOverflowError、构造的 NoClassDefFoundError）**原样透传**，
 *    且透传路径不执行降级副作用（`alive` 不置 false、不触发状态回调）——原状是静默掉线；
 *    普通异常（IOException）**逐字保持既有降级**（alive=false + 状态回调），防「顺手全改」回归。
 *  - 执行链 [cn.song.mybatisplus.visualizer.execute.DataSourceSelector.writeConsoleOutput] 的
 *    **外层 `runCatching` 边界**（T-FP-02b，独立复核阻断 1）：该函数两处捕获块已判 fatal，但
 *    `throw` 落在同一函数的外层 `runCatching { … }.onFailure { LOG.info }` 内——外层不放行即
 *    「结构性无效透传」（改前实测：fatal 变成一行 `[M36] writeConsoleOutput FAILED: …` INFO 后
 *    执行链带病继续）。`writeConsoleOutput` 为此从 private 改 internal（同
 *    [cn.song.mybatisplus.visualizer.execute.DataSourceSelector.execFileContentMatches] 先例）。
 *
 * k2j 适配：Java 侧构造器/方法无默认参数与具名实参——`AgentSession` 第 4 参改传 `Runnable`
 * （`stateChangeCounter` 直接返回 `Runnable`）、`sendConfig(false, false)`、
 * `writeConsoleOutput(console, text, isError)` 显式补 `isError = false`。
 */
class FatalPassthroughTest : BasePlatformTestCase() {

    fun test会话CONFIG下发_致命错误透传而非静默掉线() {
        val soe = realStackOverflow()
        val stateChanges = AtomicInteger()
        val session = AgentSession(
            project,
            PID_SOE,
            socketFailingOnWrite(soe),
            stateChangeCounter(stateChanges),
        )
        try {
            val thrown = try {
                session.sendConfig(false, false)
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
            stateChangeCounter(stateChanges),
        )
        try {
            val thrown = try {
                session.sendConfig(false, false)
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
            stateChangeCounter(stateChanges),
        )
        try {
            session.sendConfig(false, false) // 不得抛
            assertFalse("普通异常仍走既有降级：alive=false（回落桥）", session.alive)
            assertEquals("普通异常仍触发状态回调（零劣化回落）", 1, stateChanges.get())
        } finally {
            interruptSessionThread(PID_IO)
        }
    }

    // ---------- writeConsoleOutput 外层 runCatching 边界（T-FP-02b） ----------

    /**
     * 反射链的 fatal：假 consoleView 的 `getHistoryViewer()` 抛真 SOE，`Method.invoke` 把它包进
     * `InvocationTargetException`（普通异常）——外层 `onFailure` 须**解包 cause** 再判，只判外层会漏判。
     */
    fun test控制台输出_反射包装的fatal须解包后透传() {
        val soe = realStackOverflow()
        val thrown = try {
            DataSourceSelector.writeConsoleOutput(FakeConsole(FakeConsoleView { throw soe }), "boom", false)
            null
        } catch (t: Throwable) {
            t
        }
        assertSame("外层 runCatching 不得把 fatal 吞成一条 INFO 日志（须解包 ITE 后原样透传）", soe, thrown)
    }

    /** 非反射链的 fatal：`doc.text` 直调接口方法抛 LinkageError，不经反射包装，须原样透传。 */
    fun test控制台输出_文档读取的链接错误须透传() {
        val ncdfe = NoClassDefFoundError("cn/song/mybatisplus/visualizer/agent/EventBus")
        val thrown = try {
            DataSourceSelector.writeConsoleOutput(
                FakeConsole(FakeConsoleView { FakeHistoryViewer(proxyDocument { throw ncdfe }) }),
                "boom",
                false,
            )
            null
        } catch (t: Throwable) {
            t
        }
        assertSame("非反射路径的 LinkageError 同样不得被外层 runCatching 吞掉", ncdfe, thrown)
    }

    /** 非 fatal 回归：普通异常仍走既有降级（只记 INFO、不抛、返回值/调用方行为不变）。 */
    fun test控制台输出_普通异常仍只记日志不抛() {
        val thrown = try {
            DataSourceSelector.writeConsoleOutput(
                FakeConsole(FakeConsoleView { throw IOException("no console view") }),
                "boom",
                false,
            )
            null
        } catch (t: Throwable) {
            t
        }
        assertNull("普通异常仍走既有降级（只记 INFO，不抛）——防「顺手全改」回归", thrown)
    }

    // ---------- 执行链解包锚点收口（T-K2JTail5）：runRunner 反射调用 ----------

    /**
     * fatalUnwrapUnjudgedBaseline=5 生产收口的行为判别。[DataSourceSelector.runRunner] 是
     * 5 处解包锚点里唯一可同步注入的站点（prepare 链各 catch 位于 EDT lambda / 池化线程，
     * 断言侧接不住，由 BannedApiSourceScanTest 的解包锚点结构断言兜底——同本类头注口径）：
     * 假 runner 的 `run()` 抛真 SOE，`Method.invoke` 把它包进 `InvocationTargetException`
     * （普通异常）——捕获块须解包后判 fatal 并透传（改前实况：被吞成一条 warn + onError 面板文案，
     * 崩溃现场降级成「数据源初始化失败」）。
     */
    fun test执行链Runner反射调用_致命错误解包后透传() {
        val soe = realStackOverflow()
        val errors = mutableListOf<String>()
        val thrown = try {
            invokeRunRunner(FakeRunner { throw soe }, silent = true, errors)
            null
        } catch (t: Throwable) {
            // 测试侧 Method.invoke 会把 runRunner 抛出的任何 Throwable 再包一层 ITE（JLS 反射契约）
            // ——这层是测试自己的包装，须解掉；生产块内那层 ITE（runner.run 的包装）由生产代码解包。
            (t as? InvocationTargetException)?.targetException ?: t
        }
        assertSame("反射链 fatal 须解包 ITE 后原样透传（原状：吞成 warn + 面板文案）", soe, thrown)
        assertTrue("透传路径不得再走 onError 面板降级", errors.isEmpty())
    }

    /** 非 fatal 回归：普通异常仍走既有降级（不抛、onError 面板行内回调、silent 路径清 last-used）。 */
    fun test执行链Runner反射调用_普通异常仍走面板回调降级() {
        val errors = mutableListOf<String>()
        val thrown = try {
            invokeRunRunner(FakeRunner { throw RuntimeException("driver missing") }, silent = true, errors)
            null
        } catch (t: Throwable) {
            (t as? InvocationTargetException)?.targetException ?: t
        }
        assertNull("普通异常仍走既有降级（不抛）——防「顺手全改」回归", thrown)
        assertEquals("普通异常仍回调 onError（面板行内文案）", 1, errors.size)
        assertTrue("文案保留原始原因（execute.datasource.failed 的 {0} 占位）", errors[0].contains("driver missing"))
    }

    // ---------- 夹具 ----------

    private fun socketFailingOnWrite(t: Throwable): Socket = FakeSocket { throw t }

    /** k2j 适配：Java 构造器第 4 参为 `Runnable`（master 侧 Kotlin 为 `() -> Unit`），直接返回 Runnable。 */
    private fun stateChangeCounter(counter: AtomicInteger): Runnable = Runnable { counter.incrementAndGet() }

    /**
     * 反射调私有静态 [DataSourceSelector.runRunner]（Project, Object, boolean, Consumer）。
     * `silent = true` 走「清 last-used 后返回」路径，不触发平台通知——判别只需 onError 回调面。
     */
    private fun invokeRunRunner(runner: Any, silent: Boolean, errors: MutableList<String>) {
        val m = DataSourceSelector::class.java.getDeclaredMethod(
            "runRunner",
            com.intellij.openapi.project.Project::class.java,
            Any::class.java,
            Boolean::class.java,
            java.util.function.Consumer::class.java,
        )
        m.isAccessible = true
        m.invoke(null, project, runner, silent, java.util.function.Consumer<String> { errors.add(it) })
    }

    /** 假 runner：只需公开的 `run()`（生产反射链形态：`getMethod("run").invoke(runner)`）。 */
    class FakeRunner(private val action: () -> Unit) {
        fun run() {
            action()
        }
    }

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

    /** 假 console：只需公开的 `getConsoleView()`（生产反射链形态：`getMethod(...).invoke(...)`）。 */
    class FakeConsole(private val view: Any) {
        fun getConsoleView(): Any = view
    }

    /** 假 consoleView：`getHistoryViewer()` 由 [onHistoryViewer] 决定（抛 fatal / 抛普通异常 / 返回假 viewer）。 */
    class FakeConsoleView(private val onHistoryViewer: () -> Any) {
        fun getHistoryViewer(): Any = onHistoryViewer()
    }

    /** 假 historyViewer：只提供 `getDocument()`（`doc is Document` 判定后走 `doc.text`）。 */
    class FakeHistoryViewer(private val document: Any) {
        fun getDocument(): Any = document
    }

    /**
     * 假 Document（接口代理）：`getText()` 抛 [throwing]——`doc.text` 是 Kotlin 属性映射，直调接口方法，
     * 不经 `Method.invoke` 包装（与上一条反射链用例互补）。
     */
    private fun proxyDocument(throwing: () -> Nothing): com.intellij.openapi.editor.Document =
        java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.openapi.editor.Document::class.java.classLoader,
            arrayOf(com.intellij.openapi.editor.Document::class.java),
        ) { _, method, _ ->
            if (method.name == "getText") throwing()
            throw UnsupportedOperationException("unexpected Document.${method.name}")
        } as com.intellij.openapi.editor.Document

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
