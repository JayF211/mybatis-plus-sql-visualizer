package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.execute.SqlAstParser;
import cn.song.mybatisplus.visualizer.sql.perf.PerfContext;
import cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch1;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch2;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch3;
import cn.song.mybatisplus.visualizer.sql.perf.rules.PerfRulesBatch4;
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
 *  <li>查缓存（同 SQL 文本 + 同方言 + 同 ANSI_QUOTES 三态不重复分析）；</li>
 *  <li>JSqlParser 解析为 Statement AST（失败降级到纯正则规则）；</li>
 *  <li>{@link PerfRuleEngine} 应用所有已注册规则；</li>
 *  <li>返回 {@link PerfWarning} 列表（最多 10 条）。</li>
 * </ol>
 *
 * <p>规则注册在静态初始化块中完成（四批共 21 条规则）。
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

    /** SQL + 方言 + ANSI_QUOTES 三态 → 警告列表的缓存（LinkedHashMap 实现 LRU）。键见 {@link #cacheKey}。 */
    private static final Map<String, List<PerfWarning>> CACHE =
            new LinkedHashMap<String, List<PerfWarning>>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<PerfWarning>> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    static {
        // 注册四批规则（共 21 条）
        PerfRuleEngine.registerAll(PerfRulesBatch1.all);
        PerfRuleEngine.registerAll(PerfRulesBatch2.all);
        PerfRuleEngine.registerAll(PerfRulesBatch3.all);
        PerfRuleEngine.registerAll(PerfRulesBatch4.all);
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
        return analyze(sql, null, null);
    }

    /** {@link #analyze(String, Dialect, Boolean)} 的方言/ANSI_QUOTES 均未知形态（Kotlin 默认参数重载链）。 */
    public static List<PerfWarning> analyze(String sql, Dialect dialect) {
        return analyze(sql, dialect, null);
    }

    /**
     * 分析 SQL，返回性能警告列表。
     *
     * @param sql        完整 SQL 文本（多行或单行）
     * @param dialect    当前数据库方言；可为 null（无上下文，方言感知规则自动不判）。默认 null 保持
     *                   既有调用点逐字节行为不变
     * @param ansiQuotes 绑定数据源是否<b>已声明</b> {@code sql_mode} 含 {@code ANSI_QUOTES}
     *                   （T-DialectQuoteWarn §十二）：{@code true} = 可证明合法、{@code false} = 已知但未声明、
     *                   {@code null} = 未知（默认）。{@code false} 与 {@code null} 判定等价（都只能 WARN），
     *                   但<b>参与缓存键</b>——三态必须分开缓存
     * @return 警告列表（按规则注册顺序，最多 {@link #MAX_WARNINGS} 条）；无问题返回空列表，不返回 null
     */
    public static List<PerfWarning> analyze(String sql, Dialect dialect, Boolean ansiQuotes) {
        if (sql == null || sql.trim().isEmpty()) return List.of();
        String key = cacheKey(sql, dialect, ansiQuotes);
        List<PerfWarning> cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) return cached;
        List<PerfWarning> warnings = analyzeInternal(sql, dialect, ansiQuotes);
        synchronized (CACHE) {
            CACHE.put(key, warnings);
        }
        return warnings;
    }

    /**
     * 缓存键：{@code sql + '\u0000' + 方言名 + '\u0000' + ansiQuotes 三态}（T-DialectQuoteWarn 四·3 + §十二）。
     *
     * <p><b>必须带方言</b>：同一条 SQL 在 MySQL（{@code FROM "user"} 报引号告警）与 PostgreSQL（不报）下
     * 结论相反，不带方言会让后一次分析命中前一次的缓存返回错结果（串味）。<b>必须带 ansiQuotes 三态</b>
     * （§十二）：同一 {@code sql + 方言} 在「URL 声明 ANSI_QUOTES」（不报）与「未知」（WARN）下结论相反，
     * 三态共键同样串味。{@code '\u0000'} 是不会出现在 SQL 文本里的分隔符，避免
     * {@code sql="a"+方言名} 与 {@code sql="a<方言名>"} 撞键。</p>
     */
    private static String cacheKey(String sql, Dialect dialect, Boolean ansiQuotes) {
        String ansiPart;
        if (ansiQuotes == null) {
            ansiPart = "?";
        } else {
            ansiPart = ansiQuotes ? "true" : "false";
        }
        return sql + '\u0000' + (dialect != null ? dialect.getName() : "") + '\u0000' + ansiPart;
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

    private static List<PerfWarning> analyzeInternal(String sql, Dialect dialect, Boolean ansiQuotes) {
        Statement ast = parseSql(sql);
        List<PerfWarning> warnings = PerfRuleEngine.apply(new PerfContext(sql, ast, dialect, ansiQuotes));
        if (warnings.size() > MAX_WARNINGS) {
            return new ArrayList<>(warnings.subList(0, MAX_WARNINGS));
        }
        return warnings;
    }

    /**
     * 解析 SQL 为 AST（T-PS7-03 收拢：走 SqlAstParser.parseSingle，方括号引用 SQL 的
     * AST 规则不再整体降级）。
     *
     * @return 解析成功返回 Statement；失败返回 null（降级到纯正则规则）
     */
    private static Statement parseSql(String sql) {
        try {
            return SqlAstParser.INSTANCE.parseSingle(sql);
        } catch (Exception e) {
            return null;
        }
    }
}
