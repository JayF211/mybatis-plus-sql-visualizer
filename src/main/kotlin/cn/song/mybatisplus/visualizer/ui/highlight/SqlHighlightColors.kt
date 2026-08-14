package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * SQL 预览语法高亮配色（M8-01）。
 *
 * 颜色用 [JBColor]（亮/暗主题各一组）+ [TextAttributesKey] 注册，随 IDE
 * 主题切换自动生效。默认配色对齐参考图暗色效果（One Dark 风格）：
 *  关键字紫、表名/别名洋红、列名青蓝、字符串绿、数字橙、注释灰。
 *
 * 注意：[JBColor] 构造参数顺序为 **(亮色, 暗色)**（light, dark），
 * 与直觉相反，实测于 2025.3.5 平台（getColor 暗主题返回第二参）。
 */
object SqlHighlightColors {

    val KEYWORD = key("MP_SQL.KEYWORD", light = Color(0x000080), dark = Color(0xC678DD), bold = true)
    val TABLE = key("MP_SQL.TABLE", light = Color(0x800080), dark = Color(0xD55FDE))
    val IDENTIFIER = key("MP_SQL.IDENTIFIER", light = Color(0x0451A5), dark = Color(0x61AFEF))
    val STRING = key("MP_SQL.STRING", light = Color(0x008000), dark = Color(0x98C379))
    val NUMBER = key("MP_SQL.NUMBER", light = Color(0x098658), dark = Color(0xD19A66))
    val COMMENT = key("MP_SQL.COMMENT", light = Color(0x808080), dark = Color(0x7F848E))
    val OPERATOR = key("MP_SQL.OPERATOR", light = Color(0x000000), dark = Color(0xABB2BF))
    val PAREN = key("MP_SQL.PAREN", light = Color(0x000000), dark = Color(0xABB2BF))

    // 2025.3 弃用 createTextAttributesKey(id, TextAttributes)：新 API（继承另一 key 的默认属性）
    // 不适用本场景——插件注册**固定自定义配色**（One Dark 风格，默认属性即最终渲染颜色），
    // 平台暂无等价"注册任意默认属性"API，弃用非移除，Suppress 保留语义（M23.9）。
    @Suppress("DEPRECATION")
    private fun key(id: String, light: Color, dark: Color, bold: Boolean = false): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(
            id,
            TextAttributes().apply {
                foregroundColor = JBColor(light, dark)
                fontType = if (bold) Font.BOLD else Font.PLAIN
            },
        )
}
