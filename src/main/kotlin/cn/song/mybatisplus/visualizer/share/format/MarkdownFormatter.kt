package cn.song.mybatisplus.visualizer.share.format

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Markdown 格式生成器（F-8 Phase A）。
 *
 * 将预览 SQL + 参数 + 来源信息格式化为 Markdown 代码块，
 * 便于贴到 IM / MR 评论 / 文档中分享。
 *
 * 输出形态（参考开发计划 1.2）：
 * ```sql
 * SELECT id, name FROM user WHERE name = 'zhangsan'
 * ```
 * > 来源：UserService.java:42 | 方言：MySQL | 2026-08-22 15:30
 */
object MarkdownFormatter {

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 生成 Markdown 代码块（SQL + 元信息）。
     *
     * @param sql        要展示的 SQL（已格式化）
     * @param sources    参数来源列表（与 SQL 中 `?` 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param tableName  目标表名（可 null）
     * @param dialect    方言名称（可 null）
     * @param source     来源位置（文件名:行号，可 null）
     * @return Markdown 文本
     */
    fun format(
        sql: String,
        sources: List<String> = emptyList(),
        paramValues: Map<String, String> = emptyMap(),
        tableName: String? = null,
        dialect: String? = null,
        source: String? = null,
    ): String {
        val sb = StringBuilder(256)
        sb.append("```sql\n")
        sb.append(sql.trimEnd())
        sb.append("\n```")

        // 参数对照行
        if (sources.isNotEmpty()) {
            val filled = sources.joinToString(", ") { src ->
                val v = paramValues[src]
                if (v.isNullOrEmpty()) "$src=?" else "$src=$v"
            }
            sb.append("\n> 参数：`[$filled]`")
        }

        // 元信息行
        val meta = buildMeta(tableName, dialect, source)
        if (meta.isNotEmpty()) {
            sb.append("\n> ").append(meta.joinToString(" | "))
        }

        return sb.toString()
    }

    /**
     * 简化调用：无参数、无来源场景。
     */
    fun format(
        sql: String,
        tableName: String? = null,
        dialect: String? = null,
        source: String? = null,
    ): String = format(sql, emptyList(), emptyMap(), tableName, dialect, source)

    /**
     * 生成 Markdown 表格（SQL + 参数 + 来源三列对比）。
     *
     * @param sql        要展示的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param tableName  表名
     * @param dialect    方言
     * @param source     来源位置
     * @return Markdown 表格文本
     */
    fun formatAsTable(
        sql: String,
        sources: List<String> = emptyList(),
        paramValues: Map<String, String> = emptyMap(),
        tableName: String? = null,
        dialect: String? = null,
        source: String? = null,
    ): String {
        val sb = StringBuilder(256)
        val params = if (sources.isNotEmpty()) {
            sources.joinToString(", ") { src ->
                val v = paramValues[src]
                if (v.isNullOrEmpty()) "$src=?" else "$src=$v"
            }
        } else {
            "-"
        }
        val sourceText = source ?: "-"
        val tableText = tableName ?: "-"
        val dialectText = dialect ?: "-"
        val timeText = LocalDateTime.now().format(TIMESTAMP_FMT)

        sb.append("| 来源 | SQL | 参数 | 表 | 方言 | 时间 |\n")
        sb.append("| --- | --- | --- | --- | --- | --- |\n")
        sb.append("| ").append(escapeForMdTable(sourceText)).append(" | ")
        sb.append(escapeForMdTable(sql.trim())).append(" | ")
        sb.append(escapeForMdTable(params)).append(" | ")
        sb.append(escapeForMdTable(tableText)).append(" | ")
        sb.append(escapeForMdTable(dialectText)).append(" | ")
        sb.append(escapeForMdTable(timeText)).append(" |")

        return sb.toString()
    }

    /**
     * 转义 Markdown 表格单元格内容：
     * - 换行符 → [br]（保持可读性同时不破坏表格结构）
     * - ` → \`（避免提前关闭 code span）
     * - | → \|（避免被误认为列分隔符）
     * - \ → \\（优先处理，避免重复转义）
     */
    private fun escapeForMdTable(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")
    }

    /** 构建元信息片段列表（过滤 null/空）。 */
    private fun buildMeta(tableName: String?, dialect: String?, source: String?): List<String> {
        val meta = mutableListOf<String>()
        source?.let { meta.add("来源：$it") }
        tableName?.let { meta.add("表：$it") }
        dialect?.let { meta.add("方言：$it") }
        meta.add(LocalDateTime.now().format(TIMESTAMP_FMT))
        return meta
    }
}
