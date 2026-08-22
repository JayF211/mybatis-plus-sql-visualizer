package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch3
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement

/**
 * SQL 性能分析器（F-2 SQL 性能分析建议）。
 *
 * 对 SQL 文本做**纯静态分析**，检测性能反模式并给出优化建议。
 * 零 PSI / 零 IDE 依赖（仅 JSqlParser），可用普通 JUnit 直接覆盖。
 *
 * 分析流程：
 *  1. 查缓存（同 SQL 文本不重复分析）；
 *  2. JSqlParser 解析为 Statement AST（失败降级到纯正则规则）；
 *  3. [PerfRuleEngine] 应用所有已注册规则；
 *  4. 返回 [PerfWarning] 列表（最多 10 条）。
 *
 * 规则注册在 init 块中完成（三批共 20 条规则）。
 *
 * 性能约束：
 *  - 典型 SQL（< 1000 字符）分析耗时 < 50ms；
 *  - 单规则异常隔离，不影响其他规则；
 *  - 结果缓存上限 200 条（LRU 淘汰）。
 */
object SqlPerfAnalyzer {

    /** 缓存上限（LRU）。 */
    private const val CACHE_LIMIT = 200

    /** 警告数量上限。 */
    private const val MAX_WARNINGS = 10

    /** SQL → 警告列表的缓存（ LinkedHashMap 实现 LRU）。 */
    private val cache = object : LinkedHashMap<String, List<PerfWarning>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<PerfWarning>>): Boolean = size > CACHE_LIMIT
    }

    init {
        // 注册三批规则（共 20 条）
        PerfRuleEngine.registerAll(PerfRulesBatch1.all)
        PerfRuleEngine.registerAll(PerfRulesBatch2.all)
        PerfRuleEngine.registerAll(PerfRulesBatch3.all)
    }

    /**
     * 分析 SQL，返回性能警告列表。
     *
     * @param sql 完整 SQL 文本（多行或单行）
     * @return 警告列表（按规则注册顺序，最多 [MAX_WARNINGS] 条）；无问题返回空列表，不返回 null
     */
    fun analyze(sql: String): List<PerfWarning> {
        if (sql.isBlank()) return emptyList()
        synchronized(cache) {
            cache[sql]?.let { return it }
        }
        val warnings = analyzeInternal(sql)
        synchronized(cache) {
            cache[sql] = warnings
        }
        return warnings
    }

    /**
     * 清空分析缓存。
     */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 当前缓存大小（测试用）。
     */
    fun cacheSize(): Int = synchronized(cache) { cache.size }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun analyzeInternal(sql: String): List<PerfWarning> {
        val ast = parseSql(sql)
        val warnings = PerfRuleEngine.apply(sql, ast)
        return if (warnings.size > MAX_WARNINGS) warnings.take(MAX_WARNINGS) else warnings
    }

    /**
     * 解析 SQL 为 AST。
     *
     * @return 解析成功返回 Statement；失败返回 null（降级到纯正则规则）
     */
    private fun parseSql(sql: String): Statement? {
        return runCatching { CCJSqlParserUtil.parse(sql) }.getOrNull()
    }
}
