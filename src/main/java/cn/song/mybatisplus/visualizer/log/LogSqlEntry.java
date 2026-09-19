package cn.song.mybatisplus.visualizer.log;

import java.util.List;

/**
 * 日志 SQL 条目模型（T-M31）：一条 SQL 语句 + 其参数列表。
 *
 * 来源：MyBatis / Hibernate / JdbcTemplate 控制台日志，经解析器配对、LogSqlRestorer 还原后进入面板。
 */
public record LogSqlEntry(
    /** 原始 SQL 文本（未替换占位符）。 */
    String rawSql,
    /** 还原后的可执行 SQL（参数已替换；无法替换的 ? 保留）。 */
    String restoredSql,
    /** 参数列表（与 SQL 中 ? 按序对应）。 */
    List<LogSqlParam> params,
    /** 捕获时间戳。 */
    long timestamp,
    /** 日志原始时间戳（从日志行中解析，未解析到则为 null）。 */
    Long logTimestamp,
    /**
     * 摘要（列表展示用，智能截断：在第一行末尾或 MAX_SUMMARY_LENGTH 处取较小值，
     * 避免 mid-line 截断导致 multiline SQL 列表展示不完整）。
     */
    String summary,
    /** 条目来源（MyBatis / Hibernate / 未知）。 */
    SqlSource source,
    /**
     * 执行耗时（毫秒）；null 表示未解析到耗时信息。
     */
    Long executionTimeMs,
    /**
     * 产生该 SQL 的源文件路径（相对项目根目录）。
     * null 表示未知（旧版 F-43 保存的条目无此信息）。
     */
    String sourceFile,
    /**
     * 产生该 SQL 的源码行号（0 表示未知）。
     */
    int sourceLine,
    /**
     * T-AgentSourceJump：源码位置候选（栈序 [file, line]；仅 agent 通道产出，控制台通道/历史读回为空）。
     * 主位置在工程内解析失败时，跳转按候选序取首个可解析项——候选全部来自本次执行的真实调用栈，
     * 非表名等启发式（不会误导）。
     */
    List<kotlin.Pair<String, Integer>> sourceCandidates,
    /**
     * T-SourceJumpFunnel：候选帧类名（与 sourceCandidates 栈序对齐，旧 agent 事件可能缺类名列 → 空）。
     * 展示性字段（选帧弹层/NotFound 提示），body 内属性不参与 equals/hashCode——但 sourceCandidates
     * 在主构造里，本字段也在主构造，须评估 equals 影响：去重走 dedupeKey（惰性指纹），data class equals
     * 变化不影响面板去重口径（T-CrudColor 同理定案）。
     */
    List<String> candidateClasses,
    /**
     * T-SourceJumpFunnel：调用栈快照「类#方法:行 ← …」（仅 agent 通道；详情面板人工往上追踪用）。
     */
    String stackDigest,
    /**
     * T-LogRowNumber：会话内捕获序号（「SQL 日志」列表行号）。由 SqlLogEntryBuffer.addEntry
     * 成功入队时分配（单调递增；去重丢弃/暂停/过滤关键字命中的条目不占号），
     * 项目启动（processStarted）或手动清空列表时归零。0 表示无行号
     * （历史文件读回、日志转 SQL round-trip 产出的条目）。
     */
    long sequenceNumber,
    // ---------- M56 T-M56-00：agent 通道结构化字段（桥通道经标记行第 4/5 段与 pd 载荷扩展部分承接） ----------
    /**
     * mapper 语句 id（如 {@code com.example.mapper.UserMapper.selectById}）。
     * agent 通道从 MyBatis Executor 直取；桥通道来自源码标记第 4 段（拦截器 {@code ms.getId()}）。
     * null 表示未知（旧条目 / 非 MyBatis 来源）。
     */
    String mapperStatement,
    /**
     * 执行报错（结构化直取：异常类名 + 消息，堆栈截断）；null 表示执行成功。
     * agent 通道在执行点直捕；桥通道暂无对应日志形态（T-M56-05 前保持 null）。
     */
    String errorText,
    /**
     * 请求级聚合标签（T-M56-07 请求聚合预留）：同一次请求的多条 SQL 共享同值，
     * 聚焦页签据此分组。桥通道来自源码标记第 5 段（拦截器 MDC 兜底）；null 表示无标签。
     */
    String requestTag,
    /**
     * 影响行数（agent 通道 {@code getUpdateCount} 直取，DML 语义）；null 表示未知（查询 / 旧条目）。
     */
    Long updateCount,
    /**
     * 目标数据库显示名（T-AgentDialectLoop：agent 通道由连接 URL 子协议映射，
     * 如 "MySQL" / "DM（达梦）"）；null 表示未知（桥通道 / 旧版 agent / 畸形 URL）。
     * 展示性字段，不入去重指纹。
     */
    String databaseName
) {
    /** 摘要最大长度。 */
    private static final int MAX_SUMMARY_LENGTH = 160;

    /**
     * T-CrudColor/T-ListMode 缓存侧表（Java record 无实例字段——Kotlin data class 体属性的
     * Java 等价承载）：键为条目（record 值相等语义），弱键随条目 GC。缓存内容（statementType、
     * 行片段）均由条目组件派生，值相等的条目共享缓存结果语义完全等价。
     * 仅 EDT 渲染路径读写，synchronized 包装仅作跨面板安全垫。
     */
    private static final java.util.Map<LogSqlEntry, StatementType> STATEMENT_TYPE_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 行片段缓存载荷（片段 + 模式一并存取，保证两值一致）。 */
    private record LineFragmentCacheEntry(List<List<SqlTextFragment>> fragments, SqlListDisplayMode mode) {
    }

    private static final java.util.Map<LogSqlEntry, LineFragmentCacheEntry> LINE_FRAGMENT_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * T-CrudColor：条目语句类型（渲染热路径缓存，惰性计算一次）。
     *
     * 由 {@link LogStatementClassifier#classify(String)} 得出——与类型过滤弹窗<b>同一口径</b>，
     * 因此「筛出来的 SELECT 行不会被标成红色」这一自洽性天然成立。
     *
     * 之所以缓存而非每次现算：classify 内部 {@code split("\\s+")}
     * <b>每次调用都重新编译 Pattern</b>，渲染路径按 cell 调用会把该开销放大到
     * 「每次重绘 × 可见行数」（滚动/悬停/选中/resize 均触发重绘）。
     *
     * 与 dedupeKey 同属 body 内属性——<b>不参与 record 的 equals / hashCode</b>，
     * 故新增该字段不影响既有去重与集合语义（T-LogRowNumber 的 sequenceNumber 教训：
     * 构造函数内新增字段会改变 equals，此处不会）。
     */
    public StatementType statementType() {
        StatementType t = STATEMENT_TYPE_CACHE.get(this);
        if (t == null) {
            t = LogStatementClassifier.classify(restoredSql);
            STATEMENT_TYPE_CACHE.put(this, t);
        }
        return t;
    }

    /** Kotlin {@code val statementType} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public StatementType getStatementType() {
        return statementType();
    }

    /**
     * T-ListMode：多行模式（KEYWORD_WRAP/FORMATTED）的行片段缓存：可变行高下每次布局状态更新会对全部条目
     * 逐条调渲染器取首选尺寸（T-LogListFreeze-04 的 O(N) 因子），缓存词法片段后测量只剩轻量
     * 组件布局。SINGLE_LINE 不走缓存（固定行高 + 渲染直读，主题切换实时生效）。
     */
    public List<List<SqlTextFragment>> cachedListLineFragments() {
        LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
        return e != null ? e.fragments() : null;
    }

    /** Kotlin {@code var cachedListLineFragments} 的 JVM 读取器等价入口（属性名读取用）。 */
    public List<List<SqlTextFragment>> getCachedListLineFragments() {
        return cachedListLineFragments();
    }

    public void setCachedListLineFragments(List<List<SqlTextFragment>> value) {
        synchronized (LINE_FRAGMENT_CACHE) {
            LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
            LINE_FRAGMENT_CACHE.put(this, new LineFragmentCacheEntry(value, e != null ? e.mode() : null));
        }
    }

    /** 行片段缓存对应的显示模式——读取处按当前模式比对，模式变更自动失效（无需显式清缓存）。 */
    public SqlListDisplayMode cachedListLineFragmentsMode() {
        LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
        return e != null ? e.mode() : null;
    }

    /** Kotlin {@code var cachedListLineFragmentsMode} 的 JVM 读取器等价入口（属性名读取用）。 */
    public SqlListDisplayMode getCachedListLineFragmentsMode() {
        return cachedListLineFragmentsMode();
    }

    public void setCachedListLineFragmentsMode(SqlListDisplayMode value) {
        synchronized (LINE_FRAGMENT_CACHE) {
            LineFragmentCacheEntry e = LINE_FRAGMENT_CACHE.get(this);
            LINE_FRAGMENT_CACHE.put(this, new LineFragmentCacheEntry(e != null ? e.fragments() : null, value));
        }
    }


    /**
     * T-Perf-19（自 dev 移植）：去重键指纹（sqlFingerprint 惰性计算一次）——dedupeCounts 不再以
     * 完整 SQL 原文为键（还原参数文本可达数 KB/条，长会话数十~上百 MB 永久驻留）。
     * T-LogDedupeMpMark：指纹前先 normalizeSqlWhitespace——同一语句的双路日志副本（named 行 trim 过 /
     * prepared 行尾随空白原样保留）仅差首尾空白，原文指纹判重永不命中。
     * （record 无实例字段，纯函数每次现算——FNV-1a 线性扫描，成本可忽略。）
     */
    public String dedupeKey() {
        return LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(restoredSql));
    }

    /** Kotlin {@code val dedupeKey} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public String getDedupeKey() {
        return dedupeKey();
    }

    /** 仅原始/还原 SQL + 参数（其余取默认）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(), null,
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null);
    }

    /** 含显式时间戳。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, long timestamp) {
        this(rawSql, restoredSql, params, timestamp, null,
            computeSummary(restoredSql), SqlSource.UNKNOWN, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null);
    }

    /** 含来源。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params, SqlSource source) {
        this(rawSql, restoredSql, params, System.currentTimeMillis(), null,
            computeSummary(restoredSql), source, null, null, 0, List.of(), List.of(), null, 0L,
            null, null, null, null, null);
    }

    /** 含时间戳 + 源码位置 + 耗时（历史文件反解析）。 */
    public LogSqlEntry(String rawSql, String restoredSql, List<LogSqlParam> params,
                       long timestamp, String sourceFile, int sourceLine, Long executionTimeMs) {
        this(rawSql, restoredSql, params, timestamp, null,
            computeSummary(restoredSql), SqlSource.UNKNOWN, executionTimeMs, sourceFile, sourceLine, List.of(), List.of(), null, 0L,
            null, null, null, null, null);
    }

    /** T-LogRowNumber：以本条为原型返回带指定捕获序号的副本（Kotlin data class copy(sequenceNumber=…) 的 Java 等价）。 */
    public LogSqlEntry withSequenceNumber(long newSequenceNumber) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, newSequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName);
    }

    /** T-AgentLogTime：以本条为原型返回带日志原始时间的副本（copy(logTimestamp=…) 的 Java 等价）。 */
    public LogSqlEntry withLogTimestamp(Long newLogTimestamp) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, newLogTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, sequenceNumber,
            mapperStatement, errorText, requestTag, updateCount, databaseName);
    }

    /** T-M55-02：以本条为原型返回带执行报错的副本（copy(errorText=…) 的 Java 等价；缓冲错误反向绑定用）。 */
    public LogSqlEntry withErrorText(String newErrorText) {
        return new LogSqlEntry(rawSql, restoredSql, params, timestamp, logTimestamp, summary, source,
            executionTimeMs, sourceFile, sourceLine, sourceCandidates, candidateClasses, stackDigest, sequenceNumber,
            mapperStatement, newErrorText, requestTag, updateCount, databaseName);
    }

    /** 智能截断摘要（包内可见：解析器/桥通道按 Kotlin data class 默认参数语义构造条目时复用同一口径）。 */
    static String computeSummary(String restoredSql) {
        int newlineIdx = restoredSql.indexOf('\n');
        int endIdx;
        if (newlineIdx >= 0 && newlineIdx < MAX_SUMMARY_LENGTH) {
            endIdx = newlineIdx;
        } else {
            endIdx = Math.min(restoredSql.length(), MAX_SUMMARY_LENGTH);
        }
        if (endIdx < restoredSql.length()) {
            return restoredSql.substring(0, endIdx) + "\u2026";
        }
        return restoredSql;
    }
}
