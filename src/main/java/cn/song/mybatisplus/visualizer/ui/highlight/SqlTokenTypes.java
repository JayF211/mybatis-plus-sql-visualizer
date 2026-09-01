package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.lang.Language;
import com.intellij.psi.tree.IElementType;

/**
 * SQL 预览 token 类型（M8-01）。
 *
 * 仅供 SqlLexer / SqlFormatter / HTML 渲染使用，不作为真正的语言解析器。
 * language 参数使用 {@link Language#ANY}（这些 token 不参与 PSI 树构建）。
 */
public final class SqlTokenTypes {

    public static final SqlTokenTypes INSTANCE = new SqlTokenTypes();

    public static final IElementType KEYWORD = new IElementType("MP_SQL_KEYWORD", Language.ANY);

    public static final IElementType IDENTIFIER = new IElementType("MP_SQL_IDENTIFIER", Language.ANY);

    /** 表名 / 表别名（FROM/JOIN/INTO/UPDATE 后由词法器启发式标记）。 */
    public static final IElementType TABLE = new IElementType("MP_SQL_TABLE", Language.ANY);

    public static final IElementType STRING = new IElementType("MP_SQL_STRING", Language.ANY);

    public static final IElementType NUMBER = new IElementType("MP_SQL_NUMBER", Language.ANY);

    public static final IElementType COMMENT = new IElementType("MP_SQL_COMMENT", Language.ANY);

    public static final IElementType OPERATOR = new IElementType("MP_SQL_OPERATOR", Language.ANY);

    public static final IElementType PAREN = new IElementType("MP_SQL_PAREN", Language.ANY);

    private SqlTokenTypes() {
    }
}
