package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import java.lang.SuppressWarnings;

import java.awt.Color;
import java.awt.Font;

/**
 * SQL 预览语法高亮配色（M8-01）。
 *
 * 颜色用 {@link JBColor}（亮/暗主题各一组）+ {@link TextAttributesKey} 注册，随 IDE
 * 主题切换自动生效。默认配色对齐参考图暗色效果（One Dark 风格）：
 * 关键字紫、表名/别名洋红、列名青蓝、字符串绿、数字橙、注释灰。
 *
 * 注意：{@link JBColor} 构造参数顺序为 **(亮色, 暗色)**（light, dark），
 * 与直觉相反，实测于 2025.3.5 平台（getColor 暗主题返回第二参）。
 */
public final class SqlHighlightColors {

    public static final SqlHighlightColors INSTANCE = new SqlHighlightColors();

    public static final TextAttributesKey KEYWORD = key("MP_SQL.KEYWORD", new Color(0x000080), new Color(0xC678DD), true);

    public static final TextAttributesKey TABLE = key("MP_SQL.TABLE", new Color(0x800080), new Color(0xD55FDE), false);

    public static final TextAttributesKey IDENTIFIER = key("MP_SQL.IDENTIFIER", new Color(0x0451A5), new Color(0x61AFEF), false);

    public static final TextAttributesKey STRING = key("MP_SQL.STRING", new Color(0x008000), new Color(0x98C379), false);

    public static final TextAttributesKey NUMBER = key("MP_SQL.NUMBER", new Color(0x098658), new Color(0xD19A66), false);

    public static final TextAttributesKey COMMENT = key("MP_SQL.COMMENT", new Color(0x808080), new Color(0x7F848E), false);

    public static final TextAttributesKey OPERATOR = key("MP_SQL.OPERATOR", new Color(0x000000), new Color(0xABB2BF), false);

    public static final TextAttributesKey PAREN = key("MP_SQL.PAREN", new Color(0x000000), new Color(0xABB2BF), false);

    // T-M52：createTextAttributesKey(id, TextAttributes) 废弃，新 API createTextAttributesKey(id, TextAttributesKey)
    // 以已有 key 为 fallback 模板，不适用本场景——插件注册**固定自定义配色**（One Dark 风格），
    // 平台暂无等价"注册任意默认属性"API，弃用非移除，Suppress 保留。
    @SuppressWarnings("deprecation")
    private static TextAttributesKey key(String id, Color light, Color dark, boolean bold) {
        TextAttributes attrs = new TextAttributes();
        attrs.setForegroundColor(new JBColor(light, dark));
        attrs.setFontType(bold ? Font.BOLD : Font.PLAIN);
        return TextAttributesKey.createTextAttributesKey(id, attrs);
    }

    private SqlHighlightColors() {
    }
}
