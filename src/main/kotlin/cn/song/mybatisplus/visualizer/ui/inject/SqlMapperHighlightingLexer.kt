package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * T-XmlSynHi D1：手写单趟词法（lexer-only，决策 2-A/3-A）。
 *
 * token 优先级：注释（-- / block）> 字符串（'…'，'' 加倍不截断）> 参数占位（#{…} / ${…} 整体
 * token，决策 3-A；`\#` 转义按普通文本）> 关键字 > 数字 > 标识符 > 单字符 operator/空白。
 * 字符串态内不识别 `#{}`（MyBatis 参数占位不在字符串内出现的主形态；字符串内一切按 STRING）。
 * 未闭合字符串/注释扫到尾（BAD 形态并入 STRING/COMMENT，不产 error token——注入着色不做校验器）。
 */
class SqlMapperHighlightingLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        tokenType = null
        advance()
    }

    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
    override fun getState(): Int = 0 // 无跨 token 状态（单趟无状态机）

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            tokenEnd = endOffset
            return
        }
        val c = buffer[tokenStart]
        tokenType = when {
            c == '-' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '-' -> {
                var i = tokenStart + 2
                while (i < endOffset && buffer[i] != '\n') i++
                tokenEnd = i
                SqlMapperTT.COMMENT
            }
            c == '/' && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '*' -> {
                var i = tokenStart + 2
                while (i + 1 < endOffset && !(buffer[i] == '*' && buffer[i + 1] == '/')) i++
                tokenEnd = minOf(endOffset, i + 2)
                SqlMapperTT.COMMENT
            }
            c == '\'' || c == '"' -> {
                var i = tokenStart + 1
                while (i < endOffset) {
                    if (buffer[i] == '\\' && c == '\'') i += 2 // 反斜杠转义跳过下一字符
                    else if (buffer[i] == c) {
                        // '' 加倍：闭引号后紧跟同引号 → 视作内容继续
                        if (i + 1 < endOffset && buffer[i + 1] == c) i += 2 else break
                    } else i++
                }
                tokenEnd = minOf(endOffset, i + 1)
                SqlMapperTT.STRING
            }
            (c == '#' || c == '$') && tokenStart + 1 < endOffset && buffer[tokenStart + 1] == '{'
                && (tokenStart == 0 || buffer[tokenStart - 1] != '\\') -> {
                // R21-注：\#{ 转义形态不产 PARAM（转义=非真参数，照 MyBatis TokenParser 转义语义）
                val close = buffer.indexOf('}', tokenStart + 2)
                tokenEnd = if (close >= 0) close + 1 else endOffset
                SqlMapperTT.PARAM
            }
            c.isDigit() -> {
                var i = tokenStart
                while (i < endOffset && (buffer[i].isDigit() || buffer[i] == '.')) i++
                tokenEnd = i
                SqlMapperTT.NUMBER
            }
            c.isLetter() || c == '_' -> {
                var i = tokenStart
                while (i < endOffset && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
                tokenEnd = i
                if (buffer.subSequence(tokenStart, i).toString().uppercase() in SqlMapperLanguage.KEYWORDS) {
                    SqlMapperTT.KEYWORD
                } else SqlMapperTT.IDENTIFIER
            }
            c.isWhitespace() -> {
                var i = tokenStart
                while (i < endOffset && buffer[i].isWhitespace()) i++
                tokenEnd = i
                SqlMapperTT.WHITE_SPACE
            }
            else -> {
                tokenEnd = tokenStart + 1
                SqlMapperTT.OPERATOR
            }
        }
    }
}
