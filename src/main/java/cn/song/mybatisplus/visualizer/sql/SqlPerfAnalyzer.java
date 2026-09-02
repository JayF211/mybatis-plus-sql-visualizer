package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch3;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 性能分析器（F-2 SQL 性能分析建议）。
 *
 * <p>对 SQL 文本做<b>纯静态分析</b>，检测性能反模式并给出优化建议。
 * 零 PSI / 零 IDE 依赖（仅 JSqlParser），可用普通 JUnit 直接覆盖。
 *
 * <p>分析流程：
 * <ol>
 *  <li>查缓存（同 SQL 文本不重复分析）；</li>
 *  <li>JSqlParser 解析为 Statement AST（失败降级到纯正则规则）；</li>
 *  <li>{@link PerfRuleEngine} 应用所有已注册规则；</li>
 *  <li>返回 {@link PerfWarning} 列表（最多 10 条）。</li>
 * </ol>
 *
 * <p>规则注册在静态初始化块中完成（三批共 20 条规则）。
 *
 * <p>性能约束：
 * <ul>
 *  <li>典型 SQL（&lt; 1000 字符）分析耗时 &lt; 50ms；</li>
 *  <li>单规则异常隔离，不影响其他规则；</li>
 *  <li>结果缓存上限 200 条（LRU 淘汰）。</li>
 * </ul>
 */
public final class SqlPerfAnalyzer {

    /** 缓存上限（LRU）。 */
    private static final int CACHE_LIMIT = 200;

    /** 警告数量上限。 */
    private static final int MAX_WARNINGS = 10;

    /** SQL → 警告列表的缓存（LinkedHashMap 实现 LRU）。 */
    private static final Map<String, List<PerfWarning>> CACHE =
            new LinkedHashMap<String, List<PerfWarning>>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<PerfWarning>> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    static {
        // 注册三批规则（共 20 条）
        PerfRuleEngine.registerAll(PerfRulesBatch1.all);
        PerfRuleEngine.registerAll(PerfRulesBatch2.all);
        PerfRuleEngine.registerAll(PerfRulesBatch3.all);
    }

    private SqlPerfAnalyzer() {
    }

    /**
     * 分析 SQL，返回性能警告列表。
     *
     * @param sql 完整 SQL 文本（多行或单行）
     * @return 警告列表（按规则注册顺序，最多 {@link #MAX_WARNINGS} 条）；无问题返回空列表，不返回 null
     */
    public static List<PerfWarning> analyze(String sql) {
        if (sql == null || sql.trim().isEmpty()) return List.of();
        List<PerfWarning> cached;
        synchronized (CACHE) {
            cached = CACHE.get(sql);
        }
        if (cached != null) return cached;
        List<PerfWarning> warnings = analyzeInternal(sql);
        synchronized (CACHE) {
            CACHE.put(sql, warnings);
        }
        return warnings;
    }

    /**
     * 清空分析缓存。
     */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * 当前缓存大小（测试用）。
     */
    public static int cacheSize() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static List<PerfWarning> analyzeInternal(String sql) {
        Statement ast = parseSql(sql);
        List<PerfWarning> warnings = PerfRuleEngine.apply(sql, ast);
        if (warnings.size() > MAX_WARNINGS) {
            return new ArrayList<>(warnings.subList(0, MAX_WARNINGS));
        }
        return warnings;
    }

    /**
     * 解析 SQL 为 AST。
     *
     * @return 解析成功返回 Statement；失败返回 null（降级到纯正则规则）
     */
    private static Statement parseSql(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return null;
        }
    }
}
