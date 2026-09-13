package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType
import com.intellij.ui.SimpleTextAttributes

/**
 * SQL 列表显示模式（T-ListMode）：SQL 日志/历史列表条目中 SQL 行的展示形态。
 *
 * @property SINGLE_LINE 单行原文（T-TwoLine 双行结构下的形态，列表固定行高走 O(1) 布局快路径）
 * @property KEYWORD_WRAP 关键字换行——子句顶格换行、同行内容压一行（默认，长 SQL 快速总览）
 * @property FORMATTED 格式化——多行缩进全展开（阅读细节）
 */
enum class SqlListDisplayMode {
    SINGLE_LINE,
    KEYWORD_WRAP,
    FORMATTED,
}

/** 按显示模式变换 SQL 文本（渲染前调用；变换器复用 [SqlFormatter] 既有实现，字符串原文保留）。 */
internal fun prepareSqlForList(sql: String, mode: SqlListDisplayMode): String = when (mode) {
    SqlListDisplayMode.SINGLE_LINE -> sql
    SqlListDisplayMode.KEYWORD_WRAP -> SqlFormatter.formatClauseLine(sql)
    SqlListDisplayMode.FORMATTED -> SqlFormatter.format(sql)
}

/**
 * 着色文本片段（T-LogListFreeze-04）：列表条目渲染数据单元——文本 + 属性，供
 * [com.intellij.ui.SimpleColoredComponent.append] 直接消费。
 * 取代原 HTML `<span>` 通道：渲染成本从「BasicHTML 解析 + CSS 属性解析 + 文档布局」
 * 降为轻量文本测量，文本原样保留（无 HTML 转义面，注入场景不复存在）。
 */
data class SqlTextFragment(val text: String, val attributes: SimpleTextAttributes)

/**
 * 列表条目 SQL 着色片段管线（T-LogListFreeze-04）：SqlLexer 分词 → 每 token 按类型产着色片段。
 * 与原 SqlListHtmlRenderer 同一配色体系（[SqlHighlightColors] 的 TextAttributesKey 经
 * [EditorColorsScheme] 取色，亮暗双主题随 IDE 切换，与预览编辑器一致）；且
 * fontType（关键字加粗）随片段保留——原 HTML span 只携带色值，加粗在列表通道本就丢失。
 * scheme 可注入（纯逻辑可单测：传固定属性 map）。
 */
object SqlListFragmentRenderer {

    /** 按主题 scheme 取属性（跟随 IDE 亮/暗主题，与编辑器高亮一致）。 */
    fun renderFragments(
        sql: String,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<SqlTextFragment> {
        // token → 配色键取自 [SqlListSyntaxHighlighter.ATTR_BY_TYPE]（与编辑器版高亮共用唯一映射，
        // T-ClickPreview-04：列表与单击预览编辑器同一条 SQL 同色）
        val attrByType = SqlListSyntaxHighlighter.ATTR_BY_TYPE.mapValues { (_, key) -> attrOf(scheme, key) }
        return renderFragments(sql) { type -> attrByType[type] }
    }

    /** 产着色片段（无配色类型用 [SimpleTextAttributes.REGULAR_ATTRIBUTES] 走组件前景色）。 */
    fun renderFragments(sql: String, attrOf: (IElementType) -> SimpleTextAttributes?): List<SqlTextFragment> {
        if (sql.isEmpty()) return emptyList()
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length, 0)
        val fragments = mutableListOf<SqlTextFragment>()
        while (true) {
            val type = lexer.tokenType ?: break
            val text = lexer.tokenText
            if (text.isNotEmpty()) {
                fragments += SqlTextFragment(text, attrOf(type) ?: SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            lexer.advance()
        }
        return fragments
    }

    /**
     * 变换 + 分词着色 + 按行拆分（T-ListMode 多行模式）：[prepareSqlForList] 变换后的文本含换行，
     * 按行拆为独立片段序列（每行一个 [SimpleColoredComponent] 承载）。整段只跑一次词法，
     * 行拆分在片段文本内做——含换行的 token（空白）按行切开，属性原样继承。
     */
    fun renderLineFragments(
        sql: String,
        mode: SqlListDisplayMode,
        scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme,
    ): List<List<SqlTextFragment>> {
        val attrByType = SqlListSyntaxHighlighter.ATTR_BY_TYPE.mapValues { (_, key) -> attrOf(scheme, key) }
        return renderLineFragments(sql, mode) { type -> attrByType[type] }
    }

    /** 产多行着色片段（attrOf 注入——纯逻辑可单测，与 [renderFragments] 单行版同一测试缝）。 */
    fun renderLineFragments(
        sql: String,
        mode: SqlListDisplayMode,
        attrOf: (IElementType) -> SimpleTextAttributes?,
    ): List<List<SqlTextFragment>> {
        val all = renderFragments(prepareSqlForList(sql, mode), attrOf)
        val lines = mutableListOf<MutableList<SqlTextFragment>>()
        var current = mutableListOf<SqlTextFragment>()
        lines += current
        for (f in all) {
            if (!f.text.contains('\n')) {
                current += f
                continue
            }
            // 片段文本内嵌换行：按 \n 切开，非末段留在当前行，末段开新行
            val parts = f.text.split('\n')
            parts.forEachIndexed { i, part ->
                if (i > 0) {
                    current = mutableListOf<SqlTextFragment>()
                    lines += current
                }
                if (part.isNotEmpty()) current += SqlTextFragment(part, f.attributes)
            }
        }
        return lines
    }

    private fun attrOf(scheme: EditorColorsScheme, key: TextAttributesKey): SimpleTextAttributes? {
        val ta = scheme.getAttributes(key) ?: return null
        val fg = ta.foregroundColor ?: return null
        return SimpleTextAttributes(ta.fontType, fg)
    }
}
