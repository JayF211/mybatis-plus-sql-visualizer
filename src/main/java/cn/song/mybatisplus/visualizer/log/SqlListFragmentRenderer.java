package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.SimpleTextAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 分词与分行可缓存；着色按当前 scheme 投影，不将主题属性存回条目。 */
public final class SqlListFragmentRenderer {
    private SqlListFragmentRenderer() {
    }

    public static String prepareSqlForList(String sql, SqlListDisplayMode mode) {
        return switch (mode) {
            case KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql);
            case FORMATTED -> SqlFormatter.format(sql);
            default -> sql;
        };
    }

    /**
     * R60（T-LaneB D96）：列表展示文本按脱敏态取值——展示层打码、原始条目不动。
     * FORMATTED 直接对还原 SQL 打码；非 FORMATTED 且脱敏开时对完整 SQL 先打码再截断摘要
     * （避免旧摘要在字面量引号中间截断后漏打码），结果缓存进条目。
     */
    public static String displaySqlForList(LogSqlEntry entry, SqlListDisplayMode mode, boolean masked) {
        if (mode == SqlListDisplayMode.FORMATTED) {
            return cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(entry.restoredSql(), masked);
        }
        if (!masked) return entry.summary();
        String cached = entry.cachedMaskedSummary();
        if (cached != null) return cached;
        String maskedSummary = LogSqlEntry.summarizeSql(
                cn.song.mybatisplus.visualizer.util.DisplayMasker.maskSqlText(entry.restoredSql()));
        entry.setCachedMaskedSummary(maskedSummary);
        return maskedSummary;
    }

    public static List<SqlSemanticFragment> semanticFragments(String sql) {
        if (sql.isEmpty()) return Collections.emptyList();
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        List<SqlSemanticFragment> fragments = new ArrayList<>();
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) break;
            String text = lexer.getTokenText();
            if (!text.isEmpty()) fragments.add(new SqlSemanticFragment(text, type));
            lexer.advance();
        }
        return fragments;
    }

    public static List<List<SqlSemanticFragment>> semanticLineFragments(String sql, SqlListDisplayMode mode) {
        List<List<SqlSemanticFragment>> lines = new ArrayList<>();
        List<SqlSemanticFragment> current = new ArrayList<>();
        lines.add(current);
        for (SqlSemanticFragment fragment : semanticFragments(prepareSqlForList(sql, mode))) {
            if (!fragment.text().contains("\n")) {
                current.add(fragment);
                continue;
            }
            // Java 默认 split 会丢尾部空串；-1 保留 Kotlin 的末尾空行语义。
            String[] parts = fragment.text().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    current = new ArrayList<>();
                    lines.add(current);
                }
                if (!parts[i].isEmpty()) current.add(new SqlSemanticFragment(parts[i], fragment.tokenType()));
            }
        }
        return lines;
    }

    public static List<SqlTextFragment> projectFragments(List<SqlSemanticFragment> fragments) {
        return projectFragments(fragments, EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<SqlTextFragment> projectFragments(List<SqlSemanticFragment> fragments,
            EditorColorsScheme scheme) {
        return projectFragments(fragments, attributesFor(scheme));
    }

    public static List<List<SqlTextFragment>> projectLineFragments(List<List<SqlSemanticFragment>> lines) {
        return projectLineFragments(lines, EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<List<SqlTextFragment>> projectLineFragments(List<List<SqlSemanticFragment>> lines,
            EditorColorsScheme scheme) {
        Function<IElementType, SimpleTextAttributes> attributes = attributesFor(scheme);
        List<List<SqlTextFragment>> result = new ArrayList<>(lines.size());
        for (List<SqlSemanticFragment> line : lines) result.add(projectFragments(line, attributes));
        return result;
    }

    /** 保留旧单段渲染接口及属性注入接口。 */
    public static List<SqlTextFragment> renderFragments(String sql) {
        return renderFragments(sql, EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<SqlTextFragment> renderFragments(String sql, EditorColorsScheme scheme) {
        return projectFragments(semanticFragments(sql), scheme);
    }

    public static List<SqlTextFragment> renderFragments(String sql, Function<IElementType, SimpleTextAttributes> attrOf) {
        return projectFragments(semanticFragments(sql), attrOf);
    }

    public static List<List<SqlTextFragment>> renderLineFragments(String sql, SqlListDisplayMode mode) {
        return renderLineFragments(sql, mode, EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<List<SqlTextFragment>> renderLineFragments(
            String sql, SqlListDisplayMode mode, EditorColorsScheme scheme) {
        return projectLineFragments(semanticLineFragments(sql, mode), scheme);
    }

    public static List<List<SqlTextFragment>> renderLineFragments(
            String sql, SqlListDisplayMode mode, Function<IElementType, SimpleTextAttributes> attrOf) {
        List<List<SqlTextFragment>> result = new ArrayList<>();
        for (List<SqlSemanticFragment> line : semanticLineFragments(sql, mode)) {
            result.add(projectFragments(line, attrOf));
        }
        return result;
    }

    private static List<SqlTextFragment> projectFragments(List<SqlSemanticFragment> fragments,
            Function<IElementType, SimpleTextAttributes> attrOf) {
        List<SqlTextFragment> projected = new ArrayList<>(fragments.size());
        for (SqlSemanticFragment fragment : fragments) {
            SimpleTextAttributes attributes = attrOf.apply(fragment.tokenType());
            projected.add(new SqlTextFragment(fragment.text(),
                    attributes != null ? attributes : SimpleTextAttributes.REGULAR_ATTRIBUTES));
        }
        return projected;
    }

    private static Function<IElementType, SimpleTextAttributes> attributesFor(EditorColorsScheme scheme) {
        Map<IElementType, SimpleTextAttributes> byType = new HashMap<>();
        SqlListSyntaxHighlighter.ATTR_BY_TYPE.forEach((type, key) -> byType.put(type, attrOf(scheme, key)));
        return byType::get;
    }

    private static SimpleTextAttributes attrOf(EditorColorsScheme scheme, TextAttributesKey key) {
        TextAttributes ta = scheme.getAttributes(key);
        if (ta == null || ta.getForegroundColor() == null) return null;
        return new SimpleTextAttributes(ta.getFontType(), ta.getForegroundColor());
    }
}
