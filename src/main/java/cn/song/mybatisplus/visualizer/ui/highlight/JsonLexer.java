package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.lexer.Lexer;
import com.intellij.lexer.LexerPosition;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

/**
 * 轻量 JSON 词法扫描器：逐字符识别 JSON token。
 * 仅支持扁平结构（参数导入场景足够），不校验语法正确性。
 */
public class JsonLexer extends Lexer {

    private CharSequence buffer = "";
    private int startOffset = 0;
    private int endOffset = 0;
    private int currentPos = 0;
    private IElementType currentToken = null;
    private int tokenStart = 0;
    private int tokenEnd = 0;

    @Override
    public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.currentPos = startOffset;
        advance();
    }

    @Override
    public void advance() {
        tokenStart = currentPos;
        if (currentPos >= endOffset) {
            currentToken = null;
            tokenEnd = currentPos;
            return;
        }
        char c = buffer.charAt(currentPos);
        switch (c) {
            case '{':
                currentToken = JsonTokenTypes.LBRACE;
                tokenEnd = ++currentPos;
                break;
            case '}':
                currentToken = JsonTokenTypes.RBRACE;
                tokenEnd = ++currentPos;
                break;
            case '[':
                currentToken = JsonTokenTypes.LBRACKET;
                tokenEnd = ++currentPos;
                break;
            case ']':
                currentToken = JsonTokenTypes.RBRACKET;
                tokenEnd = ++currentPos;
                break;
            case ',':
                currentToken = JsonTokenTypes.COMMA;
                tokenEnd = ++currentPos;
                break;
            case ':':
                currentToken = JsonTokenTypes.COLON;
                tokenEnd = ++currentPos;
                break;
            case ' ':
            case '\t':
            case '\n':
            case '\r':
                skipWhitespace();
                break;
            case '"':
                scanString();
                break;
            default:
                if (c == '-' || Character.isDigit(c)) {
                    scanNumber();
                } else if (Character.isLetter(c) || c == '_') {
                    scanKeyword();
                } else {
                    currentToken = JsonTokenTypes.BAD_CHARACTER;
                    tokenEnd = ++currentPos;
                }
                break;
        }
    }

    private void skipWhitespace() {
        while (currentPos < endOffset && Character.isWhitespace(buffer.charAt(currentPos))) {
            currentPos++;
        }
        tokenEnd = currentPos;
        currentToken = JsonTokenTypes.WHITESPACE;
    }

    private void scanString() {
        currentPos++; // 跳过开头引号
        while (currentPos < endOffset) {
            char c = buffer.charAt(currentPos);
            if (c == '\\' && currentPos + 1 < endOffset) {
                currentPos += 2; // 跳过转义
            } else if (c == '"') {
                currentPos++;
                break;
            } else {
                currentPos++;
            }
        }
        tokenEnd = currentPos;
        currentToken = JsonTokenTypes.STRING;
    }

    private void scanNumber() {
        if (buffer.charAt(currentPos) == '-') {
            currentPos++;
        }
        while (currentPos < endOffset && Character.isDigit(buffer.charAt(currentPos))) {
            currentPos++;
        }
        if (currentPos < endOffset && buffer.charAt(currentPos) == '.') {
            currentPos++;
            while (currentPos < endOffset && Character.isDigit(buffer.charAt(currentPos))) {
                currentPos++;
            }
        }
        tokenEnd = currentPos;
        currentToken = JsonTokenTypes.NUMBER;
    }

    private void scanKeyword() {
        int start = currentPos;
        while (currentPos < endOffset && (Character.isLetterOrDigit(buffer.charAt(currentPos)) || buffer.charAt(currentPos) == '_')) {
            currentPos++;
        }
        String word = buffer.subSequence(start, currentPos).toString();
        tokenEnd = currentPos;
        switch (word) {
            case "true":
            case "false":
                currentToken = JsonTokenTypes.BOOLEAN;
                break;
            case "null":
                currentToken = JsonTokenTypes.NULL;
                break;
            default:
                currentToken = JsonTokenTypes.BAD_CHARACTER;
                break;
        }
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public LexerPosition getCurrentPosition() {
        return new LexerPosition() {
            @Override
            public int getOffset() {
                return currentPos;
            }

            @Override
            public int getState() {
                return 0;
            }
        };
    }

    @Override
    public void restore(LexerPosition position) {
        currentPos = position.getOffset();
        advance();
    }

    @Override
    public IElementType getTokenType() {
        return currentToken;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    @Override
    public CharSequence getBufferSequence() {
        return buffer;
    }
}
