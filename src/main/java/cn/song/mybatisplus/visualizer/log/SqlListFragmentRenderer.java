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

/**
 * 列表条目 SQL 着色片段管线（T-LogListFreeze-04）：SqlLexer 分词 → 每 token 按类型产着色片段。
 * 与原 SqlListHtmlRenderer 同一配色体系（SqlHighlightColors 的 TextAttributesKey 经
 * EditorColorsScheme 取色，亮暗双主题随 IDE 切换，与预览编辑器一致）；且
 * fontType（关键字加粗）随片段保留——原 HTML span 只携带色值，加粗在列表通道本就丢失。
 * scheme 可注入（纯逻辑可单测：传固定属性 map）。
 */
public final class SqlListFragmentRenderer {

    private SqlListFragmentRenderer() {
    }

    /**
     * 按显示模式变换 SQL 文本（渲染前调用；变换器复用 {@link SqlFormatter} 既有实现，字符串原文保留）。
     * （Kotlin 顶层函数 prepareSqlForList 的 Java 承载；SqlListDisplayMode 枚举同文件顶层声明已拆出为
     * 同包独立枚举文件。）
     */
    public static String prepareSqlForList(String sql, SqlListDisplayMode mode) {
        switch (mode) {
            case KEYWORD_WRAP:
                return SqlFormatter.formatClauseLine(sql);
            case FORMATTED:
                return SqlFormatter.format(sql);
            default:
                return sql; // SINGLE_LINE
        }
    }

    /** 按主题 scheme 取属性（跟随 IDE 亮/暗主题，与编辑器高亮一致）。 */
    public static List<SqlTextFragment> renderFragments(String sql) {
        return renderFragments(sql, EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<SqlTextFragment> renderFragments(String sql, EditorColorsScheme scheme) {
        // token → 配色键取自 SqlListSyntaxHighlighter.ATTR_BY_TYPE（与编辑器版高亮共用唯一映射，
        // T-ClickPreview-04：列表与单击预览编辑器同一条 SQL 同色）
        Map<IElementType, SimpleTextAttributes> attrByType = new HashMap<>();
        SqlListSyntaxHighlighter.ATTR_BY_TYPE.forEach((type, key) -> attrByType.put(type, attrOf(scheme, key)));
        return renderFragments(sql, attrByType::get);
    }

    /** 产着色片段（无配色类型用 {@link SimpleTextAttributes#REGULAR_ATTRIBUTES} 走组件前景色）。 */
    public static List<SqlTextFragment> renderFragments(String sql, Function<IElementType, SimpleTextAttributes> attrOf) {
        if (sql.isEmpty()) return Collections.emptyList();
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        List<SqlTextFragment> fragments = new ArrayList<>();
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) break;
            String text = lexer.getTokenText();
            if (!text.isEmpty()) {
                SimpleTextAttributes attr = attrOf.apply(type);
                fragments.add(new SqlTextFragment(text, attr != null ? attr : SimpleTextAttributes.REGULAR_ATTRIBUTES));
            }
            lexer.advance();
        }
        return fragments;
    }

    /**
     * 变换 + 分词着色 + 按行拆分（T-ListMode 多行模式）：{@link #prepareSqlForList} 变换后的文本含换行，
     * 按行拆为独立片段序列（每行一个 {@link com.intellij.ui.SimpleColoredComponent} 承载）。整段只跑一次词法，
     * 行拆分在片段文本内做——含换行的 token（空白）按行切开，属性原样继承。
     */
    /** 便捷重载：默认取全局配色（Kotlin 默认参数等价）。 */
    public static List<List<SqlTextFragment>> renderLineFragments(String sql, SqlListDisplayMode mode) {
        return renderLineFragments(sql, mode,
                com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme());
    }

    public static List<List<SqlTextFragment>> renderLineFragments(
            String sql, SqlListDisplayMode mode, EditorColorsScheme scheme) {
        Map<IElementType, SimpleTextAttributes> attrByType = new HashMap<>();
        SqlListSyntaxHighlighter.ATTR_BY_TYPE.forEach((type, key) -> attrByType.put(type, attrOf(scheme, key)));
        return renderLineFragments(sql, mode, attrByType::get);
    }

    /** 产多行着色片段（attrOf 注入——纯逻辑可单测，与 {@link #renderFragments} 单行版同一测试缝）。 */
    public static List<List<SqlTextFragment>> renderLineFragments(
            String sql, SqlListDisplayMode mode, Function<IElementType, SimpleTextAttributes> attrOf) {
        List<SqlTextFragment> all = renderFragments(prepareSqlForList(sql, mode), attrOf);
        List<List<SqlTextFragment>> lines = new ArrayList<>();
        List<SqlTextFragment> current = new ArrayList<>();
        lines.add(current);
        for (SqlTextFragment f : all) {
            if (!f.text().contains("\n")) {
                current.add(f);
                continue;
            }
            // 片段文本内嵌换行：按 \n 切开，非末段留在当前行，末段开新行
            // （split 默认 limit 0 丢弃尾部空串，与 Kotlin split 同语义）
            String[] parts = f.text().split("\n");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    current = new ArrayList<>();
                    lines.add(current);
                }
                if (!parts[i].isEmpty()) {
                    current.add(new SqlTextFragment(parts[i], f.attributes()));
                }
            }
        }
        return lines;
    }

    private static SimpleTextAttributes attrOf(EditorColorsScheme scheme, TextAttributesKey key) {
        TextAttributes ta = scheme.getAttributes(key);
        if (ta == null || ta.getForegroundColor() == null) return null;
        return new SimpleTextAttributes(ta.getFontType(), ta.getForegroundColor());
    }
}
