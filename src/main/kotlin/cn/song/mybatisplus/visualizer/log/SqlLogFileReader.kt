package cn.song.mybatisplus.visualizer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从 .idea/sql-visualizer/sql-history/ 文件反解析为 LogSqlEntry（F-44；F-44-4 目录随插件更名）。
 *
 * 支持两种格式：
 *   - 新版（F-44-2）：-- source: xxx | time: xxx | yyyy-MM-dd HH:mm:ss.SSS --\nSQL\n
 *     （起结均为两个 `-`，标准 SQL 单行注释）
 *   - 历史 F-44（已停止生产）：--- source: xxx | time: xxx | timestamp ---\nSQL\n
 *     （三个 `-`，与新格式含义相同，仅写法差异——Reader 兼容读取，以防历史文件被误判）
 *   - 旧版（F-43）：-- yyyy-MM-dd HH:mm:ss\nSQL\n
 *
 * 混合文件中三种格式共存时均可正确解析。
 */
object SqlLogFileReader {

    /**
     * 新版格式头部：兼容 2 或 3 个 `-` 起结（前缀与后缀的 `-` 数量独立，长度匹配即可）。
     * 捕获组：1=source 2=time 3=timestamp
     */
    private val NEW_HEADER_REGEX = Regex(
        """^--+ source: (.+?) \| time: (.+?) \| (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{3})?) --+$"""
    )

    /** 旧版格式头部：-- timestamp（无源码位置和耗时） */
    private val OLD_HEADER_REGEX = Regex(
        """^-- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{3})?)$"""
    )

    /**
     * 读取目录下所有 .sql 文件，返回按时间戳排序的 LogSqlEntry 列表。
     */
    fun readAll(dir: String): List<LogSqlEntry> {
        val directory = File(dir)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { f -> f.extension == "sql" }
            ?.flatMap { readFromFile(it) }
            ?.sortedBy { it.timestamp }
            ?: emptyList()
    }

    fun readFromFile(file: File): List<LogSqlEntry> {
        if (!file.exists()) return emptyList()
        return parseContent(file.readText())
    }

    /**
     * 快速统计文件中的 SQL 条目数（F-44-3 两级导航一级列表用）：
     * 只数头部行（`-- source: ...` / `--- source: ...` / `-- yyyy-MM-dd ...`），
     * 不构建 [LogSqlEntry]——大文件文件列表加载时避免无谓的对象分配。
     * 判定规则与 [parseContent] 保持一致（含 `source:` 与 `time:` 关键字的 `--` 行为新版头部，
     * `-- 日期` 为旧版头部），因此计数与 [readFromFile] 结果一致。
     */
    fun countInFile(file: File): Int {
        if (!file.exists()) return 0
        var count = 0
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                    count++
                } else if (line.startsWith("-- ") && !line.contains("source:")) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * 解析文件内容为 LogSqlEntry 列表。
     * 逐行扫描，遇到头部行则开始新块，兼容新旧两种头部混合。
     */
    fun parseContent(content: String): List<LogSqlEntry> {
        val entries = mutableListOf<LogSqlEntry>()
        val currentLines = mutableListOf<String>()

        fun flushBlock() {
            if (currentLines.isEmpty()) return
            val block = currentLines.joinToString("\n")
            parseBlock(block)?.let { entries.add(it) }
            currentLines.clear()
        }

        for (line in content.lines()) {
            // 检测新版头部（兼容 2/3 个 `-`：-- source 或 --- source）
            // 此处不能用 startsWith 严格判别「-- source」——旧版 F-43 的「-- 2026-08-31 ...」也会匹配，
            // 故先看是否为新版结构（含 "source:" 与 "time:" 关键字）——具体交由正则捕获 timestamp/format ，
            // parseBlock 中先 NEW 后 OLD 顺序尝试，任一正则匹配即可。
            val trimmed = line.trimStart()
            if (trimmed.startsWith("--") && trimmed.contains("source:") && trimmed.contains("time:")) {
                flushBlock()
                currentLines.add(line)
            }
            // 检测旧版头部（F-43：`-- yyyy-MM-dd HH:mm:ss`）
            else if (line.startsWith("-- ") && !line.contains("source:")) {
                flushBlock()
                currentLines.add(line)
            }
            // 非头部行（SQL 正文或空行）
            else {
                currentLines.add(line)
            }
        }
        flushBlock()
        return entries
    }

    private fun parseBlock(block: String): LogSqlEntry? {
        val lines = block.lines()
        if (lines.isEmpty()) return null
        val header = lines.first()
        val sql = lines.drop(1).joinToString("\n").trim()
        if (sql.isEmpty()) return null

        // 尝试新版格式
        NEW_HEADER_REGEX.find(header)?.let { mr ->
            val sourceStr = mr.groupValues[1]
            val timeStr = mr.groupValues[2]
            val timestampStr = mr.groupValues[3]
            val timestamp = parseTimestampNew(timestampStr) ?: return null
            val (sourceFile, sourceLine) = parseSource(sourceStr)
            val executionTimeMs = parseDuration(timeStr)
            return LogSqlEntry(
                rawSql = sql,
                restoredSql = sql,
                params = emptyList(),
                timestamp = timestamp,
                sourceFile = sourceFile,
                sourceLine = sourceLine,
                executionTimeMs = executionTimeMs,
            )
        }

        // 尝试旧版格式
        OLD_HEADER_REGEX.find(header)?.let { mr ->
            val timestampStr = mr.groupValues[1]
            val timestamp = parseTimestampOld(timestampStr) ?: return null
            return LogSqlEntry(
                rawSql = sql,
                restoredSql = sql,
                params = emptyList(),
                timestamp = timestamp,
            )
        }

        return null
    }

    private fun parseSource(sourceStr: String): Pair<String?, Int> {
        if (sourceStr == "unknown") return null to 0
        // 分离文件路径和行号：path/to/File.java:42 → (path/to/File.java, 42)
        val colonIdx = sourceStr.lastIndexOf(':')
        return if (colonIdx > 0) {
            val file = sourceStr.substring(0, colonIdx)
            val line = sourceStr.substring(colonIdx + 1).toIntOrNull() ?: 0
            file to line
        } else {
            sourceStr to 0
        }
    }

    private fun parseDuration(timeStr: String): Long? {
        if (timeStr == "—" || timeStr == "-" || timeStr.isBlank()) return null
        return timeStr.removeSuffix("ms").toLongOrNull()
    }

    private fun parseTimestampNew(ts: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).parse(ts)?.time
    } catch (_: Exception) { null }

    private fun parseTimestampOld(ts: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).parse(ts)?.time
    } catch (_: Exception) { null }
}
