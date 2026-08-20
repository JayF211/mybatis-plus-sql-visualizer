package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * P-05.1：轻量 JSON 语法高亮器（不依赖 JSON 插件，Community 可用）。
 *
 * 高亮：键（key）/ 字符串值 / 数值 / 布尔 / null / 标点。
 * 基于 [Lexer] 逐字符扫描，映射到平台标准命名色（亮/暗主题自适应）。
 */
class JsonSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = JsonLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        JsonTokenTypes.STRING -> pack(STRING)
        JsonTokenTypes.NUMBER -> pack(NUMBER)
        JsonTokenTypes.BOOLEAN -> pack(KEYWORD)
        JsonTokenTypes.NULL -> pack(KEYWORD)
        JsonTokenTypes.LBRACE, JsonTokenTypes.RBRACE,
        JsonTokenTypes.LBRACKET, JsonTokenTypes.RBRACKET,
        JsonTokenTypes.COMMA, JsonTokenTypes.COLON -> pack(DEFAULT)
        JsonTokenTypes.BAD_CHARACTER -> pack(BAD_CHARACTER)
        else -> emptyArray()
    }

    private companion object {
        val STRING = TextAttributesKey.createTextAttributesKey("JSON_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = TextAttributesKey.createTextAttributesKey("JSON_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val KEYWORD = TextAttributesKey.createTextAttributesKey("JSON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val DEFAULT = TextAttributesKey.createTextAttributesKey("JSON_DEFAULT", DefaultLanguageHighlighterColors.BRACES)
        val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey("JSON_BAD", HighlighterColors.BAD_CHARACTER)
    }
}

/** JSON 词法单元类型。 */
object JsonTokenTypes {
    val STRING = IElementType("JSON_STRING", null)
    val NUMBER = IElementType("JSON_NUMBER", null)
    val BOOLEAN = IElementType("JSON_BOOLEAN", null)
    val NULL = IElementType("JSON_NULL", null)
    val LBRACE = IElementType("JSON_LBRACE", null)
    val RBRACE = IElementType("JSON_RBRACE", null)
    val LBRACKET = IElementType("JSON_LBRACKET", null)
    val RBRACKET = IElementType("JSON_RBRACKET", null)
    val COMMA = IElementType("JSON_COMMA", null)
    val COLON = IElementType("JSON_COLON", null)
    val BAD_CHARACTER = TokenType.BAD_CHARACTER
    val WHITESPACE = TokenType.WHITE_SPACE
}

/**
 * 轻量 JSON 词法扫描器：逐字符识别 JSON token。
 * 仅支持扁平结构（参数导入场景足够），不校验语法正确性。
 */
class JsonLexer : Lexer() {

    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var currentPos = 0
    private var currentToken: IElementType? = null
    private var tokenStart = 0
    private var tokenEnd = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.currentPos = startOffset
        advance()
    }

    override fun advance() {
        tokenStart = currentPos
        if (currentPos >= endOffset) {
            currentToken = null
            tokenEnd = currentPos
            return
        }
        when (val c = buffer[currentPos]) {
            '{' -> { currentToken = JsonTokenTypes.LBRACE; tokenEnd = ++currentPos }
            '}' -> { currentToken = JsonTokenTypes.RBRACE; tokenEnd = ++currentPos }
            '[' -> { currentToken = JsonTokenTypes.LBRACKET; tokenEnd = ++currentPos }
            ']' -> { currentToken = JsonTokenTypes.RBRACKET; tokenEnd = ++currentPos }
            ',' -> { currentToken = JsonTokenTypes.COMMA; tokenEnd = ++currentPos }
            ':' -> { currentToken = JsonTokenTypes.COLON; tokenEnd = ++currentPos }
            ' ', '\t', '\n', '\r' -> { skipWhitespace() }
            '"' -> { scanString() }
            else -> {
                if (c == '-' || c.isDigit()) scanNumber()
                else if (c.isLetter() || c == '_') scanKeyword()
                else { currentToken = JsonTokenTypes.BAD_CHARACTER; tokenEnd = ++currentPos }
            }
        }
    }

    private fun skipWhitespace() {
        while (currentPos < endOffset && buffer[currentPos].isWhitespace()) currentPos++
        tokenEnd = currentPos
        currentToken = JsonTokenTypes.WHITESPACE
    }

    private fun scanString() {
        currentPos++ // 跳过开头引号
        while (currentPos < endOffset) {
            val c = buffer[currentPos]
            if (c == '\\' && currentPos + 1 < endOffset) currentPos += 2 // 跳过转义
            else if (c == '"') { currentPos++; break }
            else currentPos++
        }
        tokenEnd = currentPos
        currentToken = JsonTokenTypes.STRING
    }

    private fun scanNumber() {
        if (buffer[currentPos] == '-') currentPos++
        while (currentPos < endOffset && buffer[currentPos].isDigit()) currentPos++
        if (currentPos < endOffset && buffer[currentPos] == '.') {
            currentPos++
            while (currentPos < endOffset && buffer[currentPos].isDigit()) currentPos++
        }
        tokenEnd = currentPos
        currentToken = JsonTokenTypes.NUMBER
    }

    private fun scanKeyword() {
        val start = currentPos
        while (currentPos < endOffset && (buffer[currentPos].isLetterOrDigit() || buffer[currentPos] == '_')) currentPos++
        val word = buffer.substring(start, currentPos)
        tokenEnd = currentPos
        currentToken = when (word) {
            "true", "false" -> JsonTokenTypes.BOOLEAN
            "null" -> JsonTokenTypes.NULL
            else -> JsonTokenTypes.BAD_CHARACTER
        }
    }

    override fun getState(): Int = 0

    override fun getCurrentPosition(): LexerPosition = object : LexerPosition {
        override fun getOffset(): Int = currentPos
        override fun getState(): Int = 0
    }

    override fun restore(position: LexerPosition) {
        currentPos = position.offset
        advance()
    }

    override fun getTokenType(): IElementType? = currentToken

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferEnd(): Int = endOffset

    override fun getBufferSequence(): CharSequence = buffer
}
