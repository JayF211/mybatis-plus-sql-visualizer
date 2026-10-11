// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperTT.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.psi.tree.IElementType;

/**
 * T-XmlSynHi D1：注入语言的 token 类型（lexer-only，无 PSI 元素）。
 *
 * <p>移植注记：Kotlin object + {@code @JvmField val} → Java {@code public static final} 字段。
 */
public final class SqlMapperTT {

    private SqlMapperTT() {
    }

    public static final IElementType KEYWORD = new IElementType("MPSQL_KEYWORD", SqlMapperLanguage.INSTANCE);
    public static final IElementType STRING = new IElementType("MPSQL_STRING", SqlMapperLanguage.INSTANCE);
    public static final IElementType COMMENT = new IElementType("MPSQL_COMMENT", SqlMapperLanguage.INSTANCE);
    /** #{…} / ${…} 整体（决策 3-A）。 */
    public static final IElementType PARAM = new IElementType("MPSQL_PARAM", SqlMapperLanguage.INSTANCE);
    public static final IElementType NUMBER = new IElementType("MPSQL_NUMBER", SqlMapperLanguage.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("MPSQL_IDENTIFIER", SqlMapperLanguage.INSTANCE);
    public static final IElementType OPERATOR = new IElementType("MPSQL_OPERATOR", SqlMapperLanguage.INSTANCE);
    public static final IElementType WHITE_SPACE = new IElementType("MPSQL_WHITE_SPACE", SqlMapperLanguage.INSTANCE);
}
