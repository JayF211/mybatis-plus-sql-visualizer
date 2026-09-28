package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType

/** T-XmlSynHi D1：注入语言的 token 类型（lexer-only，无 PSI 元素）。 */
object SqlMapperTT {
    @JvmField val KEYWORD = IElementType("MPSQL_KEYWORD", SqlMapperLanguage)
    @JvmField val STRING = IElementType("MPSQL_STRING", SqlMapperLanguage)
    @JvmField val COMMENT = IElementType("MPSQL_COMMENT", SqlMapperLanguage)
    @JvmField val PARAM = IElementType("MPSQL_PARAM", SqlMapperLanguage) // #{…} / ${…} 整体（决策 3-A）
    @JvmField val NUMBER = IElementType("MPSQL_NUMBER", SqlMapperLanguage)
    @JvmField val IDENTIFIER = IElementType("MPSQL_IDENTIFIER", SqlMapperLanguage)
    @JvmField val OPERATOR = IElementType("MPSQL_OPERATOR", SqlMapperLanguage)
    @JvmField val WHITE_SPACE = IElementType("MPSQL_WHITE_SPACE", SqlMapperLanguage)
}
