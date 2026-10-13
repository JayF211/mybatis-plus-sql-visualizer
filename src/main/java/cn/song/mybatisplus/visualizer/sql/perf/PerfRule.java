package cn.song.mybatisplus.visualizer.sql.perf;

import java.util.List;

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
