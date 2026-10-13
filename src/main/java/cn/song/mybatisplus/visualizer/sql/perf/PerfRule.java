package cn.song.mybatisplus.visualizer.sql.perf;

import java.util.List;

import cn.song.mybatisplus.visualizer.sql.Dialect;

import net.sf.jsqlparser.statement.Statement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 性能规则接口（F-2 SQL 性能分析）。
 *
 * <p>每条规则实现一个性能反模式检测逻辑。规则分两类：
 * <ul>
 *   <li>正则规则：仅需 SQL 文本，{@code check} 的 {@code ast} 参数可忽略；</li>
 *   <li>AST 规则：需要 JSqlParser 解析后的 {@link Statement}，解析失败时 {@code ast} 为 null，
 *       AST 规则应直接返回空列表（降级到正则规则）。</li>
 * </ul>
 *
 * <p>实现要求：
 * <ul>
 *   <li>纯函数，无副作用；</li>
 *   <li>执行耗时 &lt; 5ms（典型 SQL）；</li>
 *   <li>内部异常必须捕获并返回空列表，不得上抛（规则隔离）。</li>
 * </ul>
 */
public interface PerfRule {

    /** 规则 ID（如 `missing_where`、`leading_wildcard_like`）。 */
    @NotNull String getRuleId();

    /**
     * 检测 SQL 性能问题。
     *
     * @param sql 原始 SQL 文本（未修改）
     * @param ast JSqlParser 解析后的语句树；解析失败时为 null
     * @return 警告列表；无问题返回空列表，不返回 null
     */
    @NotNull List<PerfWarning> check(@NotNull String sql, @Nullable Statement ast);
}

/**
 * 规则执行上下文（T-DialectQuoteWarn §十二）。
 *
 * <p>把随调用链一起流动的「SQL 文本 + AST + 方言 + 连接选项」收成一个对象，避免
 * {@link DialectAwarePerfRule} 的参数随维度增加而膨胀（原 {@code checkWithDialect(sql, ast, dialect)}
 * 在 §十二 接入 ANSI_QUOTES 后需第四维）。</p>
 *
 * @param sql        原始 SQL 文本（未修改）
 * @param ast        JSqlParser 解析后的语句树；解析失败时为 null
 * @param dialect    当前方言；无上下文时为 null（方言感知规则此时应返回空列表，不做判定）
 * @param ansiQuotes 目标连接是否<b>已声明</b> {@code sql_mode} 含 {@code ANSI_QUOTES}（T-DialectQuoteWarn
 *                   §十二）：{@code true} = 数据源 URL 明示开启（可证明合法）、{@code false} = 已知但未声明、
 *                   {@code null} = 未知（无绑定数据源 / 探测失败 / 调用点无预览面板）。
 *
 *                   <p><b>三态不可折叠为布尔</b>：URL 只能证明「合法」，不能证明「非法」——生效
 *                   {@code sql_mode} = 服务端全局 + 会话覆盖，URL 里的 {@code sessionVariables} 只是会话覆盖，
 *                   未声明时服务端全局仍可能开着。故 {@code false} 与 {@code null} 在判定上等价（都只能 WARN），
 *                   但保留三态以便缓存键与测试精确区分。</p>
 */
public record PerfContext(
        @NotNull String sql,
        @Nullable Statement ast,
        @Nullable Dialect dialect,
        @Nullable Boolean ansiQuotes) {

    /** {@code ansiQuotes} 未知的便捷形态（Kotlin 默认参数 {@code = null} 的重载等价）。 */
    public PerfContext(@NotNull String sql, @Nullable Statement ast, @Nullable Dialect dialect) {
        this(sql, ast, dialect, null);
    }
}

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
