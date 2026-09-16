package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 命名参数集合值渲染（T-ListParamCollect）：日志里的 Java 集合值（{@code List.toString()} 形态 {@code [a, b]}）
 * 剥离方括号、按元素渲染，避免方括号落入 SQL 字符串字面量。
 *
 * 背景（用户真机实测）：Spring NamedParameterJdbcTemplate 单行日志
 * {@code sql = … IN (:param3) , parameters = {param3=[6b31d693…]}} 此前整体按单个字面量渲染成
 * {@code IN ('[6b31d693…]')}——字符串里带着方括号，SQL 语义错误（集合查询恒为空集）。
 *
 * 边界（钉死，不做猜测式增强）：
 * - <b>只在 SQL 的 {@code IN (...)} 区间内</b>才把 {@code [..]} 按集合形态展开——非 IN 位置的值即使形如
 *   {@code [a]} 也原样按字面量渲染，防误伤普通字符串值。
 * - 本格式<b>不存在</b>「一个命名参数对应多个元素」的形态：{@code sql = …} 行是参数已解析成 SQL 文本
 *   之后打印的，多元素集合运行时会被展开成 {@code IN (:p1, :p2, …)}（每参数一值）。故本类只负责
 *   「方括号不落进 SQL」，不宣称支持「集合传参」。
 * - 元素内含逗号（{@code [a, b]} 这类值本身带逗号的极小概率场景）与嵌套集合（{@code {{a,b}}}）无法可靠
 *   逆解析——如实按逗号切分、不猜测转义，属已知边界。
 *
 * 纯函数零平台依赖，解析器（还原 SQL）与预览识别（参数行预填）共用同一口径。
 *
 * <p>master 的 {@code internal object ListParamRenderer} 的 Java 承载（全 static 形态）。</p>
 */
public final class ListParamRenderer {

    private ListParamRenderer() {
    }

    /**
     * 渲染单个命名参数的值。
     *
     * @param value    日志中的原始值文本（null 表示 null 字面量）
     * @param inClause 该参数是否处于 IN 子句（由 {@link #inClauseParamNames} 预先算好）
     * @param render   单元素字面量渲染函数（解析器与面板各自的口径，保证非本任务语义零变化）
     */
    public static String renderParam(String value, boolean inClause, Function<String, String> render) {
        // 无值（null 字面量）：交回调渲染，与 `?` 路径的 NULL 口径一致
        // （解析器回调把空串渲染为 NULL；面板回调渲染为空走面板既有空值分支）
        if (value == null) {
            return render.apply("");
        }
        if (!inClause) {
            return render.apply(value);
        }
        List<String> elements = splitListElements(value);
        // 非集合形态（无方括号）走单元素路径——与集合展开同分支，保证 `IN (:p)` 单值渲染一致
        if (elements == null) {
            return render.apply(value);
        }
        if (elements.isEmpty()) {
            return "NULL"; // `[]` 空集合：SQL 层无合法表达，按 NULL 处理并呼出方括号
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render.apply(elements.get(i)));
        }
        return sb.toString();
    }

    /**
     * 集合形态判定 + 元素拆分：{@code [a]} → {@code ["a"]}，{@code [a, b]} → {@code ["a", "b"]}，{@code []} → 空列表。
     * 非集合形态（首尾不是 {@code [} {@code ]}）返回 null。
     *
     * 不做引号/转义还原——{@code List.toString()} 产物本身不可可靠逆解析（见类注释边界）。
     */
    public static List<String> splitListElements(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        if (trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            return null;
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : body.split(",")) {
            String element = part.trim();
            if (!element.isEmpty()) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * 扫描 SQL 中的 {@code IN (...)} 子句，收集其内出现的命名参数名（去重保序）。
     *
     * 判定口径：独立的 {@code IN} 关键字（大小写不敏感、词边界）+ 可选空白 + {@code (}，随后字符串感知地
     * 匹配括号区间（单引号字符串内不计括号，{@code ''} 转义），区间内的 {@code :name} 即 IN 位置参数。
     * 与 {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel} 的 {@code detectInClauseParams}
     * （{@code ?} 占位符版本）语义对齐，此处为命名参数版本。
     */
    public static Set<String> inClauseParamNames(String sql) {
        Set<String> result = new LinkedHashSet<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipString(sql, i);
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            if ((c == 'I' || c == 'i') && isInKeyword(sql, i)) {
                int j = i + 2; // 跳过 `IN` 两字符：i 处 'I'、i+1 处 'N'
                while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) {
                    j++;
                }
                if (j < sql.length() && sql.charAt(j) == '(') {
                    collectNamedParamsIn(sql, j, result);
                    i = j;
                }
            }
            i++;
        }
        return result;
    }

    /** 位置 {@code i} 是否为独立 {@code IN} 关键字：{@code i} 处为 {@code I}/{@code i}，{@code i+1} 处为 {@code N}/{@code n}，两侧均为词边界。 */
    private static boolean isInKeyword(String sql, int i) {
        if (i + 1 >= sql.length()) {
            return false;
        }
        char second = sql.charAt(i + 1);
        if (second != 'N' && second != 'n') {
            return false;
        }
        if (i > 0 && isIdentifierChar(sql.charAt(i - 1))) {
            return false;
        }
        int after = i + 2;
        return after >= sql.length() || !isIdentifierChar(sql.charAt(after));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 跳过单引号字符串（{@code ''} 为字符串内转义引号），返回引号后的下标。 */
    private static int skipString(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /** 收集 {@code (} 起始的括号区间内全部 {@code :name}（字符串字面量内的冒号不认）。 */
    private static void collectNamedParamsIn(String sql, int openIdx, Set<String> sink) {
        int depth = 0;
        int i = openIdx;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipString(sql, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return;
                }
            } else if (c == ':' && i + 1 < sql.length() && isIdentifierChar(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < sql.length() && isIdentifierChar(sql.charAt(j))) {
                    j++;
                }
                sink.add(sql.substring(i + 1, j));
                i = j;
                continue;
            }
            i++;
        }
    }
}
