package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType

/**
 * SQL 预览 token 类型（M8-01）。
 *
 * 仅供 SqlLexer / SqlFormatter / HTML 渲染使用，不作为真正的语言解析器。
 * language 参数使用 [Language.ANY]（这些 token 不参与 PSI 树构建）。
 */
object SqlTokenTypes {
    val KEYWORD = IElementType("MP_SQL_KEYWORD", Language.ANY)
    val IDENTIFIER = IElementType("MP_SQL_IDENTIFIER", Language.ANY)

    /** 表名 / 表别名（FROM/JOIN/INTO/UPDATE 后由词法器启发式标记）。 */
    val TABLE = IElementType("MP_SQL_TABLE", Language.ANY)

    val STRING = IElementType("MP_SQL_STRING", Language.ANY)
    val NUMBER = IElementType("MP_SQL_NUMBER", Language.ANY)
    val COMMENT = IElementType("MP_SQL_COMMENT", Language.ANY)
    val OPERATOR = IElementType("MP_SQL_OPERATOR", Language.ANY)
    val PAREN = IElementType("MP_SQL_PAREN", Language.ANY)
}
