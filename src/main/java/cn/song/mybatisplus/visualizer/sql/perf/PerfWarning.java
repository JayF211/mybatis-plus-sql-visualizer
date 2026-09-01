package cn.song.mybatisplus.visualizer.sql.perf;

import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * 单条性能警告（F-2 SQL 性能分析）。
 *
 * @param ruleId      规则 ID（如 `missing_where`、`leading_wildcard_like`）
 * @param severity    严重度（ERROR / WARN / INFO）
 * @param messageKey  i18n 消息键（面板渲染时取本地化文本）
 * @param messageArgs i18n 参数（如列名、表名等）
 * @param line        警告所在行号（1-based，未知为 -1）
 */
public record PerfWarning(
        @NotNull String ruleId,
        @NotNull PerfSeverity severity,
        @NotNull String messageKey,
        @NotNull List<String> messageArgs,
        int line
) {

    /**
     * 省略 {@code messageArgs} 与 {@code line} 的构造，等价于 Kotlin 默认参数
     * {@code messageArgs = emptyList()}、{@code line = -1}。
     */
    public PerfWarning(@NotNull String ruleId, @NotNull PerfSeverity severity, @NotNull String messageKey) {
        this(ruleId, severity, messageKey, List.of(), -1);
    }
}
