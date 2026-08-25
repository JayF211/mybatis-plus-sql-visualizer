package cn.song.mybatisplus.visualizer.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从 .idea/mybatis-plus-sql-visualizer/sql-history/ 文件反解析为 LogSqlEntry（F-44）。
 *
 * 支持两种格式：
 *   - 新版（F-44）：--- source: xxx | time: xxx | yyyy-MM-dd HH:mm:ss.SSS ---\nSQL\n
 *   - 旧版（F-43）：-- yyyy-MM-dd HH:mm:ss\nSQL\n
 *
 * 混合文件中两种格式共存时均可正确解析。
 */
object SqlLogFileReader {

    /** 新版格式头部：--- source: xxx | time: xxx | timestamp --- */
    private val NEW_HEADER_REGEX = Regex(
        """^--- source: (.+?) \| time: (.+?) \| (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{3})?) ---$"""
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
            // 检测新版头部
            if (line.startsWith("--- source:")) {
                flushBlock()
                currentLines.add(line)
            }
            // 检测旧版头部
            else if (line.startsWith("-- ") && !line.startsWith("---")) {
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
