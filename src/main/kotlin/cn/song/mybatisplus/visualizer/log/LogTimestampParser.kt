package cn.song.mybatisplus.visualizer.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * 日志时间戳解析器：从日志行中提取原始时间戳。
 *
 * 支持多种常见日志框架的时间格式（Logback/Log4j2/Spring Boot 默认、ISO 8601 等），
 * 解析失败返回 null（不影响现有捕获流程）。
 *
 * 线程安全：每次调用创建新的 SimpleDateFormat 实例（与 formatTimestampFragment 同范式）。
 */
internal object LogTimestampParser {

    /** 预定义时间格式：正则 + 对应 SimpleDateFormat pattern。 */
    private val FORMATS: List<Pair<Pattern, String>> = listOf(
        // Logback/Log4j2/Spring Boot 默认：2024-01-15 10:30:45.123
        Pair(Pattern.compile("""^\s*(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}\.\d{3})"""), "yyyy-MM-dd HH:mm:ss.SSS"),
        // 无毫秒：2024-01-15 10:30:45
        Pair(Pattern.compile("""^\s*(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})"""), "yyyy-MM-dd HH:mm:ss"),
        // ISO 8601 带时区：2024-01-15T10:30:45.123+0800
        Pair(Pattern.compile("""^\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4})"""), "yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
        // ISO 8601 带冒号时区：2024-01-15T10:30:45.123+08:00
        Pair(Pattern.compile("""^\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2})"""), "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
        // Nginx 风格：15/Jan/2024:10:30:45 +0800
        Pair(Pattern.compile("""^\s*(\d{2}/\w{3}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4})"""), "dd/MMM/yyyy:HH:mm:ss Z")
    )

    /** 日志级别关键词（用于非行首位置匹配）。 */
    private val LEVEL_KEYWORDS = listOf("DEBUG", "INFO", "TRACE", "ERROR", "WARN")

    /**
     * 从日志行中解析时间戳。
     *
     * @param line 日志行文本
     * @return 解析出的时间戳（毫秒），未解析到返回 null
     */
    fun parse(line: String): Long? {
        if (line.isBlank()) return null

        // 策略 1：行首匹配
        for ((pattern, format) in FORMATS) {
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                val ts = parseTimestamp(matcher.group(1), format)
                if (ts != null) return ts
            }
        }

        // 策略 2：在 level 关键词之前匹配
        for (level in LEVEL_KEYWORDS) {
            val levelIdx = line.indexOf(level)
            if (levelIdx > 0) {
                val beforeLevel = line.substring(0, levelIdx)
                for ((pattern, format) in FORMATS) {
                    val matcher = pattern.matcher(beforeLevel)
                    if (matcher.find()) {
                        val ts = parseTimestamp(matcher.group(1), format)
                        if (ts != null) return ts
                    }
                }
            }
        }

        return null
    }

    /**
     * T-PS5-12：SimpleDateFormat 非线程安全 → ThreadLocal + 格式串键缓存
     * （照 `SqlLogPanel.timestampFormatter` 惯例；此前每命中一行新建一个 SDF，是解析热路径的固定开销）。
     * 复用实例输出与逐次新建逐字节一致（`parse` 每次完整重置内部 calendar）；
     * 时区仍每次对齐（系统默认时区运行期可变，赋值 O(1)）。
     */
    private val timestampFormatCache =
        ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

    /** 用指定格式解析时间字符串。 */
    private fun parseTimestamp(text: String, format: String): Long? {
        return try {
            val sdf = timestampFormatCache.get().getOrPut(format) {
                SimpleDateFormat(format, Locale.ENGLISH).apply {
                    // 严格模式：拒绝月份 13 / 小时 25 等非法值（宽松模式会静默滚动成有效日期）
                    isLenient = false
                }
            }
            // 使用系统默认时区（与日志框架输出时区一致）
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(text)?.time
        } catch (_: Exception) {
            null
        }
    }
}
