package cn.song.mybatisplus.visualizer.log

/**
 * SQL 日志条目来源（区分不同 ORM 框架的捕获结果）。
 */
enum class SqlSource {
    /** MyBatis-Plus 控制台日志（`Preparing:` / `Parameters:` 格式）。 */
    MYBATIS,
    /** Hibernate/JPA 控制台日志（`Hibernate:` / `binding parameter` 格式）。 */
    HIBERNATE,
    /** Spring JdbcTemplate 控制台日志（`Executing prepared SQL statement [SQL]` / `Setting SQL statement parameter value: ...` 格式）。 */
    JDBC,
    /** agent 字节码通道（M56 增强捕获）：应用 JVM 内 JDBC 执行点 hook 直取的结构化事件。 */
    AGENT,
    /** 未知来源（兼容旧版本条目）。 */
    UNKNOWN,
}

/**
 * 日志 SQL 条目模型（T-M31）：一条 SQL 语句 + 其参数列表。
 *
 * 来源：MyBatis 控制台日志（MP 前缀 `==>  Preparing:` / 原生 `Preparing:`）或
 * Hibernate 控制台日志（`Hibernate:` / `binding parameter`），
 * 经解析器配对、[LogSqlRestorer] 还原后进入工具窗口面板。
 */
data class LogSqlEntry(
    /** 原始 SQL 文本（未替换占位符）。 */
    val rawSql: String,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 `?` 保留）。 */
    val restoredSql: String,
    /** 参数列表（与 SQL 中 `?` 按序对应）。 */
    val params: List<LogSqlParam>,
    /** 捕获时间戳。 */
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 摘要（列表展示用，智能截断：在第一行末尾或 MAX_SUMMARY_LENGTH 处取较小值，
     * 避免 mid-line 截断导致 multiline SQL 列表展示不完整）。
     */
    val summary: String = run {
        val newlineIdx = restoredSql.indexOf('\n')
        val endIdx = when {
            newlineIdx in 0 until MAX_SUMMARY_LENGTH -> newlineIdx  // 在第一行末尾截断
            else -> minOf(restoredSql.length, MAX_SUMMARY_LENGTH)
        }
        if (endIdx < restoredSql.length) restoredSql.substring(0, endIdx) + "…" else restoredSql
    },
    /** 条目来源（MyBatis / Hibernate / 未知）。 */
    val source: SqlSource = SqlSource.UNKNOWN,
    /**
     * 执行耗时（毫秒）；null 表示未解析到耗时信息。
     * 由 [ExecutionTimeParser] 从日志文本解析后填入（O-1 Phase B）。
     */
    val executionTimeMs: Long? = null,
    // ---------- F-44：源码位置 ----------
    /**
     * 产生该 SQL 的源文件路径（相对项目根目录，如 "src/main/java/com/example/mapper/UserMapper.java"）。
     * null 表示未知（旧版 F-43 保存的条目无此信息）。
     */
    val sourceFile: String? = null,
    /**
     * 产生该 SQL 的源码行号（0 表示未知）。
     */
    val sourceLine: Int = 0,
    /**
     * T-LogRowNumber：会话内捕获序号（「SQL 日志」列表行号）。由 [SqlLogEntryBuffer.addEntry]
     * 成功入队时分配（单调递增；去重丢弃/暂停/过滤关键字命中的条目不占号），
     * 项目启动（processStarted）或手动清空列表时归零。0 表示无行号
     * （历史文件读回、日志转 SQL round-trip 产出的条目）。
     */
    val sequenceNumber: Long = 0L,
    // ---------- M56 T-M56-00：agent 通道结构化字段（桥通道经标记行第 4/5 段与 pd 载荷扩展部分承接） ----------
    /**
     * mapper 语句 id（如 `com.example.mapper.UserMapper.selectById`）。
     * agent 通道从 MyBatis Executor 直取；桥通道来自源码标记第 4 段（拦截器 `ms.getId()`）。
     * null 表示未知（旧条目 / 非 MyBatis 来源）。
     */
    val mapperStatement: String? = null,
    /**
     * 执行报错（结构化直取：异常类名 + 消息，堆栈截断）；null 表示执行成功。
     * agent 通道在执行点直捕；桥通道暂无对应日志形态（T-M56-05 前保持 null）。
     */
    val errorText: String? = null,
    /**
     * 请求级聚合标签（T-M56-07 请求聚合预留）：同一次请求的多条 SQL 共享同值，
     * 聚焦页签据此分组。桥通道来自源码标记第 5 段（拦截器 MDC 兜底）；null 表示无标签。
     */
    val requestTag: String? = null,
    /**
     * 影响行数（agent 通道 `getUpdateCount` 直取，DML 语义）；null 表示未知（查询 / 旧条目）。
     */
    val updateCount: Long? = null,
) {
    /**
     * T-CrudColor：条目语句类型（渲染热路径缓存，惰性计算一次）。
     *
     * 由 [LogStatementClassifier.classify] 得出——与类型过滤弹窗**同一口径**，
     * 因此「筛出来的 SELECT 行不会被标成红色」这一自洽性天然成立。
     *
     * 之所以缓存而非每次现算：[LogStatementClassifier.classify] 内部 `Regex("\\s+")`
     * **每次调用都重新编译 Pattern**，渲染路径按 cell 调用会把该开销放大到
     * 「每次重绘 × 可见行数」（滚动/悬停/选中/resize 均触发重绘）。
     *
     * 与 [dedupeKey] 同属 body 内属性——**不参与 data class 的 equals / hashCode / copy**，
     * 故新增该字段不影响既有去重与集合语义（T-LogRowNumber 的 `sequenceNumber` 教训：
     * 构造函数内新增字段会改变 equals，此处不会）。
     */
    val statementType: StatementType by lazy { LogStatementClassifier.classify(restoredSql) }

    /**
     * T-Perf-19（自 dev 移植）：去重键指纹（[sqlFingerprint] 惰性计算一次）——dedupeCounts 不再以
     * 完整 SQL 原文为键（还原参数文本可达数 KB/条，长会话数十~上百 MB 永久驻留）。
     * T-LogDedupeMpMark：指纹前先 [normalizeSqlWhitespace]——同一语句的双路日志副本（named 行 trim 过 /
     * prepared 行尾随空白原样保留）仅差首尾空白，原文指纹判重永不命中。
     */
    val dedupeKey: String by lazy { sqlFingerprint(normalizeSqlWhitespace(restoredSql)) }

    // ---------- T-ListMode：多行显示模式行片段缓存（体属性不入 equals/copy；仅 EDT 渲染路径读写） ----------

    /**
     * 多行模式（KEYWORD_WRAP/FORMATTED）的行片段缓存：可变行高下每次布局状态更新会对全部条目
     * 逐条调渲染器取首选尺寸（T-LogListFreeze-04 的 O(N) 因子），缓存词法片段后测量只剩轻量
     * 组件布局。SINGLE_LINE 不走缓存（固定行高 + 渲染直读，主题切换实时生效）。
     */
    var cachedListLineFragments: List<List<SqlTextFragment>>? = null

    /** 缓存对应的显示模式——读取处按当前模式比对，模式变更自动失效（无需显式清缓存）。 */
    var cachedListLineFragmentsMode: SqlListDisplayMode? = null

    companion object {
        private const val MAX_SUMMARY_LENGTH = 160
    }
}

/**
 * T-Perf-19（自 dev 移植）：SQL 指纹（FNV-1a 64 位十六进制串）——去重计数键用，避免以完整 SQL 原文为键
 * 的内存驻留。64 位散列下两条不同 SQL 撞键概率可忽略（~2^-64 量级）。
 */
internal fun sqlFingerprint(sql: String): String {
    var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (i in sql.indices) {
        h = h xor sql[i].code.toLong()
        h *= 0x100000001b3L // FNV-1a 64-bit prime
    }
    return java.lang.Long.toHexString(h)
}

/**
 * T-LogDedupeMpMark：去重口径的空白规范化（trim + 连续空白折叠为单空格）。
 * JdbcTemplate 双路日志对同一语句产出 named/prepared 两副本，仅差首尾空白；
 * 大小写与分号不动——COUNT 前缀判定、达梦带引号标识符大小写均不受影响。
 */
internal fun normalizeSqlWhitespace(sql: String): String =
    sql.trim().replace(Regex("""\s+"""), " ")

/**
 * 单个日志参数：值 + 类型标注。
 *
 * @param value 参数值文本（MyBatis 打印形态，如 `abc`、`1`、`2025-01-01 12:00:00.0`）；null 表示 `null` 字面量
 * @param type 类型标注（`(Long)` 中的 Long）；null 表示无类型标注（如 `null` 字面量或旧版日志）
 */
data class LogSqlParam(
    val value: String?,
    val type: String?,
)

/**
 * 条目渲染回 MyBatis 日志文本（T-M38「发送到日志转 SQL」）：`==>  Preparing: <rawSql>` +
 * `==> Parameters: <v(type), ...>`。与 [MyBatisLogParser] 的宽容匹配（mixedPrefix）互逆——
 * 渲染文本可再次解析出等价条目（round-trip）。
 */
internal fun LogSqlEntry.toLogText(): String = buildString {
    append("==>  Preparing: ").append(rawSql)
    if (params.isNotEmpty()) {
        append('\n').append("==> Parameters: ")
        append(params.joinToString(", ") { it.toParamText() })
    }
}

/** 单个参数渲染回日志形态：`1(Long)` / `abc(String)` / `null`（无类型标注直出值）。 */
private fun LogSqlParam.toParamText(): String {
    val v = value ?: return "null"
    return if (type != null) "$v($type)" else v
}
