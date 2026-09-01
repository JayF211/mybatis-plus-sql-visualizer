package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code MyBatisLogParser.kt} 顶层函数（{@code parseParams(String)}）的 Java 承载类。
 */
public final class MyBatisLogParserKt {
    private MyBatisLogParserKt() {
    }

    /** `null` 分支在前（见 {@link #parseParams(String)} KDoc）；`\A`/`\z` 锚定避免跨参数误配。 */
    private static final Pattern PARAM_REGEX = Pattern.compile(
            "(?:\\A|,\\s*)(null)(?=,\\s*|\\z)|(?:\\A|,\\s*)(.+?)\\(([A-Za-z_$][\\w$]*(?:\\[])*)\\)(?=,\\s*|\\z)");

    /**
     * 解析 `Parameters:` 行参数文本（T-M31-02 依赖）：`1(Long), abc(String), null` →
     * [(1, Long), (abc, String), (null, null)]。
     *
     * 非贪婪正则 + 右边界断言：值可含逗号/括号（`a, b(String)`、`a(x)(String)`）；
     * `null` 字面量无类型标注（须先于一般分支匹配，防 `null, 1(Integer)` 被吞成 `null, 1`）。
     * 全无 `(类型)` 标注时（旧版/第三方日志）回落逗号粗分，类型 null（还原器启发式判定引号）。
     */
    public static List<LogSqlParam> parseParams(String text) {
        List<LogSqlParam> result = new ArrayList<>();
        Matcher m = PARAM_REGEX.matcher(text);
        while (m.find()) {
            String nullLiteral = m.group(1);
            if (nullLiteral != null) {
                result.add(new LogSqlParam(null, null));
                continue;
            }
            String v = m.group(2);
            String t = m.group(3);
            result.add(new LogSqlParam(v != null ? v.trim() : null, t));
        }
        if (result.isEmpty() && !text.isBlank()) {
            for (String part : text.split(",")) {
                String trimmed = part.trim();
                result.add(new LogSqlParam(trimmed.isEmpty() ? null : trimmed, null));
            }
        }
        return result;
    }
}
