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
public final class PerfWarning {
    private final @NotNull String ruleId;
    private final @NotNull PerfSeverity severity;
    private final @NotNull String messageKey;
    private final @NotNull List<String> messageArgs;
    private final int line;

    public PerfWarning(@NotNull String ruleId, @NotNull PerfSeverity severity, @NotNull String messageKey, @NotNull List<String> messageArgs, int line) {
        this.ruleId = ruleId;
        this.severity = severity;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
        this.line = line;
    }

    public @NotNull String getRuleId() { return ruleId; }
    public @NotNull String ruleId() { return ruleId; }
    public @NotNull PerfSeverity getSeverity() { return severity; }
    public @NotNull PerfSeverity severity() { return severity; }
    public @NotNull String getMessageKey() { return messageKey; }
    public @NotNull String messageKey() { return messageKey; }
    public @NotNull List<String> getMessageArgs() { return messageArgs; }
    public @NotNull List<String> messageArgs() { return messageArgs; }
    public int getLine() { return line; }
    public int line() { return line; }

/**
     * 省略 {@code messageArgs} 与 {@code line} 的构造，等价于 Kotlin 默认参数
     * {@code messageArgs = emptyList()}、{@code line = -1}。
     */
    public PerfWarning(@NotNull String ruleId, @NotNull PerfSeverity severity, @NotNull String messageKey) {
        this(ruleId, severity, messageKey, List.of(), -1);
    }

    /**
     * 省略 {@code line} 的构造，等价于 Kotlin 默认参数 {@code line = -1}。
     */
    public PerfWarning(@NotNull String ruleId, @NotNull PerfSeverity severity, @NotNull String messageKey, @NotNull List<String> messageArgs) {
        this(ruleId, severity, messageKey, messageArgs, -1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PerfWarning)) return false;
        PerfWarning that = (PerfWarning) o;
        return java.util.Objects.equals(this.ruleId, that.ruleId) && java.util.Objects.equals(this.severity, that.severity) && java.util.Objects.equals(this.messageKey, that.messageKey) && java.util.Objects.equals(this.messageArgs, that.messageArgs) && java.util.Objects.equals(this.line, that.line);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ruleId, severity, messageKey, messageArgs, line);
    }

    @Override
    public String toString() {
        return "PerfWarning[" + "ruleId=" + ruleId + ", " + "severity=" + severity + ", " + "messageKey=" + messageKey + ", " + "messageArgs=" + messageArgs + ", " + "line=" + line + "]";
    }
}
