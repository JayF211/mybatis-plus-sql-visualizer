package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

/**
 * JSON 词法单元类型。
 */
public final class JsonTokenTypes {

    public static final JsonTokenTypes INSTANCE = new JsonTokenTypes();

    public static final IElementType STRING = new IElementType("JSON_STRING", null);

    public static final IElementType NUMBER = new IElementType("JSON_NUMBER", null);

    public static final IElementType BOOLEAN = new IElementType("JSON_BOOLEAN", null);

    public static final IElementType NULL = new IElementType("JSON_NULL", null);

    public static final IElementType LBRACE = new IElementType("JSON_LBRACE", null);

    public static final IElementType RBRACE = new IElementType("JSON_RBRACE", null);

    public static final IElementType LBRACKET = new IElementType("JSON_LBRACKET", null);

    public static final IElementType RBRACKET = new IElementType("JSON_RBRACKET", null);

    public static final IElementType COMMA = new IElementType("JSON_COMMA", null);

    public static final IElementType COLON = new IElementType("JSON_COLON", null);

    public static final IElementType BAD_CHARACTER = TokenType.BAD_CHARACTER;

    public static final IElementType WHITESPACE = TokenType.WHITE_SPACE;

    private JsonTokenTypes() {
    }
}
