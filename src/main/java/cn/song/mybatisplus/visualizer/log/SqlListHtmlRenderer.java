package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.psi.tree.IElementType;

import java.awt.Color;
import java.util.Map;
import java.util.function.Function;

/**
 * 列表条目 SQL 着色（T-M31.13/14）：SqlLexer 分词 → 每 token 按类型包 `<span style=color>` 着色。
 *
 * T-M31.14：颜色改从 {@link SqlHighlightColors} 的 TextAttributesKey 经 {@link EditorColorsScheme} 取前景色——
 * **与预览编辑器/预览弹窗同一配色体系（插件自己的 SQL 高亮，One Dark 亮暗双主题随 IDE 切换）**，
 * 不再固定暗色值。scheme 可注入（纯逻辑可单测：传固定色 map）。
 */
public final class SqlListHtmlRenderer {

    public static final SqlListHtmlRenderer INSTANCE = new SqlListHtmlRenderer();

    private SqlListHtmlRenderer() {
    }

    /** 按主题 scheme 取色（跟随 IDE 亮/暗主题，与编辑器高亮一致）。 */
    public static String render(String sql) {
        return render(sql, EditorColorsManager.getInstance().getGlobalScheme());
    }

    /** 按主题 scheme 取色（跟随 IDE 亮/暗主题，与编辑器高亮一致）。 */
    public static String render(String sql, EditorColorsScheme scheme) {
        Map<IElementType, Color> colorByType = Map.of(
                SqlTokenTypes.KEYWORD, scheme.getAttributes(SqlHighlightColors.KEYWORD).getForegroundColor(),
                SqlTokenTypes.TABLE, scheme.getAttributes(SqlHighlightColors.TABLE).getForegroundColor(),
                SqlTokenTypes.IDENTIFIER, scheme.getAttributes(SqlHighlightColors.IDENTIFIER).getForegroundColor(),
                SqlTokenTypes.STRING, scheme.getAttributes(SqlHighlightColors.STRING).getForegroundColor(),
                SqlTokenTypes.NUMBER, scheme.getAttributes(SqlHighlightColors.NUMBER).getForegroundColor(),
                SqlTokenTypes.COMMENT, scheme.getAttributes(SqlHighlightColors.COMMENT).getForegroundColor(),
                SqlTokenTypes.OPERATOR, scheme.getAttributes(SqlHighlightColors.OPERATOR).getForegroundColor(),
                SqlTokenTypes.PAREN, scheme.getAttributes(SqlHighlightColors.PAREN).getForegroundColor()
        );
        return render(sql, type -> {
            Color c = colorByType.get(type);
            return c != null ? toHex(c) : null;
        });
    }

    /** 渲染为 HTML 片段（无 `<html>` 包裹，调用方按需包裹；文本已 HTML 转义防注入）。 */
    public static String render(String sql, Function<IElementType, String> colorOf) {
        if (sql.isEmpty()) {
            return "";
        }
        SqlLexer lexer = new SqlLexer();
        lexer.start(sql, 0, sql.length(), 0);
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        while (true) {
            IElementType type = lexer.getTokenType();
            if (type == null) {
                break;
            }
            String text = lexer.getTokenText();
            if (text != null && !text.isEmpty()) {
                String color = colorOf.apply(type);
                if (color != null) {
                    sb.append("<span style='color:").append(color).append("'>")
                            .append(escapeHtml(text)).append("</span>");
                } else {
                    sb.append(escapeHtml(text));
                }
            }
            lexer.advance();
        }
        return sb.toString();
    }

    private static String toHex(Color color) {
        String r = String.format("%02x", color.getRed());
        String g = String.format("%02x", color.getGreen());
        String b = String.format("%02x", color.getBlue());
        return "#" + r + g + b;
    }

    private static String escapeHtml(String s) {
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>"); // 换行转 <br>（multiline SQL 在列表 cell 中正确渲染）
    }
}
