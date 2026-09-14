package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.editor.impl.DocumentImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PerfTail #3：重放守卫行 0 零拷贝读取测试。
 *
 *  - [SqlLogConsoleBridge.firstLineText] 纯函数语义与旧 `getText().lineSequence().firstOrNull()`
 *    逐一对齐（空文档 → null；无换行 → 全文；`\n` 开头 → 空串首行）；
 *  - 零拷贝断言：对只读前缀的 CharSequence 桩不触发全量物化（charAt 访问不越过首行末尾）；
 *  - 真实 Document（getCharsSequence 视图）上的行为一致。
 */
class Line0ZeroCopyTest {

    /** 记录 charAt 最大访问下标的 CharSequence 桩（模拟 getCharsSequence 视图的惰性读取）。 */
    private class RecordingCharSequence(private val text: String) : CharSequence {
        var maxAccessedIndex = -1
            private set

        override val length: Int = text.length
        override fun get(index: Int): Char {
            maxAccessedIndex = maxOf(maxAccessedIndex, index)
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            text.substring(startIndex, endIndex)
        override fun toString(): String {
            maxAccessedIndex = maxOf(maxAccessedIndex, text.length - 1)
            return text
        }
    }

    // ---------- 语义对齐（与旧 lineSequence().firstOrNull() 一致） ----------

    @Test
    fun test首行提取语义对齐() {
        assertEquals("first line", SqlLogConsoleBridge.firstLineText("first line\nsecond line"))
        assertEquals("no newline", SqlLogConsoleBridge.firstLineText("no newline"))
        assertEquals("", SqlLogConsoleBridge.firstLineText("\nsecond line"))
        assertEquals("abc", SqlLogConsoleBridge.firstLineText("abc\n"))
        assertNull("空文档 → null（守卫按 Reset 处理）", SqlLogConsoleBridge.firstLineText(""))
    }

    // ---------- 零拷贝：只读前缀，不全量物化 ----------

    @Test
    fun test大文本零拷贝_不越过首行末尾() {
        val line0 = "==>  Preparing: SELECT * FROM user WHERE id = 1"
        val big = line0 + "\n" + "x".repeat(64 * 1024 * 1024) // 模拟 64MB 控制台文档
        val seq = RecordingCharSequence(big)
        assertEquals(line0, SqlLogConsoleBridge.firstLineText(seq))
        // indexOf 需读到换行符本身（index = line0.length），但绝不越过——后半段 64MB 零访问
        // （toString 全量物化会把 maxAccessedIndex 推到文档末尾，此断言同时证明未发生 toString）
        assertTrue(
            "charAt 最大访问下标(${seq.maxAccessedIndex})不得超过首行末尾换行符(${line0.length})——零拷贝只读前缀",
            seq.maxAccessedIndex <= line0.length,
        )
    }

    @Test
    fun test无换行时才物化全文() {
        val body = "y".repeat(1024)
        val seq = RecordingCharSequence(body)
        assertEquals(body, SqlLogConsoleBridge.firstLineText(seq))
        assertEquals("无换行场景全文即首行", body.length - 1, seq.maxAccessedIndex)
    }

    // ---------- 真实 Document（getCharsSequence 视图）行为一致 ----------

    @Test
    fun test真实Document的charsSequence() {
        val doc = DocumentImpl("SELECT 1\nFROM t\nWHERE id = 1")
        assertEquals("SELECT 1", SqlLogConsoleBridge.firstLineText(doc.getCharsSequence()))
        val single = DocumentImpl("SELECT 2;")
        assertEquals("SELECT 2;", SqlLogConsoleBridge.firstLineText(single.getCharsSequence()))
        val empty = DocumentImpl("")
        assertNull(SqlLogConsoleBridge.firstLineText(empty.getCharsSequence()))
    }
}
