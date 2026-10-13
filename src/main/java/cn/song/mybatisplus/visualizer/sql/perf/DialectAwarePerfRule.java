// T-SyncK2JMaster104 波A 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/perf/PerfRule.kt（DialectAwarePerfRule 独立文件：Java 一文件一公开类）
package cn.song.mybatisplus.visualizer.sql.perf;

import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * <b>方言感知</b>性能规则（T-DialectQuoteWarn）。
 *
 * <p>少数规则的正确性依赖当前数据库方言（如「标识符引号是否被本方言接受」）。为把方言接进引擎而
 * <b>不改</b> {@link PerfRule#check} 签名（既有 20 条规则与两批测试零改动），新增本子接口：
 * {@link PerfRuleEngine#apply} 发现规则实现本接口时改调 {@link #check(PerfContext)}，
 * 否则仍调 {@link PerfRule#check}。</p>
 *
 * <p>契约与 {@link PerfRule} 一致（纯函数、耗时 &lt; 5ms、内部异常必须自吞返回空列表），额外约束：</p>
 * <ul>
 *   <li>{@code ctx.dialect() == null} 或方言无引号上下文时，<b>不得</b>硬编码任何默认方言——应返回
 *       空列表（宁可不报）；</li>
 *   <li>本接口的实现类仍须实现 {@link PerfRule#check}（作为无方言上下文的降级，通常直接返回空列表）。</li>
 * </ul>
 */
public interface DialectAwarePerfRule extends PerfRule {

    /**
     * 带上下文的检测（T-DialectQuoteWarn §十二：原 {@code checkWithDialect(sql, ast, dialect)} 升级为
     * {@link PerfContext}，以承载新增的 {@code ansiQuotes} 三态）。
     *
     * @param ctx 执行上下文（SQL / AST / 方言 / 连接 ANSI_QUOTES 三态）
     * @return 警告列表；无问题返回空列表，不返回 null
     */
    @NotNull List<PerfWarning> check(@NotNull PerfContext ctx);
}
