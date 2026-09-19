package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * [FatalErrors] 表驱动用例（T-FatalPassthrough T-FP-01 / 验收 1）。
 *
 * 纯逻辑类（无平台依赖），随 `testFast` 秒级通道执行。除分类表外还钉住两件事：
 *  - **真实**栈溢出（真递归撞栈，不是 `StackOverflowError("x")` 造对象）判 fatal——避免
 *    「只测构造出来的实例、与真实抛出形态脱节」；
 *  - 形态 A 片段（`if (FatalErrors.isFatal(t)) throw t`）在捕获块里**只放行 fatal**：
 *    普通异常（含 `ClassNotFoundException` 这类「可选能力缺失」信号）仍被既有降级吞掉（验收 4）。
 */
class FatalErrorsTest {

    @Test
    fun `致命错误白名单三类及其子类判true`() {
        val fatal: List<Throwable> = listOf(
            // VirtualMachineError 族
            StackOverflowError(),
            OutOfMemoryError(),
            InternalError(),
            UnknownError(),
            // LinkageError 族（类加载/链接损坏——与「可选能力缺失」不同，见 FatalErrors KDoc）
            NoClassDefFoundError("cn/song/example/Missing"),
            ExceptionInInitializerError(IllegalStateException("static init failed")),
            IncompatibleClassChangeError(),
            NoSuchMethodError("cn.song.Example#run()V"),
            VerifyError(),
            // ThreadDeath
            ThreadDeath(),
        )
        for (t in fatal) {
            assertTrue("${t.javaClass.name} 应判 fatal", FatalErrors.isFatal(t))
        }
    }

    @Test
    fun `普通异常与白名单外的Error判false`() {
        val notFatal: List<Throwable> = listOf(
            Exception("plain"),
            RuntimeException("runtime"),
            IOException("io"),
            IllegalArgumentException("bad arg"),
            IllegalStateException("bad state"),
            // 「可选能力缺失」的既有降级信号——**不是** fatal，反射探测靠它回落（验收 4）
            ClassNotFoundException("com.example.Optional"),
            NoSuchMethodException("optional"),
            UnsupportedOperationException("not supported"),
            // 非白名单 Error：断言失败是编程错误信号，既有降级路径需要接住（FatalErrors KDoc）
            AssertionError("assertion"),
            Error("bare error"),
            // 自定义异常
            object : RuntimeException("custom") {},
        )
        for (t in notFatal) {
            assertFalse("${t.javaClass.name} 不应判 fatal", FatalErrors.isFatal(t))
        }
    }

    @Test
    fun `真实栈溢出判fatal且带现场`() {
        val soe = realStackOverflow()
        assertEquals("必须是真实抛出的 StackOverflowError", StackOverflowError::class.java, soe.javaClass)
        assertTrue("真实栈溢出应判 fatal", FatalErrors.isFatal(soe))
        assertTrue("真实抛出的 fatal 应带非空栈（现场不得为空）", soe.stackTrace.isNotEmpty())
    }

    @Test
    fun `形态A片段只放行fatal_普通异常仍被吞`() {
        // 与落地形态逐字一致的片段（形态 A）：捕获块首行判 fatal 并原样抛出
        fun swallowLikeProduction(action: () -> Unit): Throwable? = try {
            action()
            null
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t
            t // 既有降级：吞掉（生产侧此处是 LOG.warn + 回落）
        }

        val thrown = try {
            swallowLikeProduction { throw realStackOverflow() }
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("fatal 必须透传而非被吞，实际=$thrown", thrown is StackOverflowError)

        val swallowed = swallowLikeProduction { throw IOException("optional capability absent") }
        assertTrue("普通异常仍走既有降级（不得顺手改成抛出）", swallowed is IOException)
        val linkMiss = swallowLikeProduction { throw ClassNotFoundException("optional") }
        assertTrue("可选能力缺失（ClassNotFoundException）仍走降级", linkMiss is ClassNotFoundException)
    }

    @Test
    fun `反射包装的致命错误须解包后判定`() {
        // Method.invoke 把目标异常（含 Error）包进 InvocationTargetException（普通异常）——
        // 外层直接判会漏判，落地形态见 execute/DataSourceSelector.kt 原生执行链的 cause 解包。
        val target = FatalErrorsTest::class.java.getDeclaredMethod("throwRealStackOverflow")
        val ite = try {
            target.invoke(this)
            null
        } catch (t: InvocationTargetException) {
            t
        }
        assertTrue("反射调用须抛 InvocationTargetException", ite is InvocationTargetException)
        assertFalse("包装层本身不是 fatal（故须解包再判）", FatalErrors.isFatal(ite!!))
        assertTrue("解包后的 targetException 才是 fatal", FatalErrors.isFatal(ite.targetException))
    }

    /** 供反射调用：真实撞栈抛出（非构造实例）。 */
    @Suppress("unused")
    private fun throwRealStackOverflow(): Unit = throw realStackOverflow()

    /** 真递归撞栈得到**真实** StackOverflowError（含真实现场栈）。 */
    private fun realStackOverflow(): StackOverflowError {
        fun dive(n: Int): Int = dive(n + 1) + 1
        return try {
            dive(0)
            error("unreachable: 递归未溢出")
        } catch (e: StackOverflowError) {
            e
        }
    }
}
