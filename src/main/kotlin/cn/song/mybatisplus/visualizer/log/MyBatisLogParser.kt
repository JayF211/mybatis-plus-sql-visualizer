package cn.song.mybatisplus.visualizer.log

/**
 * MyBatis 控制台日志解析器（T-M31-01）：逐行喂入控制台输出，配对 `Preparing:` / `Parameters:` 行。
 *
 * 支持的日志形态（T-M31.14 起前缀可配置）：
 * - MP 前缀：`==>  Preparing: SELECT ...` / `==> Parameters: 1(Long), abc(String)`
 * - 原生：`Preparing: SELECT ...` / `Parameters: 1, abc(String)`
 * - 带日志框架前缀（时间戳/线程/类名）——[mixedPrefix]=true 时按关键字定位（默认，宽容匹配）；
 *   false 时仅行首精确前缀匹配（用户自定义前缀后开启）
 * - 无参语句：仅 `Preparing:` 行（无 Parameters），还原时不做替换
 *
 * 噪声行（`<== Columns:` / `<== Total:` / 业务日志等）自然忽略；
 * 一条语句可跟多条 Parameters 行（极端形态），参数顺序追加。
 * [maxParseLength] 超长截断（防控制台大输出卡顿）。
 */
class MyBatisLogParser(
    private val restorer: LogSqlRestorer = LogSqlRestorer(),

    /**
     * 配置项为 **var 可变**（T-xx 设置生效性审计）：此前为构造时固化（filter 挂载时快照）——
     * 捕捉中修改前缀/混合/最大长度对已挂载 console 不生效（读的是缓存旧值）；
     * 现由 [SqlLogConsoleBridge] 每次喂行前按 [ParserConfig] 重新同步，即时生效。
     * 默认值与构造参数保持兼容（既有测试按命名参数构造不受影响）。
     */
    var sqlPrefix: String = DEFAULT_SQL_PREFIX,
    var paramPrefix: String = DEFAULT_PARAM_PREFIX,
    var mixedPrefix: Boolean = true,
    var maxParseLength: Int = DEFAULT_MAX_PARSE_LENGTH,
) {

    private val entries = mutableListOf<LogSqlEntry>()
    private var pendingSql: String? = null
    private val pendingParams = mutableListOf<LogSqlParam>()
    /**
     * 前缀独占一行兼容（T-M31.xx）：`Preparing:` 行 remainder 为空时置 true，
     * 等待下一行作为 SQL。下一行非空非关键字行视为 SQL 文本。
     */
    private var prefixMatched: Boolean = false
    /**
     * Parameters 也独占一行（极端形态）：`Parameters:` 行 remainder 为空时置 true，
     * 等待下一行作为参数文本。
     */
    private var paramPrefixMatched: Boolean = false

    /** 喂入一行控制台输出（任意线程，内部无共享可变状态——解析器实例按 console 隔离）。 */
    fun feed(line: String) {
        // 分支 1：Preparing 行（有 SQL 同行）
        val sql = extractAfterKeyword(line, sqlPrefix)
        if (sql != null) {
            closePending() // 上一条未闭合先闭合（无 Parameters 或异常日志）
            pendingSql = sql.take(maxParseLength)
            pendingParams.clear()
            prefixMatched = false
            return
        }

        // 分支 2：Preparing 独占一行（remainder 为空）→ 进入等待 SQL 态
        if (!prefixMatched && isPrefixOnly(line, sqlPrefix)) {
            prefixMatched = true
            pendingParams.clear()
            return
        }

        // 分支 3：Parameters 独占一行（极端形态）→ 等待下一行参数文本
        if (!paramPrefixMatched && isPrefixOnly(line, paramPrefix)) {
            if (prefixMatched) {
                // 等待 SQL 中看到 Parameters（中间无 SQL 行）→ 放弃等待
                prefixMatched = false
                return
            }
            paramPrefixMatched = true
            return
        }
        if (paramPrefixMatched) {
            paramPrefixMatched = false
            if (line.isBlank()) return // 跳过空行
            if (pendingSql == null) return // 无 SQL 上下文，忽略
            pendingParams += parseParams(line.trim())
            closePending()
            return
        }

        // 分支 4：Parameters 行（有参数同行）——正常形态
        val paramsText = extractAfterKeyword(line, paramPrefix)
        if (paramsText != null) {
            prefixMatched = false // 清除等待态（罕见：Preparing 独占一行后紧跟正常 Parameters）
            if (pendingSql == null) return // 无上下文（未捕获到 Preparing），忽略
            pendingParams += parseParams(paramsText)
            // 立即闭合——MyBatis 单条语句 Parameters 行后参数完整，立即生成条目避免去重窗口时序问题；
            // 多 Parameters 行（极端）合并逻辑：若下一行仍是 Parameters，pendingParams 已被清空后重开。
            closePending()
            return
        }

        // 分支 5：等待 SQL 态 → 此行即 SQL
        if (prefixMatched) {
            if (line.isBlank()) return // 跳过空行
            pendingSql = line.trim().take(maxParseLength)
            prefixMatched = false
            return
        }

        // 分支 6：噪声行（现有逻辑不变）
    }

    /** 闭合当前未完成条目（新语句到来 / 面板刷新时调用）。 */
    fun closePending(): LogSqlEntry? {
        val sql = pendingSql ?: return null
        pendingSql = null
        val entry = LogSqlEntry(
            rawSql = sql,
            restoredSql = restorer.restore(sql, pendingParams),
            params = pendingParams.toList(),
            source = SqlSource.MYBATIS,
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
        if (keyword.isEmpty()) return null
        val idx = if (mixedPrefix) line.indexOf(keyword) else {
            // 严格模式：行首精确前缀（允许日志框架前缀在关键字前？——严格模式按用户定义的前缀整段匹配，
            // 即行以「前缀 + 空格/冒号」开头；此处实现为行首匹配关键字）
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return null
        val text = line.substring(idx + keyword.length).trim()
        return text.ifEmpty { null }
    }

    /**
     * 行仅含前缀关键字（remainder 为空/纯空白）——分行形态信号。
     * 与 [extractAfterKeyword] 使用相同的 mixedPrefix 判断路径，保证两种模式下行为一致。
     */
    private fun isPrefixOnly(line: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        val idx = if (mixedPrefix) line.indexOf(keyword) else {
            if (line.startsWith(keyword)) 0 else -1
        }
        if (idx < 0) return false
        return line.substring(idx + keyword.length).trim().isEmpty()
    }

    companion object {
        private const val DEFAULT_SQL_PREFIX = "Preparing:"
        private const val DEFAULT_PARAM_PREFIX = "Parameters:"
        private const val DEFAULT_MAX_PARSE_LENGTH = 20000
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
