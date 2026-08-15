package cn.song.mybatisplus.visualizer.log

/**
 * MyBatis 控制台日志解析器（T-M31-01）：逐行喂入控制台输出，配对 `Preparing:` / `Parameters:` 行。
 *
 * 支持的日志形态（自动识别，不做格式配置）：
 * - MP 前缀：`==>  Preparing: SELECT ...` / `==> Parameters: 1(Long), abc(String)`
 * - 原生：`Preparing: SELECT ...` / `Parameters: 1, abc(String)`
 * - 带日志框架前缀（时间戳/线程/类名）——按关键字 `Preparing:` / `Parameters:` 定位，不要求行首锚定
 * - 无参语句：仅 `Preparing:` 行（无 Parameters），还原时不做替换
 *
 * 噪声行（`<== Columns:` / `<== Total:` / 业务日志等）自然忽略；
 * 一条语句可跟多条 Parameters 行（极端形态），参数顺序追加。
 */
class MyBatisLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),
) {

    private val entries = mutableListOf<LogSqlEntry>()
    private var pendingSql: String? = null
    private val pendingParams = mutableListOf<LogSqlParam>()

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        val sql = extractAfterKeyword(line, KEY_PREPARING)
        if (sql != null) {
            closePending() // 上一条未闭合（无 Parameters 或异常日志）先闭合
            pendingSql = sql
            pendingParams.clear()
            return
        }
        val paramsText = extractAfterKeyword(line, KEY_PARAMETERS)
        if (paramsText != null) {
            if (pendingSql == null) return // 无上下文（未捕获到 Preparing），忽略
            pendingParams += parseParams(paramsText)
        }
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    fun closePending(): LogSqlEntry? {
        val sql = pendingSql ?: return null
        pendingSql = null
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, pendingParams),
            params = pendingParams.toList(),
        )
        entries += entry
        pendingParams.clear()
        return entry
    }

    /** 取出全部已闭合条目并清空缓冲区（面板刷新用）。 */
    fun takeEntries(): List<LogSqlEntry> {
        val result = entries.toList()
        entries.clear()
        return result
    }

    private fun extractAfterKeyword(line: String, keyword: String): String? {
        val idx = line.indexOf(keyword)
        if (idx < 0) return null
        val text = line.substring(idx + keyword.length).trim()
        return text.ifEmpty { null }
    }

    companion object {
        private const val KEY_PREPARING = "Preparing:"
        private const val KEY_PARAMETERS = "Parameters:"
    }
}

/**
 * 解析 `Parameters:` 行参数文本（T-M31-02 依赖）：`1(Long), abc(String), null` →
 * [(1, Long), (abc, String), (null, null)]。
 *
 * 非贪婪正则 + 右边界断言：`值(类型)` 值可含逗号/括号（`a, b(String)`、`a(x)(String)`）；
 * `null` 字面量无类型标注（须先于一般分支匹配，防 `null, 1(Integer)` 被吞成 `null, 1`）。
 * 全无 `(类型)` 标注时（旧版/第三方日志）回落逗号粗分，类型 null（还原器启发式判定引号）。
 */
internal fun parseParams(text: String): List<LogSqlParam> {
    val result = mutableListOf<LogSqlParam>()
    for (match in PARAM_REGEX.findAll(text)) {
        val nullLiteral = match.groups[1]?.value
        if (nullLiteral != null) {
            result += LogSqlParam(null, null)
            continue
        }
        result += LogSqlParam(match.groups[2]?.value?.trim(), match.groups[3]?.value)
    }
    if (result.isEmpty() && text.isNotBlank()) {
        result += text.split(',').map { LogSqlParam(it.trim().ifEmpty { null }, null) }
    }
    return result
}

/** `null` 分支在前（见 [parseParams] KDoc）；`\A`/`\z` 锚定避免跨参数误配。 */
private val PARAM_REGEX = Regex(
    """(?:\A|,\s*)(null)(?=,\s*|\z)|(?:\A|,\s*)(.+?)\(([A-Za-z_$][\w$]*(?:\[\])*)\)(?=,\s*|\z)""",
)
