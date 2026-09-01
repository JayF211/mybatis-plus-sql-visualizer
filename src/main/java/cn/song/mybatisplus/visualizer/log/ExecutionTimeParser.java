package cn.song.mybatisplus.visualizer.log;

import java.util.regex.Pattern;

/**
 * 执行耗时解析器（O-1 Phase B，纯逻辑，可独立单测）：从日志文本中解析执行耗时（毫秒）。
 *
 * 支持格式（正则匹配，< 1ms）：
 * - **p6spy**：`elapsed 123 ms` / `consumed 456ms`
 * - **MP TimeCount**：`TimeCount: 789ms` / `time: 100 ms`
 * - **自定义前缀**：通过 `logTimePrefix` 配置（如 `Time:`）+ 数字 + `ms`
 *
 * 解析策略：先尝试通用格式（p6spy/TimeCount），再尝试自定义前缀。都不命中返回 null。
 */
public final class ExecutionTimeParser {

    public static final ExecutionTimeParser INSTANCE = new ExecutionTimeParser();

    private ExecutionTimeParser() {
    }

    /** p6spy elapsed/consumed 格式：`elapsed 123 ms` / `consumed 456ms`（数字前后可有空格，ms 可选）。 */
    private static final Pattern P6SPY_REGEX = Pattern.compile("(?i)\\b(?:elapsed|consumed)\\s+(\\d+)\\s*ms?\\b");

    /** MP TimeCount 格式：`TimeCount: 789ms` / `time: 100 ms`。 */
    private static final Pattern TIMECOUNT_REGEX = Pattern.compile("(?i)\\bTimeCount\\s*[:=]\\s*(\\d+)\\s*ms?\\b");

    /** 通用 `time` 关键字格式：`time: 100 ms` / `time 200ms`。 */
    private static final Pattern GENERIC_TIME_REGEX = Pattern.compile("(?i)\\btime\\s*[:=]?\\s+(\\d+)\\s*ms?\\b");

    /**
     * 从日志文本中解析执行耗时（毫秒）。
     *
     * 解析顺序：p6spy → TimeCount → 通用 time 格式 → 自定义前缀。
     *
     * @param text         日志文本（可能含多行）
     * @param customPrefix 自定义耗时前缀（如 `Time:`）；null 时跳过自定义匹配
     * @return 耗时毫秒；无法解析返回 null
     */
    public static Long parse(String text) {
        return parse(text, null);
    }

    /**
     * 从日志文本中解析执行耗时（毫秒）。
     *
     * @param text         日志文本（可能含多行）
     * @param customPrefix 自定义耗时前缀（如 `Time:`）；null 时跳过自定义匹配
     * @return 耗时毫秒；无法解析返回 null
     */
    public static Long parse(String text, String customPrefix) {
        if (text.isBlank()) {
            return null;
        }

        // 1. p6spy elapsed/consumed
        java.util.regex.Matcher m1 = P6SPY_REGEX.matcher(text);
        if (m1.find()) {
            return toLongOrNull(m1.group(1));
        }
        // 2. MP TimeCount
        java.util.regex.Matcher m2 = TIMECOUNT_REGEX.matcher(text);
        if (m2.find()) {
            return toLongOrNull(m2.group(1));
        }
        // 3. 通用 time 格式
        java.util.regex.Matcher m3 = GENERIC_TIME_REGEX.matcher(text);
        if (m3.find()) {
            return toLongOrNull(m3.group(1));
        }
        // 4. 自定义前缀（动态构建正则）
        if (customPrefix != null && !customPrefix.isBlank()) {
            String escaped = Pattern.quote(customPrefix);
            Pattern dynamicRegex = Pattern.compile("(?i)" + escaped + "\\s*(\\d+)\\s*ms?\\b");
            java.util.regex.Matcher m4 = dynamicRegex.matcher(text);
            if (m4.find()) {
                return toLongOrNull(m4.group(1));
            }
        }
        return null;
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
