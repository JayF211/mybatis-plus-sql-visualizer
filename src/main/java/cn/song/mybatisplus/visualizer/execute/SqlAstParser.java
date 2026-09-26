// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/execute/SqlAstParser.kt
package cn.song.mybatisplus.visualizer.execute;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.feature.Feature;
import net.sf.jsqlparser.parser.feature.FeatureConfiguration;
import net.sf.jsqlparser.statement.Statement;

import java.util.List;
import java.util.Objects;

/**
 * SQL 文本 → AST 的<b>单一解析入口</b>（T-PerfSweep7 T-PS7-03 收拢后共四处：无界写探测/回滚推导、
 * 元数据失效、DDL 目标提取、SQL 性能分析、批量等值谓词合并、MCP SELECT 闸门）。
 *
 * <p><b>为什么需要它</b>（2026-09-23 真机走查发现的缺陷）：此前各处各自写 {@code CCJSqlParserUtil.parse*(sql)}
 * ——<b>不带任何 feature</b>，于是 SQL Server 的方括号引用 {@code [user]} 被 JSqlParser 拒：
 * {@code ParseException: Encountered unexpected token: "[" "[" at line 2, column 6.}。
 * 而方括号引用<b>正是本插件自己为 SQL Server 产出的形态</b>（{@code Dialect.quoteIfNeeded} 走 {@code [...]}），
 * 于是「回滚 SQL」对 SQL Server 产物整体不可用（真机读数：回滚弹窗直接报 {@code 无法解析 DELETE: …ParseException}）。</p>
 *
 * <p>只开 {@code allowSquareBracketQuotation} 一项，不放宽其它解析行为（最小面）。
 * 失败语义：<b>原样抛出</b>（{@link JSQLParserException}），由调用方决定 fail 方向
 * （闸门 fail-closed / 探测 fail-through / 分析降级）。</p>
 *
 * <p>移植注记：master 的 Kotlin {@code internal object} 按本仓互操作惯例落为
 * {@code final class} + {@link #INSTANCE} 单例；两个方法为<b>实例方法</b>，调用形态
 * {@code SqlAstParser.INSTANCE.parseSingle(...)}（勿改纯静态）。Kotlin 顶层无需受检声明，
 * Java 侧按底层 {@code CCJSqlParserUtil} 的真实签名透出 {@code throws JSQLParserException}，
 * 「失败原样抛出」逐字保留。</p>
 */
public final class SqlAstParser {

    public static final SqlAstParser INSTANCE = new SqlAstParser();
    private SqlAstParser() {}

    private static final FeatureConfiguration features =
            new FeatureConfiguration().setValue(Feature.allowSquareBracketQuotation, true);

    /**
     * 解析（可含多条语句与注释）；失败原样抛出，由调用方决定 fail 语义。
     *
     * <p><b>移植注记（Kotlin 空安全显式化）</b>：master 的 Kotlin 返回类型声明为非空
     * {@code List<Statement>}，编译器在返回平台类型值处内置 {@code checkNotNullExpressionValue}。
     * jsqlparser 5.3 实测：同一 JVM 内<b>已成功解析过一次</b>之后，解析失败的
     * {@code parseStatements(String, Consumer)} 会<b>静默返回 null 而非抛 {@link JSQLParserException}</b>
     * （首败抛、次败返 null；探测产物 {@code statements=null} 直达调用方迭代 NPE）。
     * Java 无编译器判空，必须显式拒绝——null 在此转抛 {@code NullPointerException}，
     * 各调用方的 {@code catch (Exception)} fail-closed 语义（探测 PARSE_FAILED / 回滚 UNABLE /
     * 元数据失效 fail-closed true）照常接管，与 master 行为一致。
     */
    public List<Statement> parseStatements(String sql) throws JSQLParserException {
        return Objects.requireNonNull(
                CCJSqlParserUtil.parseStatements(sql, parser -> parser.withConfiguration(features)),
                "CCJSqlParserUtil.parseStatements(sql) must not be null");
    }

    /** 解析单条语句（T-PS7-03 新增，共享既有 {@link #features}）；失败原样抛出，由调用方决定 fail 语义。 */
    public Statement parseSingle(String sql) throws JSQLParserException {
        return Objects.requireNonNull(
                CCJSqlParserUtil.parse(sql, parser -> parser.withConfiguration(features)),
                "CCJSqlParserUtil.parse(sql) must not be null");
    }
}
