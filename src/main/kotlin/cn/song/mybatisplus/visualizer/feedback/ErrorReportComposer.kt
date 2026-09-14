package cn.song.mybatisplus.visualizer.feedback

/**
 * 报告内容组装（T-ErrReport-04）：全部纯函数、平台无关（单测直接覆盖）。
 *
 * 标题/描述满足需求池服务端约束（title 5~200、description ≥10，见开发计划 §2.2 契约表）；
 * 分类映射为粗粒度启发式（按插件包名线索），管理员可在需求池后台改判。
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
    ): String = buildString {
        appendLine("SQL Visualizer 错误报告")
        appendLine("========================================")
        appendLine("时间: $occurredAt")
        appendLine("IDE: ${ideVersion ?: "unknown"}")
        appendLine("插件版本: ${pluginVersion ?: "unknown"}")
        appendLine("操作系统: ${System.getProperty("os.name") ?: "unknown"} ${System.getProperty("os.version") ?: ""}")
        appendLine("指纹: ${fingerprint ?: "none"}")
        appendLine("========================================")
        appendLine()
        append(stackTraceText?.take(60_000) ?: "(无堆栈)")
    }

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
