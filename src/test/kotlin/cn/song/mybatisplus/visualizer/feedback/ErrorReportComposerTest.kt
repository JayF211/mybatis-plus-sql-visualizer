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
    fun `预填默认脱敏_标题只出类名_描述出类名与栈帧摘要`() {
        val prefill = ErrorReportComposer.prefill("java.lang.RuntimeException", "boom happened at runtime", stack)
        // T-ErrorReportRedact（D1=B）：默认预填 = 类名 + 指纹 + 栈帧，message 一律不进
        assertEquals("RuntimeException", prefill.title)
        assertFalse("默认描述不得含 message 文本", prefill.description.contains("boom"))
        assertTrue(prefill.description.contains("异常: java.lang.RuntimeException"))
        assertTrue(
            "描述应带业务栈帧位置（含行号）",
            prefill.description.contains("cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh(SqlLogPanel.kt:120)"),
        )
        assertFalse("平台帧不进栈帧摘要", prefill.description.contains("com.intellij.openapi.application.ApplicationImpl"))
        assertEquals("sql-log", prefill.category)
        // message 全文与「勾选附加」的旧形态一并随 Prefill 交给对话框（不外传，只做回填源）
        assertEquals("boom happened at runtime", prefill.message)
        assertEquals("RuntimeException: boom happened at runtime", prefill.titleWithMessage)
        assertEquals("boom happened at runtime", prefill.descriptionWithMessage)
    }

    @Test
    fun `预填勾选附加形态与改前逐字节一致_默认形态不含消息`() {
        val prefill = ErrorReportComposer.prefill("java.lang.RuntimeException", "boom happened at runtime\n第二行", stack)
        // 组装式与 ErrorReportDialog.collectRequest 同款：buildDescription(null, 描述区文本, 指纹)
        val optInDescription = ErrorReportComposer.buildDescription(null, prefill.descriptionWithMessage, "abcd1234")
        assertEquals("RuntimeException: boom happened at runtime", prefill.titleWithMessage)
        assertEquals("boom happened at runtime\n第二行\n\nfingerprint: abcd1234", optInDescription)
        val defaultDescription = ErrorReportComposer.buildDescription(null, prefill.description, "abcd1234")
        assertFalse("默认形态不得含 message 文本", defaultDescription.contains("boom"))
        assertTrue(defaultDescription.endsWith("fingerprint: abcd1234"))
        assertTrue(defaultDescription.contains("（异常消息全文默认不附带"))
    }

    @Test
    fun `预填无消息时勾选行无回填源且描述不出说明行`() {
        val prefill = ErrorReportComposer.prefill("java.lang.RuntimeException", null, stack)
        assertEquals(null, prefill.message)
        assertEquals("RuntimeException", prefill.title)
        assertFalse("无消息就没有「未附带」说明", prefill.description.contains("默认不附带"))
        // 无消息的旧形态标题为 `(no message)` 兜底（勾选行在对话框里按 message==null 隐藏）
        assertEquals("RuntimeException: (no message)", prefill.titleWithMessage)
    }

    @Test
    fun `过短消息的旧形态描述仍补兜底句_与改前一致`() {
        // 服务端描述下限 10 字符：message 过短时旧形态（勾选附加）同样补兜底句——改前即如此，
        // 勾选路径必须逐字节保留（不能因为「脱敏改造」把这条兜底丢了）
        val prefill = ErrorReportComposer.prefill("java.lang.RuntimeException", "boom", stack)
        assertEquals("boom\n(from IDE error report)", prefill.descriptionWithMessage)
    }

    // ---------- 脱敏标题/描述（T-ErrorReportRedact D1=B） ----------

    @Test
    fun `脱敏标题只有类名且守住服务端上下限`() {
        assertEquals("RuntimeException", ErrorReportComposer.buildRedactedTitle("java.lang.RuntimeException"))
        assertEquals("PluginError", ErrorReportComposer.buildRedactedTitle(null))
        assertTrue("过短补兜底（服务端 5 字符下限）", ErrorReportComposer.buildRedactedTitle("a.B").length >= 5)
        assertEquals(
            "超长截断（服务端 200 上限）",
            200,
            ErrorReportComposer.buildRedactedTitle("a." + "X".repeat(500)).length,
        )
    }

    @Test
    fun `脱敏描述含cause链类名但不含任何消息文本`() {
        val driverStack = """
            java.sql.SQLException: Access denied for user 'root'@'localhost'
            	at com.mysql.cj.jdbc.exceptions.SQLError.createSQLException(SQLError.java:129)
            	at com.mysql.cj.jdbc.ConnectionImpl.createNewIO(ConnectionImpl.java:1)
            	at cn.song.mybatisplus.visualizer.execute.DataSourceSelector.connect(DataSourceSelector.kt:2624)
            	at com.intellij.openapi.application.ApplicationImpl.run(ApplicationImpl.java:1)
            Caused by: com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
            """.trimIndent()
        val description = ErrorReportComposer.buildRedactedDescription("java.sql.SQLException", driverStack, messageOmitted = true)
        assertTrue(description.contains("异常: java.sql.SQLException"))
        assertTrue(description.contains("原因链: com.mysql.cj.jdbc.exceptions.CommunicationsException"))
        assertTrue(description.contains("cn.song.mybatisplus.visualizer.execute.DataSourceSelector.connect(DataSourceSelector.kt:2624)"))
        // 帧与 cause 链都只取类名/位置——异常消息与 cause 消息一律不进描述
        assertFalse("异常消息不得进描述", description.contains("Access denied"))
        assertFalse("cause 行上的消息也不得进描述", description.contains("Communications link failure"))
        assertTrue(description.contains("（异常消息全文默认不附带"))
    }

    @Test
    fun `脱敏描述无堆栈时退化且满足服务端10字符下限`() {
        val description = ErrorReportComposer.buildRedactedDescription("java.lang.Error", null)
        assertEquals("异常: java.lang.Error", description)
        assertTrue(description.length >= ErrorReportComposer.DESCRIPTION_MIN)
        assertTrue(
            "类名极短时兜底句守住服务端下限",
            ErrorReportComposer.buildRedactedDescription("a", null).contains("from IDE error report"),
        )
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
        // 默认脱敏形态：首条只出类名
        assertEquals("Boom1（等 3 条异常）", ErrorReportComposer.buildBatchTitle(entries(3)))
        // 勾选附加形态与改前逐字节一致
        assertEquals("Boom1: boom 1（等 3 条异常）", ErrorReportComposer.buildBatchTitle(entries(3), includeMessages = true))
        // 超长首条也须 ≤200（服务端 400 拒绝线）
        val long = ErrorReportComposer.buildBatchTitle(
            listOf(ErrorReportComposer.BatchEntry("a.B", "x".repeat(500), null)) + entries(2),
        )
        assertTrue("len=${long.length}", long.length <= ErrorReportComposer.TITLE_MAX)
        assertTrue(long.endsWith("（等 3 条异常）"))
    }

    @Test
    fun `合并描述默认只出类名_勾选附加后与改前逐字节一致`() {
        val redacted = ErrorReportComposer.buildBatchDescription(entries(3), "顺便说一句")
        assertTrue(redacted.contains("共 3 条"))
        assertTrue(redacted.contains("#1 Boom1"))
        assertTrue(redacted.contains("#3 Boom3"))
        assertFalse("默认不得带逐条消息文本", redacted.contains("#1 Boom1: boom 1"))
        assertTrue(redacted.contains("（逐条异常消息默认不附带"))
        assertTrue("用户主动写的补充始终带上", redacted.contains("顺便说一句"))
        // 勾选附加 = 改前形态（逐字节钉住，含空行与顺序）
        assertEquals(
            "（来自 IDE 的错误批次报告，共 3 条）\n" +
                "#1 Boom1: boom 1\n#2 Boom2: boom 2\n#3 Boom3: boom 3\n\n顺便说一句",
            ErrorReportComposer.buildBatchDescription(entries(3), "顺便说一句", includeMessages = true),
        )
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
