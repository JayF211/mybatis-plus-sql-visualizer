package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MP 占位符展开辅助（T-M26-B，P-立项-03）：${变量.customSqlSegment} / ${变量.sqlSelect} / ${变量.sqlSet}
 * 的识别与 SQL 片段提取。纯逻辑（无 PSI），Java 侧关联在 cn.song.mybatisplus.visualizer.ui.XmlEwResolver。
 *
 * 片段语义对齐 MP（AbstractWrapper）：
 * - customSqlSegment → WHERE (条件...)（wrapper 条件非空时；空则整段空串）
 * - sqlSelect → SELECT 列清单（SELECT 与首个 FROM 之间）
 * - sqlSet → UPDATE 的 SET 片段（SET 与 WHERE 之间）
 */
public final class XmlEwExpander {

    public static final XmlEwExpander INSTANCE = new XmlEwExpander();

    /** ${变量.片段类型} 占位识别（customSqlSegment/sqlSelect/sqlSet）。 */
    private static final Pattern EW_PLACEHOLDER_REGEX = Pattern.compile("\\$\\{(\\w+)\\.(customSqlSegment|sqlSelect|sqlSet)\\}");

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private XmlEwExpander() {
    }

    /** 扫描文本中的占位（去重，保持出现顺序）。 */
    public static List<EwPlaceholder> placeholders(String text) {
        Set<EwPlaceholder> seen = new LinkedHashSet<>();
        Matcher m = EW_PLACEHOLDER_REGEX.matcher(text);
        while (m.find()) {
            seen.add(new EwPlaceholder(m.group(1), m.group(2)));
        }
        return new ArrayList<>(seen);
    }

    /** customSqlSegment 片段：WHERE (条件)。 */
    public static String extractWhere(String sql) {
        String compact = WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim();
        int idx = compact.indexOf(" WHERE ");
        if (idx < 0) {
            return null;
        }
        String wherePart = compact.substring(idx + " WHERE ".length()).trim();
        return wherePart.isEmpty() ? null : "WHERE (" + wherePart + ")";
    }

    /** sqlSelect 片段：SELECT 列清单（首个 FROM 之前）。 */
    public static String extractSelect(String sql) {
        String compact = WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim();
        int start = compact.indexOf("SELECT ");
        if (start < 0) {
            return null;
        }
        int fromIdx = compact.indexOf(" FROM ", start);
        if (fromIdx < 0) {
            return null;
        }
        return compact.substring(start + "SELECT ".length(), fromIdx).trim();
    }

    /** sqlSet 片段：UPDATE 的 SET 内容（SET 与 WHERE 之间）。 */
    public static String extractSet(String sql) {
        String compact = WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim();
        int start = compact.indexOf("SET ");
        if (start < 0) {
            return null;
        }
        int whereIdx = compact.indexOf(" WHERE ", start);
        int end = whereIdx > 0 ? whereIdx : compact.length();
        return compact.substring(start + "SET ".length(), end).trim();
    }
}
