// T-SyncK2JMaster104 波A 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/perf/PerfRule.kt（PerfContext 独立文件：Java 一文件一公开类）
package cn.song.mybatisplus.visualizer.sql.perf;

import cn.song.mybatisplus.visualizer.sql.Dialect;

import net.sf.jsqlparser.statement.Statement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
