package cn.song.mybatisplus.visualizer.share.format;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Markdown 格式生成器（F-8 Phase A）。
 *
 * <p>将预览 SQL + 参数 + 来源信息格式化为 Markdown 代码块，
 * 便于贴到 IM / MR 评论 / 文档中分享。
 *
 * <p>输出形态（参考开发计划 1.2）：
 * <pre>
 * ```sql
 * SELECT id, name FROM user WHERE name = 'zhangsan'
 * ```
 * </pre>
 * &gt; 来源：UserService.java:42 | 方言：MySQL | 2026-08-22 15:30
 */
public final class MarkdownFormatter {

    /** 单例（兼容 Kotlin {@code object} 语义）。 */
    @NotNull
    public static final MarkdownFormatter INSTANCE = new MarkdownFormatter();

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MarkdownFormatter() {
    }

    /**
     * 生成 Markdown 代码块（SQL + 元信息）。
     *
     * @param sql         要展示的 SQL（已格式化）
     * @param sources     参数来源列表（与 SQL 中 {@code ?} 按序对应）
     * @param paramValues 参数值映射（source → 值）
     * @param tableName   目标表名（可 null）
     * @param dialect     方言名称（可 null）
     * @param source      来源位置（文件名:行号，可 null）
     * @return Markdown 文本
     */
    @NotNull
    public static String format(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String tableName,
            @Nullable String dialect,
            @Nullable String source) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("```sql\n");
        sb.append(rtrim(sql));
        sb.append("\n```");

        // 参数对照行
        if (!sources.isEmpty()) {
            StringBuilder filled = new StringBuilder();
            boolean first = true;
            for (String src : sources) {
                if (!first) {
                    filled.append(", ");
                }
                first = false;
                String v = paramValues.get(src);
                if (v == null || v.isEmpty()) {
                    filled.append(src).append("=?");
                } else {
                    filled.append(src).append('=').append(v);
                }
            }
            sb.append("\n> 参数：`[").append(filled).append("]`");
        }

        // 元信息行
        List<String> meta = buildMeta(tableName, dialect, source);
        if (!meta.isEmpty()) {
            sb.append("\n> ").append(String.join(" | ", meta));
        }

        return sb.toString();
    }

    /**
     * 简化调用：无参数、无来源场景（对应 Kotlin 默认值 {@code sources=emptyList(), paramValues=emptyMap()}）。
     */
    @NotNull
    public static String format(
            @NotNull String sql,
            @Nullable String tableName,
            @Nullable String dialect,
            @Nullable String source) {
        return format(sql, List.of(), Map.of(), tableName, dialect, source);
    }

    /**
     * 生成 Markdown 表格（SQL + 参数 + 来源三列对比）。
     *
     * @param sql         要展示的 SQL
     * @param sources     参数来源列表
     * @param paramValues 参数值映射
     * @param tableName   表名
     * @param dialect     方言
     * @param source      来源位置
     * @return Markdown 表格文本
     */
    @NotNull
    public static String formatAsTable(
            @NotNull String sql,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            @Nullable String tableName,
            @Nullable String dialect,
            @Nullable String source) {
        StringBuilder sb = new StringBuilder(256);
        String params;
        if (!sources.isEmpty()) {
            StringBuilder filled = new StringBuilder();
            boolean first = true;
            for (String src : sources) {
                if (!first) {
                    filled.append(", ");
                }
                first = false;
                String v = paramValues.get(src);
                if (v == null || v.isEmpty()) {
                    filled.append(src).append("=?");
                } else {
                    filled.append(src).append('=').append(v);
                }
            }
            params = filled.toString();
        } else {
            params = "-";
        }
        String sourceText = source != null ? source : "-";
        String tableText = tableName != null ? tableName : "-";
        String dialectText = dialect != null ? dialect : "-";
        String timeText = LocalDateTime.now().format(TIMESTAMP_FMT);

        sb.append("| 来源 | SQL | 参数 | 表 | 方言 | 时间 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        sb.append("| ").append(escapeForMdTable(sourceText)).append(" | ");
        sb.append(escapeForMdTable(sql.trim())).append(" | ");
        sb.append(escapeForMdTable(params)).append(" | ");
        sb.append(escapeForMdTable(tableText)).append(" | ");
        sb.append(escapeForMdTable(dialectText)).append(" | ");
        sb.append(escapeForMdTable(timeText)).append(" |");

        return sb.toString();
    }

    /**
     * 转义 Markdown 表格单元格内容：
     * <ul>
     *   <li>换行符 → [br]（保持可读性同时不破坏表格结构）</li>
     *   <li>` → \（避免提前关闭 code span）</li>
     *   <li>| → \|（避免被误认为列分隔符）</li>
     *   <li>\ → \\（优先处理，避免重复转义）</li>
     * </ul>
     */
    @NotNull
    private static String escapeForMdTable(@NotNull String text) {
        return text
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\n", "<br>")
                .replace("\r", "<br>");
    }

    /** 构建元信息片段列表（过滤 null/空）。 */
    @NotNull
    private static List<String> buildMeta(
            @Nullable String tableName,
            @Nullable String dialect,
            @Nullable String source) {
        java.util.List<String> meta = new java.util.ArrayList<>();
        if (source != null) {
            meta.add("来源：" + source);
        }
        if (tableName != null) {
            meta.add("表：" + tableName);
        }
        if (dialect != null) {
            meta.add("方言：" + dialect);
        }
        meta.add(LocalDateTime.now().format(TIMESTAMP_FMT));
        return meta;
    }

    @NotNull
    private static String rtrim(@NotNull String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) <= ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
