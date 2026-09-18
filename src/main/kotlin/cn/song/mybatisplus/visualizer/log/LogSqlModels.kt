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
    /** 日志原始时间戳（从日志行中解析，未解析到则为 null）。 */
    val logTimestamp: Long? = null,
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
     * T-AgentSourceJump：源码位置候选（栈序 [file, line]；仅 agent 通道产出，控制台通道/历史读回为空）。
     * 主位置在工程内解析失败时，跳转按候选序取首个可解析项——候选全部来自本次执行的真实调用栈，
     * 非表名等启发式（不会误导）。
     */
    val sourceCandidates: List<Pair<String, Int>> = emptyList(),
    /**
     * T-SourceJumpFunnel：候选帧类名（与 [sourceCandidates] 栈序对齐，旧 agent 事件可能缺类名列 → 空）。
     * 展示性字段（选帧弹层/NotFound 提示），**body 内属性不参与 equals/hashCode**——但 sourceCandidates
     * 在主构造里，本字段也在主构造，须评估 equals 影响：去重走 dedupeKey（惰性指纹），data class equals
     * 变化不影响面板去重口径（T-CrudColor 同理定案）。
     */
    val candidateClasses: List<String> = emptyList(),
    /** T-SourceJumpFunnel：调用栈快照「类#方法:行 ← …」（仅 agent 通道；详情面板人工往上追踪用）。 */
    val stackDigest: String? = null,
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
    /**
     * 目标数据库显示名（T-AgentDialectLoop：agent 通道由连接 URL 子协议映射，
     * 如 "MySQL" / "DM（达梦）"）；null 表示未知（桥通道 / 旧版 agent / 畸形 URL）。
     * 展示性字段，不入去重指纹。
     */
    val databaseName: String? = null,
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
 * T-AgentLogTime：日志时间关联键——**占位符版 rawSql 的指纹**。
 *
 * 增强通道的 logTimestamp 是派生的（控制台链嗅探暂存 → agent 事件取回），关联键必须两侧同源：
 * 控制台侧 rawSql = `Preparing:` 行原文，agent 侧 rawSql = `prepareStatement` 收到的原文，
 * 二者都不经过参数还原。旧口径用 [LogSqlEntry.dedupeKey]（还原后 SQL 指纹）要求两套还原器
 * 逐字符一致，布尔参数渲染、注释内 `?` 计数、参数个数守卫三类分叉都会静默失配。
 *
 * 与 [LogSqlEntry.dedupeKey] 的分工：dedupeKey 管面板去重（跨通道同 SQL 折叠，语义不动），
 * 本键只管日志时间配对——错位替换防护（agent 侧「占位符数 ≠ 参数数就不还原」）因此得以保留。
 */
internal fun logTimeKey(entry: LogSqlEntry): String = sqlFingerprint(normalizeSqlWhitespace(entry.rawSql))

/**
 * T-AgentLogTime：日志时间配对的次级判别——参数**值**序列签名。
 * 同键（同一条 SQL 多次执行）时用它挑参数一致的那条；两侧渲染口径若分叉则退化为队首配对
 * （近似但不错失），故不做强制相等断言。无参语句两侧都是空串，天然相等。
 */
internal fun logTimeParamSignature(params: List<LogSqlParam>): String =
    params.joinToString("\u0001") { it.value ?: "\u0000" }

/**
 * T-AgentLogTime：给 agent 条目回填控制台嗅探到的日志时间；未命中返回 null
 * （调用方回落 [LogSqlEntry.timestamp] 并留痕）。
 *
 * 增强通道回填的唯一入口——生产路径（`AgentManager.AgentSession`）与跨通道守卫测试都走它，
 * 避免测试另抄一份「取键 + take」逻辑而自证（T-AgentDialectLoop 的教训：两侧各自绿、集成不通）。
 */
internal fun stampLogTime(entry: LogSqlEntry): LogSqlEntry? =
    LogTimeCorrelator.take(logTimeKey(entry), logTimeParamSignature(entry.params))
        ?.let { entry.copy(logTimestamp = it) }

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
