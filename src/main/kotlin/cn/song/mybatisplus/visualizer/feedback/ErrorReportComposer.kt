package cn.song.mybatisplus.visualizer.feedback

/**
 * 报告内容组装（T-ErrReport-04）：全部纯函数、平台无关（单测直接覆盖）。
 *
 * 标题/描述满足需求池服务端约束（title 5~200、description ≥10，见开发计划 §2.2 契约表）；
 * 分类映射为粗粒度启发式（按插件包名线索），管理员可在需求池后台改判。
 *
 * T-FeedbackBatch（2026-09-14 用户定案「不能被轻易限流」）：平台错误列表可多选，用户常一次报一堆——
 * 服务端限流是 5 次/小时/IP，逐条提交必然 429。策略：多条合并成**一条**帖子（描述出清单、附件收全文，
 * 见 [buildBatchTitle]/[buildBatchDescription]/[buildStackAttachment]），把 N 次请求压成 1 次。
 */
object ErrorReportComposer {

    const val TITLE_MAX = 200
    /** 下限供对话框客户端校验复用（T-FeedbackDlg-01，单一事实源）。 */
    internal const val TITLE_MIN = 5
    internal const val DESCRIPTION_MIN = 10

    /** 需求池反馈类型：1功能需求 / 2Bug / 3体验优化 / 4疑问咨询（sql-visualizer-admin constants）。 */
    const val TYPE_BUG = 2
    const val TYPE_QUESTION = 4

    /** 对话框预填（错误形态；手动形态各字段为空由对话框自行留白）。 */
    data class Prefill(
        val title: String,
        val description: String,
        val category: String,
    )

    /** 从异常信息组装预填（title = 异常类简单名: message 首行，截断到 200）。 */
    fun prefill(throwableClassName: String?, message: String?, stackTraceText: String?): Prefill = Prefill(
        title = buildTitle(throwableClassName, message),
        description = buildDescription(message, userNotes = null, fingerprint = null),
        category = guessCategory(stackTraceText),
    )

    /** 标题规则：`异常类简单名: message 首行`；超 200 截断、不足 5 补后缀兜底。 */
    fun buildTitle(throwableClassName: String?, message: String?): String {
        val simpleName = throwableClassName?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "PluginError"
        val firstLine = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val raw = if (firstLine.isBlank()) "$simpleName: (no message)" else "$simpleName: $firstLine"
        val truncated = if (raw.length > TITLE_MAX) raw.take(TITLE_MAX) else raw
        return if (truncated.length < TITLE_MIN) "$truncated (SQL Visualizer)" else truncated
    }

    /** 描述组装：message 全文 + 用户补充 + 指纹行；不足 10 字符补兜底句（服务端 400 拒绝线）。 */
    fun buildDescription(message: String?, userNotes: String?, fingerprint: String?): String {
        val parts = buildList {
            val msg = message?.trim().takeUnless { it.isNullOrBlank() }
            if (msg != null) add(msg)
            val notes = userNotes?.trim().takeUnless { it.isNullOrBlank() }
            if (notes != null) add(notes)
            if (!fingerprint.isNullOrBlank()) add("fingerprint: $fingerprint")
        }
        val text = parts.joinToString("\n\n").trim()
        return if (text.length < DESCRIPTION_MIN) "$text\n(from IDE error report)" else text
    }

    /** 分类映射：堆栈/消息中的插件包名线索 → 需求池 category slug；兜底 other（特定规则先于 wrapper）。 */
    fun guessCategory(stackTraceText: String?): String {
        val text = stackTraceText ?: return CATEGORY_OTHER
        return when {
            text.contains("visualizer.log") || text.contains("JdbcTemplateLogParser") -> CATEGORY_SQL_LOG
            text.contains("BatchOperationConverter") || text.contains("visualizer.batch") -> CATEGORY_BATCH
            text.contains("SqlReverseParser") || text.contains("ReverseConverter") -> CATEGORY_SQL_TO_JAVA
            text.contains("visualizer.completion") || text.contains("CompletionContrib") -> CATEGORY_COMPLETION
            text.contains("visualizer.xml") || text.contains("XmlPreview") -> CATEGORY_XML
            text.contains("visualizer.annotation") -> CATEGORY_ANNOTATION
            text.contains("visualizer.converter") || text.contains("Wrapper") -> CATEGORY_WRAPPER
            else -> CATEGORY_OTHER
        }
    }

    /**
     * 堆栈附件 txt 内容：环境头 + 异常全文（服务端附件白名单允许 .txt，≤5MB——
     * 超长堆栈截断到 60_000 字符，正常堆栈远小于此）。
     */
    fun buildStackAttachment(
        stackTraceText: String?,
        ideVersion: String?,
        pluginVersion: String?,
        fingerprint: String?,
        occurredAt: String,
    ): String = buildStackAttachment(listOf(stackTraceText ?: "(无堆栈)"), ideVersion, pluginVersion, fingerprint, occurredAt)

    /**
     * 多事件堆栈附件（T-FeedbackBatch）：平台在对话框里可多选错误，勾选几条就带几条——
     * 每条独立分节（`#N 异常类: 消息` 标题 + 全文），空/空白条目剔除；
     * 全部为空时退化为单节 `(无堆栈)`。总量仍截断到 60_000 字符。
     */
    fun buildStackAttachment(
        stackTraces: List<String>,
        ideVersion: String?,
        pluginVersion: String?,
        fingerprint: String?,
        occurredAt: String,
    ): String = buildString {
        // T-FeedbackErr 真机第三轮：UTF-8 BOM 让附件自描述编码——R2 元数据里的 MIME 不带 charset，
        // 消费方若忽略响应头（浏览器按本地编码猜、编辑器按系统默认）会把中文堆栈显示成乱码。
        // 真机乱码根因在服务端响应头（已同步补 charset=utf-8），BOM 是文本文件的标准自描述兜底
        append('\uFEFF')
        appendLine("SQL Visualizer 错误报告")
        appendLine("========================================")
        appendLine("时间: $occurredAt")
        appendLine("IDE: ${ideVersion ?: "unknown"}")
        appendLine("插件版本: ${pluginVersion ?: "unknown"}")
        appendLine("操作系统: ${System.getProperty("os.name") ?: "unknown"} ${System.getProperty("os.version") ?: ""}")
        appendLine("指纹: ${fingerprint ?: "none"}")
        appendLine("异常条数: ${stackTraces.count { it.isNotBlank() }.coerceAtLeast(1)}")
        appendLine("========================================")
        appendLine()
        val meaningful = stackTraces.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) {
            append("(无堆栈)")
            return@buildString
        }
        meaningful.forEachIndexed { index, stack ->
            if (index > 0) appendLine().appendLine()
            appendLine("----- #${index + 1} -----")
            // T-PerfSweep2-03：append 前逐条截断——此前全文 append 后才 take(60_000)，
            // 组装瞬时分配与所选堆栈总量成正比（20 条 × 大堆栈病态场景在 EDT 瞬时数十 MB
            // 字符操作，弹窗打开有可感卡顿窗）。单条路径保持 [ATTACHMENT_MAX_CHARS]（存量
            // 行为 byte 级兼容）；批量按条数均分上限（半幅均分，留足分节头与尾注余量）——
            // 瞬时上界 ~30KB 且 20 节级批次不再被终裁整节丢弃（固定 8k/条 × 20 = 160k 仍会
            // 触发 take 丢节，均分方案同时修复内存面与丢节面）；末尾 take 保留为最后防线。
            val limit = if (meaningful.size == 1) {
                ATTACHMENT_MAX_CHARS
            } else {
                maxOf(ATTACHMENT_MAX_CHARS / (meaningful.size * 2), PER_STACK_MIN_CHARS)
            }
            append(if (stack.length > limit) stack.take(limit) + "（已截断，原 ${stack.length} 字符）" else stack)
            if (!stack.endsWith("\n")) appendLine()
        }
    }.take(ATTACHMENT_MAX_CHARS)

    /** 单条附件字符上限（原 60_000 字面量收编为常量，单/批两路径共用最后防线）。 */
    private const val ATTACHMENT_MAX_CHARS = 60_000

    /** T-PerfSweep2-03：批量路径每条堆栈下限——条数极多时每节仍保 1k（≈15 帧堆栈）诊断上下文。 */
    private const val PER_STACK_MIN_CHARS = 1_000

    // ---------- 多事件批量报告（T-FeedbackBatch） ----------

    /** 批量报告条目：一条异常事件 → 一节内容。 */
    data class BatchEntry(val title: String, val message: String?, val stackTraceText: String?)

    /** 批量合并阈值：≤ 此条数合成一条帖子（服务端 5 次/小时/IP，逐条报必撞 429）。 */
    const val BATCH_MERGE_MAX = 5

    /** 单条合并帖子的异常数上限（超出截断并注明）。 */
    const val BATCH_MAX_ITEMS = 20

    /**
     * 合并帖标题（纯函数，≤200）：首条异常摘要 + `等 N 条异常`，让管理员一眼看出是批次报告。
     */
    fun buildBatchTitle(entries: List<BatchEntry>): String {
        val first = entries.firstOrNull()
        val head = buildTitle(first?.title, first?.message)
        if (entries.size <= 1) return head
        val suffix = "（等 ${entries.size} 条异常）"
        val room = TITLE_MAX - suffix.length
        val truncated = if (head.length > room) head.take(room) else head
        return if (truncated.length < TITLE_MIN) "$truncated$suffix (SQL Visualizer)" else "$truncated$suffix"
    }

    /**
     * 合并帖描述（纯函数）：总条数说明 + 每条 `#N [异常类] 消息` 清单（超出 [BATCH_MAX_ITEMS] 截断并注明），
     * 保证 ≥ [DESCRIPTION_MIN]（服务端 400 拒绝线）。
     */
    fun buildBatchDescription(entries: List<BatchEntry>, userNotes: String?): String {
        val total = entries.size
        val shown = entries.take(BATCH_MAX_ITEMS)
        val text = buildString {
            appendLine("（来自 IDE 的错误批次报告，共 $total 条）")
            shown.forEachIndexed { index, entry ->
                val type = entry.title.substringAfterLast('.').ifBlank { "PluginError" }
                val firstLine = entry.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                appendLine("#${index + 1} $type${if (firstLine.isBlank()) "" else ": $firstLine"}")
            }
            if (total > shown.size) appendLine("（其余 ${total - shown.size} 条见附件）")
            userNotes?.trim()?.takeUnless { it.isBlank() }?.let { appendLine(); appendLine(it) }
        }.trim()
        return if (text.length < DESCRIPTION_MIN) "$text\n(from IDE error report)" else text
    }

    /**
     * 是否走合并帖（纯函数）：条数 > 1 且 ≤ [BATCH_MERGE_MAX] 时合并成一条帖子，
     * 把 N 次请求压成 1 次——限流桶是「5 次/小时/IP」，逐条提交等于把用户的额度一次烧光。
     * 条数更多时另走「先传附件、再补发」两步（见 [FeedbackApi.scheduleFollowUp]）。
     */
    fun shouldMerge(entryCount: Int): Boolean = entryCount in 2..BATCH_MERGE_MAX

    /** 需求池分类 slug（与 sql-visualizer-admin Categories 一致）。 */
    const val CATEGORY_WRAPPER = "wrapper"
    const val CATEGORY_XML = "xml"
    const val CATEGORY_ANNOTATION = "annotation"
    const val CATEGORY_SQL_TO_JAVA = "sql-to-java"
    const val CATEGORY_SQL_LOG = "sql-log"
    const val CATEGORY_BATCH = "batch"
    const val CATEGORY_COMPLETION = "completion"
    const val CATEGORY_OTHER = "other"
}
