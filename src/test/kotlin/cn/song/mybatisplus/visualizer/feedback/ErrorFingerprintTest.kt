package cn.song.mybatisplus.visualizer.feedback

import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-ErrReport-02：栈指纹与 24h 去重单测（纯 JUnit4，无平台依赖）。
 */
class ErrorFingerprintTest {

    private val stack = """
        java.lang.RuntimeException: boom
        	at cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh(SqlLogPanel.kt:120)
        	at cn.song.mybatisplus.visualizer.converter.Foo.convert(Foo.kt:55)
        	at com.intellij.openapi.application.ApplicationImpl.run(ApplicationImpl.java:1)
        	at cn.song.mybatisplus.visualizer.ui.Bar.render(Bar.kt:9)
        	at java.base/java.util.ArrayList.forEach(ArrayList.java:1)
        	at cn.song.mybatisplus.visualizer.log.Baz.tick(Baz.kt:3)
        	at cn.song.mybatisplus.visualizer.log.Qux.tock(Qux.kt:7)
    """.trimIndent()

    @Before
    fun setUp() = ErrorFingerprint.reset()

    /** 时钟 seam 注入（SAM lambda 构造——k2j 互操作适配，抽到非 test 前缀私有方法）。 */
    private fun fixedClock(read: () -> Long): LongSupplier = LongSupplier { read() }

    @Test
    fun `同输入指纹稳定`() {
        assertEquals(ErrorFingerprint.of("java.lang.RuntimeException", stack), ErrorFingerprint.of("java.lang.RuntimeException", stack))
    }

    @Test
    fun `异常类名参与指纹`() {
        assertNotEquals(ErrorFingerprint.of("java.lang.RuntimeException", stack), ErrorFingerprint.of("java.lang.IllegalStateException", stack))
    }

    @Test
    fun `平台帧剔除后行号漂移不影响指纹`() {
        val shifted = stack.replace("SqlLogPanel.kt:120", "SqlLogPanel.kt:999")
            .replace("ApplicationImpl.java:1", "ApplicationImpl.java:77")
        assertEquals(ErrorFingerprint.of("X", stack), ErrorFingerprint.of("X", shifted))
    }

    @Test
    fun `业务帧差异改变指纹`() {
        // 行号被指纹剔除（防漂移），方法名差异才改变指纹
        val other = stack.replace("Foo.convert(", "Foo.convert2(")
        assertNotEquals(ErrorFingerprint.of("X", stack), ErrorFingerprint.of("X", other))
    }

    @Test
    fun `仅前五个业务帧参与_第六帧起无关`() {
        // 业务帧序列：SqlLogPanel / Foo / Bar / Baz / Qux（平台两帧被剔除）——替换第 6 个业务帧之后不存在，
        // 构造 6 个业务帧样本验证第 6 帧不参与
        val sixFrames = """
            X
            	at a.B.c1(B.java:1)
            	at a.B.c2(B.java:2)
            	at a.B.c3(B.java:3)
            	at a.B.c4(B.java:4)
            	at a.B.c5(B.java:5)
            	at a.B.c6(B.java:6)
            	at a.B.c7(B.java:7)
        """.trimIndent()
        val sixFramesAlt = sixFrames.replace("c7(B.java:7)", "c7(B.java:70)")
        assertEquals(ErrorFingerprint.of("X", sixFrames), ErrorFingerprint.of("X", sixFramesAlt))
        // 第 5 个业务帧（方法名）变化必须改变指纹；行号变化不改变（指纹剔除行号）
        val sixFramesAlt2 = sixFrames.replace("c5(", "c5x(")
        assertNotEquals(ErrorFingerprint.of("X", sixFrames), ErrorFingerprint.of("X", sixFramesAlt2))
    }

    @Test
    fun `businessFrames_解析at行并剔除平台前缀`() {
        val frames = ErrorFingerprint.businessFrames(stack)
        assertEquals(
            listOf(
                "cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh",
                "cn.song.mybatisplus.visualizer.converter.Foo.convert",
                "cn.song.mybatisplus.visualizer.ui.Bar.render",
                "cn.song.mybatisplus.visualizer.log.Baz.tick",
                "cn.song.mybatisplus.visualizer.log.Qux.tock",
            ),
            frames,
        )
    }

    @Test
    fun `空堆栈退化为异常类名哈希且非空`() {
        val fp = ErrorFingerprint.of("java.lang.RuntimeException", null)
        assertTrue(fp.isNotEmpty())
        assertEquals(16, fp.length)
        assertEquals(fp, ErrorFingerprint.of("java.lang.RuntimeException", ""))
        assertNotEquals(fp, ErrorFingerprint.of("java.lang.Error", null))
    }

    @Test
    fun `record后seenRecently命中_24h后过期`() {
        var now = 1_000_000L
        ErrorFingerprint.setClock(fixedClock { now })
        val fp = ErrorFingerprint.of("X", stack)
        assertFalse(ErrorFingerprint.seenRecently(fp))
        ErrorFingerprint.record(fp)
        assertTrue(ErrorFingerprint.seenRecently(fp))
        now += 23 * 60 * 60 * 1000L
        assertTrue(ErrorFingerprint.seenRecently(fp))
        now += 2 * 60 * 60 * 1000L
        assertFalse(ErrorFingerprint.seenRecently(fp))
    }

    @Test
    fun `LRU超上限淘汰最旧`() {
        var now = 0L
        ErrorFingerprint.setClock(fixedClock { now })
        repeat(140) { index ->
            ErrorFingerprint.record("fp-$index")
        }
        assertEquals(128, ErrorFingerprint.seenEntriesForTest().size)
        // 最旧的 fp-0 ~ fp-11 被淘汰，最新的保留
        assertFalse(ErrorFingerprint.seenRecently("fp-0"))
        assertTrue(ErrorFingerprint.seenRecently("fp-139"))
    }
}
