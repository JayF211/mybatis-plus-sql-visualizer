package cn.song.mybatisplus.visualizer.sql;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 参数替换器（F-02）：处理 SQL 中残留的 {@code ?} 占位符与参数对照表。
 *
 * <p>说明：绝大多数参数值在 psi 层已通过变量追踪（M2-01）内联进 SQL；
 * 残留的 {@code ?} 表示运行时才能确定的值（如方法参数），静态插件无法取得实际值，
 * 因此提供两种兜底：
 * <ul>
 *  <li>来源注释模式：{@link #annotate} 将 {@code ?} 标注其来源（如 {@code ? /*deviceId*}{@code /}）</li>
 *  <li>对照表：{@link #buildParamTable} 生成 {@code -- 参数对照: [deviceId=?, stationId=?]}</li>
 *  <li>可执行 SQL：{@link #buildExecutableSql} 将 {@code ?} 替换为带类型处理的字面值（供复制到外部工具执行）</li>
 * </ul>
 */
public final class ParameterReplacer {

    /** 数值启发式正则（无 {@link ParamHint} 时判定是否加引号）。 */
    private static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ParameterReplacer() {
    }

    /**
     * 将 SQL 中的 {@code ?} 按出现顺序标注来源注释。
     *
     * @param sql    含 {@code ?} 的 SQL
     * @param params 参数来源列表（与 {@code GeneratedSql.unresolvedParams} 对应）
     * @return 标注后的 SQL（{@code ?} 数量多于参数列表时，多余的 {@code ?} 保持原样）
     */
    public static String annotate(String sql, List<String> params) {
        if (params.isEmpty()) return sql;
        int index = 0;
        StringBuilder sb = new StringBuilder(sql.length() + params.size() * 12);
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '?' && index < params.size()) {
                sb.append("? /*").append(params.get(index)).append("*/");
                index++;
            } else {
                sb.append(ch);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * 生成参数对照表（供 UI 展示，如 {@code -- 参数对照: [deviceId=?, stationId=?]}）。
     *
     * @param params 未解析参数来源列表
     * @return 对照表文本；无参数时返回 null
     */
    public static String buildParamTable(List<String> params) {
        if (params.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) body.append(", ");
            body.append(params.get(i)).append("=?");
        }
        return "-- 参数对照: [" + body + "]";
    }

    /**
     * 将 SQL 中的 {@code ?} 占位符替换为带类型处理的字面值，生成可执行 SQL（供复制到 DBeaver/DataGrip 等工具直接执行）。
     *
     * <p>替换规则（与面板回填 {@code SqlPreviewPanel} 一致）：
     * <ul>
     *  <li>值未填写（空/空白）→ 保留 {@code ?}</li>
     *  <li>{@link ParamHint#getQuote()} = true → 单引号包裹（String/日期列）</li>
     *  <li>{@link ParamHint#getQuote()} = false → 原样渲染（数值列）</li>
     *  <li>{@link ParamHint#getQuote()} = null → 数值启发式（纯数字原样，否则引号包裹）</li>
     *  <li>值中单引号转义为 {@code ''}（防 SQL 注入，与面板回填一致）</li>
     * </ul>
     *
     * @param sql         含 {@code ?} 的 SQL（{@code ?} 顺序与 {@code sources} 一一对应）
     * @param sources     参数来源列表（与 SQL 中 {@code ?} 按出现顺序对应）
     * @param paramValues 参数值映射（source → 用户输入值）
     * @param hints       与 {@code sources} 并行的回填提示（引号规则）；缺失走启发式
     * @return 替换后的可执行 SQL
     */
    public static String buildExecutableSql(
            String sql,
            List<String> sources,
            Map<String, String> paramValues,
            List<ParamHint> hints) {
        if (sources.isEmpty()) return sql;
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '?') {
                String source = paramIndex < sources.size() ? sources.get(paramIndex) : null;
                String value = source != null ? paramValues.get(source) : null;
                sb.append(renderValue(value, paramIndex < hints.size() ? hints.get(paramIndex) : null));
                paramIndex++;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    /** 对应 Kotlin 默认参数 {@code hints = emptyList()} 的重载。 */
    public static String buildExecutableSql(
            String sql,
            List<String> sources,
            Map<String, String> paramValues) {
        return buildExecutableSql(sql, sources, paramValues, List.of());
    }

    /**
     * 单值渲染：按 {@link ParamHint#getQuote()} 决定引号规则；值中单引号转义为 {@code ''}。
     * 空值/空白 → 返回 {@code ?}（保留占位符）。
     */
    private static String renderValue(String value, ParamHint hint) {
        if (value == null || value.trim().isEmpty()) return "?";
        String escaped = value.replace("'", "''");
        if (hint != null && Boolean.TRUE.equals(hint.getQuote())) {
            return "'" + escaped + "'";
        }
        if (hint != null && Boolean.FALSE.equals(hint.getQuote())) {
            return value.trim();
        }
        String trimmed = value.trim();
        return NUMBER_REGEX.matcher(trimmed).matches() ? trimmed : "'" + escaped + "'";
    }
}
