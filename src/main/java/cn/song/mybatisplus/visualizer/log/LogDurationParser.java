package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 耗时解析器（P-06，纯逻辑，可独立单测）：尝试从条目中解析耗时（毫秒）。
 *
 * 支持格式：
 * - **p6spy**：`1723456789012|123|statement|select ...`（`|` 分隔第二段为毫秒）
 * - **druid**：rawSql / restoredSql 中含 `executeMillis:456` 或 `"executeMillis":456`
 *
 * 注意：当前 {@link LogSqlEntry} 不含专用耗时字段；若日志格式不含耗时行，本模块返回 null，
 * 排序按钮隐藏（降级）。后续可在桥层扩展 {@link LogSqlEntry} 增加可选 `durationMs` 字段。
 */
public final class LogDurationParser {

    public static final LogDurationParser INSTANCE = new LogDurationParser();

    private LogDurationParser() {
    }

    /** p6spy 格式正则：`|123|statement|` 或 `|123|result|`（第二段为毫秒整数）。 */
    private static final Pattern P6SPY_REGEX = Pattern.compile("\\|(\\d{1,})\\|");

    /** druid 格式正则：`executeMillis:456` 或 `"executeMillis":456`。 */
    private static final Pattern DRUID_REGEX = Pattern.compile("(?i)executeMillis[\"']?\\s*[:=]\\s*(\\d+)");

    /**
     * 尝试从条目中解析耗时（毫秒）。
     *
     * 解析顺序：先检测 p6spy 格式（rawSql 中含 `|` 分隔耗时），再检测 druid 格式。
     * 都不命中返回 null。
     *
     * @param entry 日志 SQL 条目
     * @return 耗时毫秒；无法解析返回 null
     */
    public static Long parse(LogSqlEntry entry) {
        // 优先：p6spy 格式（rawSql 中含 `时间戳|毫秒|statement|SQL`）
        String raw = entry.rawSql();
        if (raw.contains("|")) {
            List<String> groups = new ArrayList<>();
            Matcher m = P6SPY_REGEX.matcher(raw);
            while (m.find()) {
                groups.add(m.group(1));
            }
            // p6spy 格式：`1723456789012|123|statement|SQL`——第二段为耗时
            if (groups.size() >= 2) {
                Long ms = toLongOrNull(groups.get(1));
                if (ms != null && ms >= 0) {
                    return ms;
                }
            }
            // fallback：第一段如果是小数字（毫秒）
            if (!groups.isEmpty()) {
                Long firstMs = toLongOrNull(groups.get(0));
                if (firstMs != null && firstMs >= 0 && firstMs < 100_000_000L) {
                    return firstMs;
                }
            }
        }
        // 次选：druid 格式（先 rawSql，再 restoredSql）
        Matcher druidMatch = DRUID_REGEX.matcher(raw);
        if (!druidMatch.find()) {
            druidMatch = DRUID_REGEX.matcher(entry.restoredSql());
            if (!druidMatch.find()) {
                druidMatch = null;
            }
        }
        if (druidMatch != null) {
            Long ms = toLongOrNull(druidMatch.group(1));
            if (ms != null && ms >= 0) {
                return ms;
            }
        }
        return null;
    }

    /**
     * 检测条目是否含耗时信息（用于决定是否显示排序按钮）。
     *
     * @param entry 日志 SQL 条目
     * @return true = 含耗时信息
     */
    public static boolean hasDurationInfo(LogSqlEntry entry) {
        return parse(entry) != null;
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
