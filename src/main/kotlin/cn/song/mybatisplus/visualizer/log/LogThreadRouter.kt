package cn.song.mybatisplus.visualizer.log

/**
 * 控制台日志「线程分桶」路由与日志行形态判定（T-LogParseConc）。
 *
 * 背景：单槽状态机假设「SQL 行 → 参数行 → 下一条」严格串行，而真实控制台里多个请求线程的日志
 * 按到达序混排——另一线程的语句行会把本线程尚未收到参数的语句提前闭合，本线程随后到达的参数行
 * 因无上下文被静默丢弃（参数丢失、`?` 未还原）。`JdbcTemplateLogParser` 在 T-JdbcThreadBucket
 * （2026-09-15）已按线程 token 分桶修复，本文件把该机制下沉为三解析器共享实现（MyBatis/Hibernate
 * 此前从未跟进，T-LogParseConc 实证为「并发下条目严重不正确」主因）。
 *
 * 路由策略（fail-open，与既有 JdbcTemplate 实现同语义）：
 * - 行内提取到线程 token → 该线程的独立桶（不存在则新建，超上限先淘汰空闲桶）；
 * - 提取不到 token（多行 SQL 的裸续行等）→ 归属当前处于「收集态」的桶（通常唯一），
 *   否则回退共享默认桶——与旧单槽行为一致，不因提取失败而丢行。
 *
 * 桶状态由各解析器自定（[B] 泛型），本类只管「哪一行归哪个桶」与容量。
 */
internal class LogThreadRouter<B : Any>(
    private val factory: () -> B,
    /** 该桶是否处于「多行 SQL 收集中」——无线程 token 的续行按此归属。 */
    private val isCollecting: (B) -> Boolean,
    /** 该桶是否空闲（无待闭合状态）——超上限淘汰用，淘汰不作为即丢状态，故只淘汰空闲桶。 */
    private val isIdle: (B) -> Boolean,
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
) {

    /** 线程 token → 独立桶（保持插入序，便于 closePending 按序冲刷）。 */
    private val buckets = LinkedHashMap<String, B>()

    /** token 提取失败时的回退桶（与旧单槽状态机同语义）。 */
    private val fallback: B = factory()

    /** 按行路由到对应桶。 */
    fun bucketFor(line: String): B {
        val token = threadTokenOf(line)
        if (token != null) {
            buckets[token]?.let { return it }
            if (buckets.size >= maxBuckets) evictIdle()
            return factory().also { buckets[token] = it }
        }
        return buckets.values.firstOrNull { isCollecting(it) } ?: fallback
    }

    /** 全部桶（分桶在前、回退桶在后）——closePending 全量冲刷用。 */
    fun allBuckets(): List<B> = buckets.values.toList() + fallback

    /** 当前活跃桶数（诊断/测试用）。 */
    fun bucketCount(): Int = buckets.size

    /** 清空（面板销毁/重挂载时）。 */
    fun clear() {
        buckets.clear()
    }

    /** 淘汰空闲桶直到低于上限（新建桶前调用）。 */
    private fun evictIdle() {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext() && buckets.size >= maxBuckets) {
            val entry = iterator.next()
            if (isIdle(entry.value)) iterator.remove()
        }
    }

    companion object {
        /** 单实例最大线程桶数（超限时空闲桶冲刷淘汰，防异常形态下内存膨胀）。 */
        const val DEFAULT_MAX_BUCKETS = 64

        /** 线程 token 提取时，日志级别关键词前后窗口大小（字符）。 */
        private const val THREAD_WINDOW = 35

        /** 匹配行内方括号段（线程名 / TID 段 / 纯数字下标）。 */
        private val THREAD_BRACKET = Regex("""\[([^\]]*)]""")

        /** 纯数字判定（column index / 数组下标等非线程名）。 */
        private val PURE_DIGIT = Regex("""\d+""")

        /** 日志级别关键词锚点（线程 token 定位；T-PerfSweep 收口为常量，逐行热路径复用）。 */
        private val LEVEL_KEYWORD_PATTERN = Regex("""\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""")

        /**
         * 从日志行提取线程 token（并发分桶键）。兼容三种形态：
         * - SkyWalking 注入：`2026-09-15 13:47:17.843 [TID: AE51C…] [http-nio-…-exec-4] DEBUG …`
         * - logback 默认：`2026-08-21 10:00:00.124 TRACE [main] o.h… : …`
         * - Spring Boot 默认：`2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE …`
         *
         * 以日志级别关键词为锚，取前后窗口内最近的非 TID 非纯数字方括号段。
         * 返回 null 表示无法可靠提取（→ 回退共享默认桶，fail-open）。
         */
        fun threadTokenOf(line: String): String? {
            val levelMatch = LEVEL_KEYWORD_PATTERN.find(line) ?: return null
            val levelPos = levelMatch.range.first
            val beforeLevel = line.substring((levelPos - THREAD_WINDOW).coerceAtLeast(0), levelPos)
            val afterLevel = line.substring(levelPos, (levelPos + THREAD_WINDOW).coerceAtMost(line.length))

            fun findThread(s: String): String? =
                THREAD_BRACKET.findAll(s)
                    .map { it.groupValues[1] }
                    .filter { token ->
                        !token.startsWith("TID:", ignoreCase = true) &&
                            !token.matches(PURE_DIGIT) &&
                            token.isNotBlank()
                    }
                    .lastOrNull()

            return findThread(beforeLevel) ?: findThread(afterLevel)
        }
    }
}

/**
 * 行是否**带日志行特征**（T-LogParseConc）：供「空 `Parameters:` 等待态」与「SQL 续行」判定共用。
 *
 * 用途有二，缺它两处都会误判：
 * - 空 `Parameters:` 行后的下一行若是日志行，说明该语句**本就是无参数语句**（MyBatis 对无参语句
 *   打印空前缀行）——须闭合且**不得吞掉该行**；否则整行日志会被当作参数值塞进 `?`。
 * - 多行 SQL 续行判定：日志行即续行终止符。
 *
 * 判定特征（任一命中即为日志行）：`==>` / `<==` 前缀、含给定语句关键字（`Preparing:`/`Parameters:` 等）、
 * 行首时间戳、或「日志级别关键词 + 方括号段」组合（裸参数文本形态 `10(Long), abc(String)` 三者皆不命中）。
 */
internal fun looksLikeLogLine(line: String, vararg keywords: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("==>") || trimmed.startsWith("<==")) return true
    for (keyword in keywords) {
        if (keyword.isNotEmpty() && trimmed.contains(keyword)) return true
    }
    if (regexLineStartTimestamp.containsMatchIn(line)) return true
    return regexLevelKeyword.containsMatchIn(trimmed) && trimmed.contains('[')
}

private val regexLineStartTimestamp = Regex("""^\s*\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}""")
private val regexLevelKeyword = Regex("""\b(DEBUG|TRACE|INFO|WARN|ERROR)\b""")

/**
 * 解析期静默失效计数（T-LogParseConc S5）：这些失效过去**完全无痕**——用户只能靠肉眼发现
 * 「参数没了」「SQL 里混进日志行」。计数暴露给面板/日志侧周期上报，排障时一眼看到是哪一类。
 *
 * 纯静态计数（无 IDE 依赖，可在纯逻辑用例中断言）；上报由调用方（控制台桥，已持有 Logger）完成。
 */
internal object LogParseDiag {

    /** 参数行到达时无待闭合语句（上下文已丢）→ 该参数被丢弃。 */
    val paramWithoutContext = java.util.concurrent.atomic.AtomicLong()

    /** 空 `Parameters:` 等待态被判别式拦下（原本会把下一行整行吞成参数值）。 */
    val swallowedLineBlocked = java.util.concurrent.atomic.AtomicLong()

    /** 多行残句强制闭合（未等到闭合括号）。 */
    val partialMultilineClosed = java.util.concurrent.atomic.AtomicLong()

    /** 参数文本命中日志行特征被拒（最后一道防污染）。 */
    val paramWithLogMarker = java.util.concurrent.atomic.AtomicLong()

    /** 上次已上报的计数快照（仅上报增量，避免刷屏）。 */
    private var reported = longArrayOf(0, 0, 0, 0)

    /** 当前各计数（[paramWithoutContext], [swallowedLineBlocked], [partialMultilineClosed], [paramWithLogMarker]）。 */
    fun counts(): LongArray = longArrayOf(
        paramWithoutContext.get(),
        swallowedLineBlocked.get(),
        partialMultilineClosed.get(),
        paramWithLogMarker.get(),
    )

    /**
     * 返回「自上次上报以来的增量」描述；无增量返回 null（调用方按周期调用，零增量零输出）。
     */
    @Synchronized
    fun takeDeltaReport(): String? {
        val now = counts()
        if (now.contentEquals(reported)) return null
        val delta = LongArray(now.size) { now[it] - reported[it] }
        reported = now
        return "控制台解析诊断（增量）：参数行无上下文丢弃=${delta[0]} 空参数行拦下=${delta[1]} " +
            "多行残句闭合=${delta[2]} 参数含日志特征被拒=${delta[3]}（累计 ${now.joinToString("/")}）"
    }

    /** 全量归零（测试/会话切换）。 */
    @Synchronized
    fun reset() {
        paramWithoutContext.set(0)
        swallowedLineBlocked.set(0)
        partialMultilineClosed.set(0)
        paramWithLogMarker.set(0)
        reported = longArrayOf(0, 0, 0, 0)
    }
}
