package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.ConsoleReplayGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-PS4-06 重放守卫首行区间读测试：line0Reader 求值分支改 `getCharsSequence` 首行区间读
 * （[0, 首个行分隔)），历史语义逐字复刻防行为漂移：
 *
 * - 行分隔口径与既有 `lineSequence()` 一致（`\n` / `\r\n` / `\r` 同为分隔）；
 * - 无行分隔取全文（单行文档场景）；
 * - 空文档为 null（对齐 `lineSequence().firstOrNull()`）；
 * - stamp 缓存语义保持（stamp 未变不重读，stamp 变化恰重读一次）；
 * - 全文档 `getText` 调用面归零——桩文档**故意不提供 getText 方法**：若实现回退 getText 反射，
 *   NoSuchMethod 会被 runCatching 吞掉使 line0Reader 恒返回 null，内容断言即失败（行为等价证明）。
 *
 * 经 [SqlLogConsoleBridge.attachReplayGuard] internal 测试缝对反射桩直接驱动。
 */
class ReplayGuardLine0ReadTest {

    // ---------- 反射桩：方法名对齐 attachReplayGuard 的反射解析面 ----------

    /** 桩文档：不声明 getText（见类 KDoc「getText 调用面归零」等价证明）。 */
    private class FakeDoc(text0: String) {
        var stamp: Long = 1L
        var text: String = text0
        val charsRequests = AtomicInteger()
        val fullSeqToStringCalls = AtomicInteger()

        @Suppress("unused")
        fun getModificationStamp(): Long = stamp

        @Suppress("unused")
        fun getCharsSequence(): CharSequence {
            charsRequests.incrementAndGet()
            return CountingSeq(text, fullSeqToStringCalls)
        }
    }

    /** 免拷贝视图模拟：整段 toString（= 全文档拷贝等价物）单独计数。 */
    private class CountingSeq(private val s: String, private val fullToStringCalls: AtomicInteger) : CharSequence {
        override val length: Int get() = s.length
        override fun get(index: Int): Char = s[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.subSequence(startIndex, endIndex)
        override fun toString(): String {
            fullToStringCalls.incrementAndGet()
            return s
        }
    }

    private class FakeEditor(private val doc: FakeDoc) {
        @Suppress("unused")
        fun getDocument(): FakeDoc = doc
    }

    private class FakeConsole(private val editor: FakeEditor) {
        @Suppress("unused")
        fun getEditor(): FakeEditor = editor
    }

    /** 构建桥 + ctx 并挂载重放守卫，返回（doc 引用, ctx）。 */
    private fun attach(text: String): Pair<FakeDoc, BridgeLineContext> {
        val bridge = SqlLogConsoleBridge(SqlLogEntryBuffer())
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        val ctx = BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
        val doc = FakeDoc(text)
        bridge.attachReplayGuard(FakeConsole(FakeEditor(doc)), ctx)
        return doc to ctx
    }

    // ---------- 首行区间读：历史 lineSequence().firstOrNull() 语义逐字复刻 ----------

    @Test
    fun `多行文档取首行_全文档拷贝调用面归零`() {
        val (ref, ctx) = attach("abc\ndef\nghi")
        assertEquals("abc", ctx.line0Reader!!.invoke())
        assertEquals("首行区间读：整段 toString（全文档拷贝等价物）零调用", 0, ref.fullSeqToStringCalls.get())
    }

    @Test
    fun `CRLF与CR行分隔口径与lineSequence一致`() {
        val (_, ctx1) = attach("abc\r\ndef")
        assertEquals("\\r\\n 场景首行不含 \\r（对齐 lineSequence 口径）", "abc", ctx1.line0Reader!!.invoke())
        val (_, ctx2) = attach("abc\rdef")
        assertEquals("孤立 \\r 亦为行分隔（lineSequence 同口径）", "abc", ctx2.line0Reader!!.invoke())
        val (_, ctx3) = attach("\nabc")
        assertEquals("首行空文本", "", ctx3.line0Reader!!.invoke())
        val (_, ctx4) = attach("\rabc")
        assertEquals("首行空文本（\\r 起始）", "", ctx4.line0Reader!!.invoke())
    }

    @Test
    fun `单行文档无行分隔取全文`() {
        val (ref, ctx) = attach("only line without separator")
        assertEquals("only line without separator", ctx.line0Reader!!.invoke())
        assertEquals("无行分隔才允许整段读取（拷贝即首行本身）", 1, ref.fullSeqToStringCalls.get())
    }

    @Test
    fun `空文档为null_对齐lineSequence_firstOrNull历史语义`() {
        val (_, ctx) = attach("")
        assertNull(ctx.line0Reader!!.invoke())
    }

    // ---------- stamp 缓存语义保持 ----------

    @Test
    fun `stamp未变不重读_stamp变化恰重读一次`() {
        val (ref, ctx) = attach("first\nsecond")
        val reader = ctx.line0Reader!!
        assertEquals("first", reader.invoke())
        assertEquals(1, ref.charsRequests.get())
        assertEquals("同 stamp 内缓存命中（不重读）", "first", reader.invoke())
        assertEquals(1, ref.charsRequests.get())
        // stamp 变化 → 恰重读一次，且读到新文本首行
        ref.stamp = 2L
        ref.text = "new-first\nnew-second"
        assertEquals("new-first", reader.invoke())
        assertEquals(2, ref.charsRequests.get())
        assertEquals("新 stamp 内继续缓存", "new-first", reader.invoke())
        assertEquals(2, ref.charsRequests.get())
    }

    // ---------- 经真实 line0Reader 的守卫混合态判定（与纯逻辑测试同断言，防行为漂移） ----------

    @Test
    fun `混合态判定经真实line0Reader_与纯逻辑守卫测试同断言`() {
        val (ref, ctx) = attach("X\nrest of history")
        val guard = ctx.replayGuard
        // 流式推进
        assertEquals(Decision.Process, guard.decide(100, 1L, "X", ctx.line0Reader))
        // 重放叠加新增混合态：stamp 变 + 行文本 == 首行 → Replay（历史断言逐字复刻）
        ref.stamp = 2L
        assertEquals(Decision.Replay, guard.decide(10, 2L, "X", ctx.line0Reader))
        assertEquals("混合态判 Replay 水位不动", 100, guard.watermark)
        // 控制台重置：stamp 变 + 行文本 != 首行 → Reset
        ref.stamp = 3L
        assertEquals(Decision.Reset, guard.decide(5, 3L, "fresh", ctx.line0Reader))
        assertEquals(5, guard.watermark)
    }
}
