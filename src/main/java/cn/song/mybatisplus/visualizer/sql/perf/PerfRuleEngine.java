package cn.song.mybatisplus.visualizer.sql.perf;

import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.statement.Statement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 性能规则引擎（F-2 SQL 性能分析）。
 *
 * <p>注册 + 执行所有规则。每条规则在独立的 try-catch 中执行——单规则异常不阻塞其他规则。
 *
 * <p>执行流程：
 * <ol>
 *   <li>遍历所有已注册规则；</li>
 *   <li>每条规则独立 try-catch，异常时记录并跳过；</li>
 *   <li>合并所有规则的警告列表返回。</li>
 * </ol>
 */
public final class PerfRuleEngine {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final PerfRuleEngine INSTANCE = new PerfRuleEngine();

    private static final List<PerfRule> rules = new ArrayList<>();

    private PerfRuleEngine() {
    }

    /**
     * 注册一条规则（幂等：同 ruleId 重复注册覆盖旧规则）。
     */
    public static void register(@NotNull PerfRule rule) {
        rules.removeIf(it -> it.getRuleId().equals(rule.getRuleId()));
        rules.add(rule);
    }

    /**
     * 批量注册规则。
     */
    public static void registerAll(@NotNull List<@NotNull PerfRule> newRules) {
        newRules.forEach(PerfRuleEngine::register);
    }

    /**
     * 应用所有规则到 SQL。
     *
     * @param sql 原始 SQL 文本
     * @param ast JSqlParser 解析后的语句树；可为 null（降级模式）
     * @return 所有规则的警告列表（按规则注册顺序）
     */
    @NotNull
    public static List<PerfWarning> apply(@NotNull String sql, @Nullable Statement ast) {
        List<PerfWarning> warnings = new ArrayList<>();
        for (PerfRule rule : rules) {
            try {
                List<PerfWarning> result = rule.check(sql, ast);
                if (!result.isEmpty()) {
                    warnings.addAll(result);
                }
            } catch (Exception e) {
                // 规则隔离：单规则异常不阻塞其他规则
            }
        }
        return warnings;
    }

    /** 当前注册规则数量（测试用）。 */
    public static int ruleCount() {
        return rules.size();
    }
}
