package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType

/**
 * SQL 预览用的轻量语言与 token 类型（M8-01）。
 *
 * 仅供预览区语法高亮使用，不作为真正的语言解析器；token 类别以本插件
 * 产出的 SELECT/UPDATE/DELETE + MPJ 联表 SQL 形态为限。
 */
object SqlPreviewLanguage : Language("MP_SQL_Preview")

/** [SqlLexer] 产生的 token 类别（高亮器按此分发颜色）。 */
object SqlTokenTypes {
    val KEYWORD = IElementType("MP_SQL_KEYWORD", SqlPreviewLanguage)
    val IDENTIFIER = IElementType("MP_SQL_IDENTIFIER", SqlPreviewLanguage)

    /** 表名 / 表别名（FROM/JOIN/INTO/UPDATE 后由词法器启发式标记）。 */
    val TABLE = IElementType("MP_SQL_TABLE", SqlPreviewLanguage)

    val STRING = IElementType("MP_SQL_STRING", SqlPreviewLanguage)
    val NUMBER = IElementType("MP_SQL_NUMBER", SqlPreviewLanguage)
    val COMMENT = IElementType("MP_SQL_COMMENT", SqlPreviewLanguage)
    val OPERATOR = IElementType("MP_SQL_OPERATOR", SqlPreviewLanguage)
    val PAREN = IElementType("MP_SQL_PAREN", SqlPreviewLanguage)
}
