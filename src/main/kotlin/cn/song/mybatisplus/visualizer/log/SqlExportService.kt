package cn.song.mybatisplus.visualizer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R4（W3 历史工作流包）：SQL 导出服务——历史/日志面板行集导出为 CSV / TSV / 纯文本文件。
 *
 * 纯逻辑（格式化 + 写盘），不依赖平台 UI；面板侧负责行集收集（与当前过滤一致）与
 * 文件选择，写盘在后台线程（大集不卡 EDT，第四轮 R4 口径）。
 *
 * CSV：RFC 4180 口径——含逗号/引号/换行的字段双引号包裹、内部双引号加倍；首行表头。
 * TSV：制表符分隔，字段内 tab/换行替换为空格（TSV 无转义机制，防串列）。
 * TXT：人读形态——分隔线 + 时间/来源/耗时元信息 + 完整 SQL 原文（多行保形）。
 */
enum class SqlExportFormat(val ext: String) {
    CSV("csv"), TSV("tsv"), TXT("txt");
}

/** 导出行（面板行集 → 导出的中性投影）。 */
data class SqlExportRow(
    val time: String,
    val sql: String,
    val source: String,
    val durationMs: Long? = null,
)

object SqlExportService {

    /** 表头（CSV/TSV 共用列序；TXT 用分隔线形态）。 */
    private val HEADER = listOf("time", "source", "duration_ms", "sql")

    /** CSV 字段转义：含逗号/引号/换行/回车即包裹，内部双引号加倍。 */
    internal fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    /** 行集格式化（三格式分发）。 */
    fun format(rows: List<SqlExportRow>, format: SqlExportFormat): String = when (format) {
        SqlExportFormat.CSV -> formatCsv(rows)
        SqlExportFormat.TSV -> formatTsv(rows)
        SqlExportFormat.TXT -> formatTxt(rows)
    }

    /** 写盘（调用方保证后台线程）。 */
    fun exportToFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun formatCsv(rows: List<SqlExportRow>): String {
        val sb = StringBuilder(HEADER.joinToString(","))
        sb.append('\n')
        rows.forEach { r ->
            sb.append(csvField(r.time)).append(',')
                .append(csvField(r.source)).append(',')
                .append(csvField(r.durationMs?.toString() ?: "")).append(',')
                .append(csvField(r.sql))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatTsv(rows: List<SqlExportRow>): String {
        val sb = StringBuilder(HEADER.joinToString("\t"))
        sb.append('\n')
        rows.forEach { r ->
            val flatten = { v: String -> v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
            sb.append(flatten(r.time)).append('\t')
                .append(flatten(r.source)).append('\t')
                .append(flatten(r.durationMs?.toString() ?: "")).append('\t')
                .append(flatten(r.sql))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatTxt(rows: List<SqlExportRow>): String {
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            sb.append("-- === SQL #").append(i + 1).append(" ===\n")
            sb.append("-- time: ").append(r.time)
            if (r.source.isNotBlank()) sb.append(" | source: ").append(r.source)
            if (r.durationMs != null) sb.append(" | ").append(r.durationMs).append(" ms")
            sb.append('\n')
            sb.append(r.sql.trimEnd())
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /** 时间戳 → 导出用显示文本（毫秒精度，调用方口径统一）。 */
    fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(ts))
}
