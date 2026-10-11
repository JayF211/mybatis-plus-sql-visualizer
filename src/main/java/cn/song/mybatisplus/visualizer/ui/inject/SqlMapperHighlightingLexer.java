// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperHighlightingLexer.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-XmlSynHi D1：手写单趟词法（lexer-only，决策 2-A/3-A）。
 *
 * <p>token 优先级：注释（-- / block）> 字符串（'…'，'' 加倍不截断）> 参数占位（#{…} / ${…} 整体
 * token，决策 3-A；{@code \#} 转义按普通文本）> 关键字 > 数字 > 标识符 > 单字符 operator/空白。
 * 字符串态内不识别 {@code #{}}（MyBatis 参数占位不在字符串内出现的主形态；字符串内一切按 STRING）。
 * 未闭合字符串/注释扫到尾（BAD 形态并入 STRING/COMMENT，不产 error token——注入着色不做校验器）。
 */
public class SqlMapperHighlightingLexer extends LexerBase {

    private CharSequence buffer = "";
    private int endOffset = 0;
    private int tokenStart = 0;
    private int tokenEnd = 0;
    private IElementType tokenType = null;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        advance();
    }

    @Override
    @Nullable
    public IElementType getTokenType() {
        return tokenType;
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
    public CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    @Override
    public int getState() {
        return 0; // 无跨 token 状态（单趟无状态机）
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= endOffset) {
            tokenType = null;
            tokenEnd = endOffset;
            return;
        }
        char c = buffer.charAt(tokenStart);
        if (c == '-' && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '-') {
            // -- 行注释：扫到换行为止
            int i = tokenStart + 2;
            while (i < endOffset && buffer.charAt(i) != '\n') i++;
            tokenEnd = i;
            tokenType = SqlMapperTT.COMMENT;
        } else if (c == '/' && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '*') {
            // /* 块注释：扫到 */ 为止
            int i = tokenStart + 2;
            while (i + 1 < endOffset && !(buffer.charAt(i) == '*' && buffer.charAt(i + 1) == '/')) i++;
            tokenEnd = Math.min(endOffset, i + 2);
            tokenType = SqlMapperTT.COMMENT;
        } else if (c == '\'' || c == '"') {
            // 字符串：反斜杠转义跳下一字符；'' 加倍视作内容继续；未闭合扫到尾
            int i = tokenStart + 1;
            while (i < endOffset) {
                if (buffer.charAt(i) == '\\' && c == '\'') {
                    i += 2; // 反斜杠转义跳过下一字符
                } else if (buffer.charAt(i) == c) {
                    // '' 加倍：闭引号后紧跟同引号 → 视作内容继续
                    if (i + 1 < endOffset && buffer.charAt(i + 1) == c) {
                        i += 2;
                    } else {
                        break;
                    }
                } else {
                    i++;
                }
            }
            tokenEnd = Math.min(endOffset, i + 1);
            tokenType = SqlMapperTT.STRING;
        } else if ((c == '#' || c == '$') && tokenStart + 1 < endOffset && buffer.charAt(tokenStart + 1) == '{'
                && (tokenStart == 0 || buffer.charAt(tokenStart - 1) != '\\')) {
            // R21-注：\#{ 转义形态不产 PARAM（转义=非真参数，照 MyBatis TokenParser 转义语义）
            int close = indexOf(buffer, '}', tokenStart + 2);
            tokenEnd = close >= 0 ? close + 1 : endOffset;
            tokenType = SqlMapperTT.PARAM;
        } else if (isDigit(c)) {
            int i = tokenStart;
            while (i < endOffset && (isDigit(buffer.charAt(i)) || buffer.charAt(i) == '.')) i++;
            tokenEnd = i;
            tokenType = SqlMapperTT.NUMBER;
        } else if (isLetter(c) || c == '_') {
            int i = tokenStart;
            while (i < endOffset && (isLetterOrDigit(buffer.charAt(i)) || buffer.charAt(i) == '_')) i++;
            tokenEnd = i;
            tokenType = isKeywordAt(tokenStart, i) ? SqlMapperTT.KEYWORD : SqlMapperTT.IDENTIFIER;
        } else if (Character.isWhitespace(c)) {
            int i = tokenStart;
            while (i < endOffset && Character.isWhitespace(buffer.charAt(i))) i++;
            tokenEnd = i;
            tokenType = SqlMapperTT.WHITE_SPACE;
        } else {
            tokenEnd = tokenStart + 1;
            tokenType = SqlMapperTT.OPERATOR;
        }
    }

    /**
     * 无分配关键字判定（T-PerfSweep16 F46）：原 {@code subSequence().toString().uppercase()} 对
     * 每个词做两次串分配（子串副本 + 大写副本）；改为对词集逐词 {@code regionMatches} 忽略大小写
     * 直读缓冲，零分配。词集大小写不敏感口径不变（{@link SqlMapperLanguage#KEYWORDS} 存大写形态）。
     */
    private boolean isKeywordAt(int start, int end) {
        int len = end - start;
        for (String keyword : SqlMapperLanguage.KEYWORDS) {
            if (keyword.length() == len && buffer instanceof String
                    ? ((String) buffer).regionMatches(true, start, keyword, 0, len)
                    : keyword.length() == len && regionMatchesIgnoreCase(start, keyword, len)) {
                return true;
            }
        }
        return false;
    }

    /** CharSequence 通用忽略大小写前缀比较（buffer 非 String 兜底路径）。 */
    private boolean regionMatchesIgnoreCase(int start, String keyword, int len) {
        for (int i = 0; i < len; i++) {
            if (Character.toLowerCase(buffer.charAt(start + i)) != Character.toLowerCase(keyword.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return Character.isLetter(c);
    }

    private static boolean isLetterOrDigit(char c) {
        return isDigit(c) || Character.isLetter(c);
    }

    private static int indexOf(CharSequence cs, char ch, int from) {
        for (int i = Math.max(0, from); i < cs.length(); i++) {
            if (cs.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }
}
