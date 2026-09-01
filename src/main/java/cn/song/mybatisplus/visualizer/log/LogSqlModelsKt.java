package cn.song.mybatisplus.visualizer.log;

import java.util.stream.Collectors;

/**
 * 日志条目渲染回 MyBatis 日志文本（T-M38）的扩展函数载体。
 */
public final class LogSqlModelsKt {
    private LogSqlModelsKt() {}

    /** 条目渲染回 MyBatis 日志文本。 */
    public static String toLogText(LogSqlEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("==>  Preparing: ").append(entry.rawSql());
        if (!entry.params().isEmpty()) {
            sb.append('\n').append("==> Parameters: ");
            sb.append(entry.params().stream().map(LogSqlModelsKt::toParamText).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    /** 单个参数渲染回日志形态：1(Long) / abc(String) / null。 */
    private static String toParamText(LogSqlParam p) {
        String v = p.value();
        if (v == null) return "null";
        return p.type() != null ? v + "(" + p.type() + ")" : v;
    }
}
