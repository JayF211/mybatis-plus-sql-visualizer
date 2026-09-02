package cn.song.mybatisplus.visualizer.share.format;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 可执行 SQL 格式化器（F-8 Phase C）。
 *
 * <p>复用 {@link ParameterReplacer#buildExecutableSql(String, List, Map, List)} 将 {@code ?} 替换为带类型处理的字面值，
 * 额外提供 MR/PR 评论模板（Code Review 场景贴可执行 SQL + 来源）。
 */
public final class ExecutableSqlFormatter {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final ExecutableSqlFormatter INSTANCE = new ExecutableSqlFormatter();

    private ExecutableSqlFormatter() {
    }

    /**
     * 生成单行可执行 SQL（替换 {@code ?} 为字面值）。
     *
     * @param sql         含 {@code ?} 的 SQL
     * @param sources     参数来源列表（与 {@code ?} 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param hints       回填提示（可空，走启发式）
     * @return 可执行 SQL
     */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable List<ParamHint> hints) {
        return ParameterReplacer.buildExecutableSql(sql, sources, paramValues, hints);
    }

    /** 便捷重载：无回填提示时调用（对应 Kotlin 默认值 {@code hints = emptyList()}）。 */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues) {
        return format(sql, sources, paramValues, List.of());
    }

    /**
     * 生成 MR/PR 评论格式（可执行 SQL + 来源前缀）。
     *
     * <p>模板：
     * <pre>
     * [SQL Review] UserService.java:42
     * ```sql
     * SELECT id, name FROM user WHERE name = 'zhangsan'
     * ```
     * </pre>
     *
     * @param sql        含 {@code ?} 的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param source     来源位置（文件名:行号，可 null）
     * @param prefix     标题前缀（默认 {@code [SQL Review]}）
     * @param hints      回填提示
     * @return MR/PR 评论文本
     */
    @NotNull
    public static String formatAsMrComment(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String source,
            @NotNull String prefix,
            @Nullable List<ParamHint> hints) {
        String executable = format(sql, sources, paramValues, hints);
        StringBuilder sb = new StringBuilder(256);
        sb.append(prefix);
        if (source != null) {
            sb.append(' ').append(source);
        }
        sb.append("\n```sql\n");
        sb.append(rtrim(executable));
        sb.append("\n```");
        return sb.toString();
    }

    /** 便捷重载：默认前缀与空提示（对应 Kotlin 默认值）。 */
    @NotNull
    public static String formatAsMrComment(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String source) {
        return formatAsMrComment(sql, sources, paramValues, source, "[SQL Review]", List.of());
    }

    private static String rtrim(@NotNull String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) <= ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
