package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * 轻量 SQL 词法器（M8-01）。
 *
 * 仅为本插件产出的 SQL 做语法高亮分词，不追求通用 SQL 方言：
 *  - 关键字 / 标识符 / 表名 / 字符串 / 数字 / 注释 / 运算符 / 括号
 *  - 表名启发式：`FROM` / `JOIN` / `INTO` / `UPDATE` 后的标识符（及紧随的表别名）
 *    标记为 [SqlTokenTypes.TABLE]，对齐参考样式中"表名/别名"与"列名"分色。
 *
 * 与 [SqlSyntaxHighlighter] 配合，经 [com.intellij.openapi.editor.highlighter.LexerEditorHighlighter]
 * 挂到只读编辑器视图上。
 */
class SqlLexer : LexerBase() {

    private var myBuffer: CharSequence = ""
    private var myStart = 0
    private var myEnd = 0
    private var myTokenStart = 0
    private var myTokenEnd = 0
    private var myTokenType: IElementType? = null

    /** 表名启发式状态：0=普通；1=期望表名；2=表名后（期望别名或进入后续子句）。 */
    private var myTableState = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        myBuffer = buffer
        myStart = startOffset
        myEnd = endOffset
        myTokenStart = startOffset
        myTokenEnd = startOffset
        myTokenType = null
        myTableState = 0
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = myTokenType

    override fun getTokenStart(): Int = myTokenStart

    override fun getTokenEnd(): Int = myTokenEnd

    override fun getBufferSequence(): CharSequence = myBuffer

    override fun getBufferEnd(): Int = myEnd

    override fun advance() {
        myTokenStart = myTokenEnd
        if (myTokenEnd >= myEnd) {
            myTokenType = null
            return
        }
        val c = myBuffer[myTokenStart]
        when {
            c.isWhitespace() -> scanWhitespace()
            isLineCommentStart() -> scanLineComment()
            isBlockCommentStart() -> scanBlockComment()
            c == '\'' -> scanString()
            c.isDigit() -> scanNumber()
            isIdentifierStart(c) -> scanWord()
            c == '`' || c == '[' -> scanQuotedIdentifier()
            c == '(' || c == ')' -> {
                myTokenEnd++
                myTokenType = SqlTokenTypes.PAREN
            }
            else -> scanOperator()
        }
    }

    // ------------------------------------------------------------------
    // 各 token 扫描
    // ------------------------------------------------------------------

    /** 空白（不改变表名启发式状态，避免 `FROM tbl` 中间空白打断）。 */
    private fun scanWhitespace() {
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd].isWhitespace()) myTokenEnd++
        myTokenType = TokenType.WHITE_SPACE
    }

    /** `--` 行注释。 */
    private fun scanLineComment() {
        myTokenEnd += 2
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd] != '\n') myTokenEnd++
        myTokenType = SqlTokenTypes.COMMENT
    }

    /** 扫描块注释（斜杠星号包裹）。 */
    private fun scanBlockComment() {
        myTokenEnd += 2
        while (myTokenEnd + 1 < myEnd && !(myBuffer[myTokenEnd] == '*' && myBuffer[myTokenEnd + 1] == '/')) myTokenEnd++
        myTokenEnd = if (myTokenEnd + 1 < myEnd) myTokenEnd + 2 else myEnd
        myTokenType = SqlTokenTypes.COMMENT
    }

    /** `'...'` 字符串（`''` 转义）。 */
    private fun scanString() {
        myTokenEnd++
        while (myTokenEnd < myEnd) {
            if (myBuffer[myTokenEnd] == '\'') {
                if (myTokenEnd + 1 < myEnd && myBuffer[myTokenEnd + 1] == '\'') {
                    myTokenEnd += 2
                } else {
                    myTokenEnd++
                    break
                }
            } else {
                myTokenEnd++
            }
        }
        myTokenType = SqlTokenTypes.STRING
        myTableState = 0
    }

    /** 数字（含小数）。 */
    private fun scanNumber() {
        myTokenEnd++
        while (myTokenEnd < myEnd && (myBuffer[myTokenEnd].isDigit() || myBuffer[myTokenEnd] == '.')) myTokenEnd++
        myTokenType = SqlTokenTypes.NUMBER
        myTableState = 0
    }

    /** 标识符 / 关键字（按表名启发式状态分类）。 */
    private fun scanWord() {
        myTokenEnd++
        while (myTokenEnd < myEnd && isIdentifierPart(myBuffer[myTokenEnd])) myTokenEnd++
        val word = myBuffer.subSequence(myTokenStart, myTokenEnd).toString()
        myTokenType = classifyWord(word)
    }

    /** 反引号 `` `xxx` `` / 方括号 `[xxx]` 标识符。 */
    private fun scanQuotedIdentifier() {
        myTokenEnd++
        while (myTokenEnd < myEnd && myBuffer[myTokenEnd] != '`' && myBuffer[myTokenEnd] != ']') myTokenEnd++
        if (myTokenEnd < myEnd) myTokenEnd++
        myTokenType = SqlTokenTypes.IDENTIFIER
        myTableState = 0
    }

    /** 运算符 / 标点（= != <> < <= > >= + - * / % ? 等）。 */
    private fun scanOperator() {
        myTokenEnd++
        when {
            cAt(myTokenStart) == '!' && cAt(myTokenStart + 1) == '=' -> myTokenEnd++
            cAt(myTokenStart) == '<' && (cAt(myTokenStart + 1) == '=' || cAt(myTokenStart + 1) == '>') -> myTokenEnd++
            cAt(myTokenStart) == '>' && cAt(myTokenStart + 1) == '=' -> myTokenEnd++
        }
        myTokenType = SqlTokenTypes.OPERATOR
        myTableState = 0
    }

    // ------------------------------------------------------------------
    // 表名启发式
    // ------------------------------------------------------------------

    /**
     * 关键字集合（大小写不敏感）。仅覆盖本插件 SQL 会出现的子句关键字。
     */
    private fun classifyWord(word: String): IElementType {
        val upper = word.uppercase()
        if (upper in KEYWORDS) {
            when (upper) {
                "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "INTO", "UPDATE", "TABLE" -> myTableState = 1
                "AS" -> { /* AS 后可为表别名或列别名，保持当前状态 */ }
                else -> myTableState = 0
            }
            return SqlTokenTypes.KEYWORD
        }
        return when (myTableState) {
            1 -> {
                myTableState = 2
                SqlTokenTypes.TABLE
            }
            2 -> {
                myTableState = 0
                SqlTokenTypes.TABLE
            }
            else -> SqlTokenTypes.IDENTIFIER
        }
    }

    private fun isLineCommentStart(): Boolean =
        cAt(myTokenStart) == '-' && cAt(myTokenStart + 1) == '-'

    private fun isBlockCommentStart(): Boolean =
        cAt(myTokenStart) == '/' && cAt(myTokenStart + 1) == '*'

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** 越界返回 NUL。 */
    private fun cAt(index: Int): Char = if (index < myEnd) myBuffer[index] else '\u0000'

    companion object {
        /** SQL 关键字（大写，去重；补全器复用）。 */
        internal val KEYWORDS = setOf(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE", "EXISTS",
            "GROUP", "BY", "ORDER", "ASC", "DESC", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "ALL",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "AS", "USING", "UNION",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "TABLE", "CREATE", "DROP", "ALTER", "ADD",
            "COLUMN", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "INDEX",
            "CASE", "WHEN", "THEN", "ELSE", "END",
            "COUNT", "SUM", "MAX", "MIN", "AVG", "COALESCE", "NULLIF", "CAST", "CONVERT",
        )
    }
}
