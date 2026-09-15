package cn.song.mybatisplus.visualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val content = ErrorReportComposer.buildStackAttachment(
            stack, ideVersion = "IntelliJ IDEA 2026.2.1", pluginVersion = "2026.9.8",
            fingerprint = "abcd1234", occurredAt = "2026-09-08 13:00:00 +08:00",
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

    // ---------- 批量报告（T-FeedbackBatch：一次多勾也不撞 429） ----------

    private fun entries(count: Int) = (1..count).map { index ->
        ErrorReportComposer.BatchEntry(
            title = "cn.song.mybatisplus.visualizer.Boom$index",
            message = "boom $index",
            stackTraceText = "java.lang.RuntimeException: boom $index\n\tat cn.song.mybatisplus.visualizer.log.P$index.r(P$index.kt:1)",
        )
    }

    @Test
    fun `合并阈值_两条到五条合并_单条与更多不合并`() {
        assertEquals(false, ErrorReportComposer.shouldMerge(1))
        assertEquals(true, ErrorReportComposer.shouldMerge(2))
        assertEquals(true, ErrorReportComposer.shouldMerge(ErrorReportComposer.BATCH_MERGE_MAX))
        assertEquals(false, ErrorReportComposer.shouldMerge(ErrorReportComposer.BATCH_MERGE_MAX + 1))
        assertEquals(false, ErrorReportComposer.shouldMerge(0))
    }

    @Test
    fun `合并标题含条数且不超服务端上限`() {
        assertEquals("Boom1: boom 1（等 3 条异常）", ErrorReportComposer.buildBatchTitle(entries(3)))
        // 超长首条也须 ≤200（服务端 400 拒绝线）
        val long = ErrorReportComposer.buildBatchTitle(
            listOf(ErrorReportComposer.BatchEntry("a.B", "x".repeat(500), null)) + entries(2),
        )
        assertTrue("len=${long.length}", long.length <= ErrorReportComposer.TITLE_MAX)
        assertTrue(long.endsWith("（等 3 条异常）"))
    }

    @Test
    fun `合并描述出条目清单并保留用户补充`() {
        val description = ErrorReportComposer.buildBatchDescription(entries(3), "顺便说一句")
        assertTrue(description.contains("共 3 条"))
        assertTrue(description.contains("#1 Boom1: boom 1"))
        assertTrue(description.contains("#3 Boom3: boom 3"))
        assertTrue(description.contains("顺便说一句"))
    }

    @Test
    fun `合并描述超20条截断并注明`() {
        val description = ErrorReportComposer.buildBatchDescription(entries(25), null)
        assertTrue(description.contains("共 25 条"))
        assertTrue(description.contains("其余 5 条见附件"))
        assertEquals(false, description.contains("#21"))
    }

    @Test
    fun `多节附件含每条分节与条数`() {
        val content = ErrorReportComposer.buildStackAttachment(
            entries(3).map { it.stackTraceText ?: "" },
            ideVersion = "IntelliJ IDEA 2026.2.1",
            pluginVersion = "2026.9.14",
            fingerprint = null,
            occurredAt = "2026-09-14 20:00:00 +08:00",
        )
        assertTrue(content.contains("异常条数: 3"))
        assertTrue(content.contains("----- #1 -----"))
        assertTrue(content.contains("----- #3 -----"))
        assertTrue(content.contains("boom 3"))
    }

    @Test
    fun `多节附件空条目退化且总长受限`() {
        val empty = ErrorReportComposer.buildStackAttachment(listOf("", "  "), null, null, null, "t")
        assertTrue(empty.contains("(无堆栈)"))
        val huge = ErrorReportComposer.buildStackAttachment(
            List(3) { "at a.B.c(B.java:1)\n".repeat(5_000) }, null, null, null, "t",
        )
        assertTrue("len=${huge.length}", huge.length <= 60_000)
    }

    @Test
    fun `附件带UTF8BOM自描述编码`() {
        // 真机第三轮：R2 元数据 MIME 不带 charset，消费方忽略响应头时中文显示成乱码——
        // BOM 让文本文件自描述（服务端响应头同时补 charset=utf-8）
        val content = ErrorReportComposer.buildStackAttachment(stack, null, null, null, "t")
        assertTrue("应以 U+FEFF 开头", content.startsWith('\uFEFF'))
        assertEquals("BOM 编码为 EF BB BF", 0xEF, content.toByteArray(Charsets.UTF_8)[0].toInt() and 0xFF)
        assertTrue(content.contains("SQL Visualizer 错误报告"))
    }

    // ---------- per-item 截断（T-PerfSweep2-03） ----------

    @Test
    fun `批量超长堆栈逐条截断带尾注且总量受限`() {
        // 病灶回归：20 条 × 50k 堆栈——此前全文 append 后才 take(60_000)，EDT 瞬时 ~1MB 字符
        // 操作且只保留前 60k（后续条目内容全部丢失）；现在逐条 8k 截断，每节都保留内容
        val bigStack = "at a.B.c(B.java:1)\n".repeat(2_500) // ≈50k 字符
        val content = ErrorReportComposer.buildStackAttachment(
            List(20) { "#${it + 1} marker\n$bigStack" },
            null, null, null, "t",
        )
        assertTrue("总量仍受限 len=${content.length}", content.length <= 60_000)
        assertEquals("20 节全部在场（此前尾部落入 take 之外被整体丢失）", 20, Regex("----- #\\d+ -----").findAll(content).count())
        assertEquals("每节都带截断尾注", 20, content.split("（已截断，原 ").size - 1)
        assertTrue("尾注带原长度", content.contains("（已截断，原 ${bigStack.length + "#1 marker\n".length} 字符）"))
    }

    @Test
    fun `单条超长路径行为不变_无尾注逐字节同旧`() {
        // 单条路径保持 60_000 上限（存量行为兼容）：超长时 take 终裁，suffix 落在截断线之外
        val bigStack = "at a.B.c(B.java:1)\n".repeat(5_000) // ≈95k 字符
        val content = ErrorReportComposer.buildStackAttachment(bigStack, null, null, null, "t")
        assertEquals("单条总量 = 60_000", 60_000, content.length)
        assertFalse("单条路径不出现尾注（suffix 被 take 切掉）", content.contains("已截断"))
    }

    @Test
    fun `小堆栈批量不受截断影响`() {
        val content = ErrorReportComposer.buildStackAttachment(
            listOf("java.lang.IllegalStateException: one\nat A.b(A.java:1)", "java.lang.Error: two\nat C.d(C.java:2)"),
            null, null, null, "t",
        )
        assertFalse("小堆栈不带尾注", content.contains("已截断"))
        assertTrue(content.contains("one"))
        assertTrue(content.contains("two"))
    }
}
