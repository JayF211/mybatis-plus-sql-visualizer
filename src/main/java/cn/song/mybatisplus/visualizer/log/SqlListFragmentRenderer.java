package cn.song.mybatisplus.visualizer.log;

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

    private static SimpleTextAttributes attrOf(EditorColorsScheme scheme, TextAttributesKey key) {
        TextAttributes ta = scheme.getAttributes(key);
        if (ta == null || ta.getForegroundColor() == null) return null;
        return new SimpleTextAttributes(ta.getFontType(), ta.getForegroundColor());
    }
}
