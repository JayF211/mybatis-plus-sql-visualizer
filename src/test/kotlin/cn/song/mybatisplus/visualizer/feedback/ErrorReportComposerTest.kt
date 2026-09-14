package cn.song.mybatisplus.visualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ErrReport-04：报告内容组装纯函数单测（服务端约束对齐，见开发计划 §2.2 契约表）。
 */
class ErrorReportComposerTest {

    private val stack = """
        java.lang.RuntimeException: boom
        	at cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh(SqlLogPanel.kt:120)
        	at com.intellij.openapi.application.ApplicationImpl.run(ApplicationImpl.java:1)
    """.trimIndent()

    // ---------- 标题 ----------

    @Test
    fun `标题为异常简单名加message首行`() {
        assertEquals(
            "RuntimeException: boom",
            ErrorReportComposer.buildTitle("java.lang.RuntimeException", "boom\n第二行"),
        )
    }

    @Test
    fun `标题超200截断`() {
        val long = "x".repeat(500)
        val title = ErrorReportComposer.buildTitle("java.lang.Error", long)
        assertEquals(200, title.length)
    }

    @Test
    fun `无message与空类名兜底`() {
        val title = ErrorReportComposer.buildTitle(null, null)
        assertTrue(title.contains("PluginError"))
        // 过短标题补齐到服务端最低 5 字符
        val short = ErrorReportComposer.buildTitle("a.B", "hi")
        assertTrue(short.length >= 5)
    }

    // ---------- 描述 ----------

    @Test
    fun `描述按消息_补充_指纹顺序拼接`() {
        val desc = ErrorReportComposer.buildDescription("msg", "note", "abcd1234")
        assertEquals("msg\n\nnote\n\nfingerprint: abcd1234", desc)
    }

    @Test
    fun `描述过短补兜底句满足服务端10字符下限`() {
        val desc = ErrorReportComposer.buildDescription(null, null, null)
        assertTrue(desc.length >= 10)
        assertTrue(desc.contains("from IDE error report"))
    }

    // ---------- 分类 ----------

    @Test
    fun `分类映射按特定规则优先`() {
        assertEquals("sql-log", ErrorReportComposer.guessCategory(stack))
        assertEquals("batch", ErrorReportComposer.guessCategory("at cn.song.mybatisplus.visualizer.converter.BatchOperationConverter.convert"))
        assertEquals("sql-to-java", ErrorReportComposer.guessCategory("at cn.song.mybatisplus.visualizer.reverse.SqlReverseParser.parse"))
        assertEquals("wrapper", ErrorReportComposer.guessCategory("at cn.song.mybatisplus.visualizer.converter.LambdaQueryConverter.apply"))
        assertEquals("other", ErrorReportComposer.guessCategory("at java.lang.Exception"))
        assertEquals("other", ErrorReportComposer.guessCategory(null))
    }

    // ---------- 堆栈附件 ----------

    @Test
    fun `堆栈附件含环境头与全文`() {
        // k2j 互操作适配：Java 方法无具名实参，按 (stack, ideVersion, pluginVersion, fingerprint, occurredAt) 位置传参
        val content = ErrorReportComposer.buildStackAttachment(
            stack, "IntelliJ IDEA 2026.2.1", "2026.9.8",
            "abcd1234", "2026-09-08 13:00:00 +08:00",
        )
        assertTrue(content.contains("时间: 2026-09-08 13:00:00 +08:00"))
        assertTrue(content.contains("IDE: IntelliJ IDEA 2026.2.1"))
        assertTrue(content.contains("插件版本: 2026.9.8"))
        assertTrue(content.contains("指纹: abcd1234"))
        assertTrue(content.contains("RuntimeException: boom"))
    }

    @Test
    fun `超长堆栈截断到6万字符`() {
        val huge = "at a.B.c(B.java:1)\n".repeat(10_000)
        val content = ErrorReportComposer.buildStackAttachment(huge, null, null, null, "t")
        assertTrue(content.length < 61_000)
    }

    // ---------- 预填 ----------

    @Test
    fun `预填组合标题描述与分类`() {
        val prefill = ErrorReportComposer.prefill("java.lang.RuntimeException", "boom", stack)
        assertEquals("RuntimeException: boom", prefill.title)
        assertTrue(prefill.description.contains("boom"))
        assertEquals("sql-log", prefill.category)
    }
}
