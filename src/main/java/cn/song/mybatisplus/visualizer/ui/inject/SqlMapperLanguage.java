// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperLanguage.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lang.Language;

import java.util.Arrays;
import java.util.Set;

/**
 * T-XmlSynHi D1：mapper XML 语句文本注入用的自研 SQL-like 语言（lexer-only，决策 2-A）。
 *
 * <p>场景 = mapper XML 的语句标签（select/insert/update/delete）文本：关键字着色 +
 * {@code #{}}/{@code ${}} 参数占位整体 token 底色（决策 3-A：表达式整段一个 token，不细分 OGNL）。
 * 与插件自家预览面板的 {@code SqlLexer}（预览面板专用）分立——注入语言要求平台 Language 身份。
 *
 * <p>移植注记：Kotlin object 单例 → Java {@code public static final} {@link #INSTANCE}
 * （Kotlin 以 {@code SqlMapperLanguage} 直呼实例处，Java 侧写 {@code SqlMapperLanguage.INSTANCE}）。
 */
public final class SqlMapperLanguage extends Language {

    /** Kotlin object 单例等价（{@code object SqlMapperLanguage : Language("MpSqlMapper")}）。 */
    public static final SqlMapperLanguage INSTANCE = new SqlMapperLanguage();

    private SqlMapperLanguage() {
        super("MpSqlMapper");
    }

    /**
     * 命中即按关键字着色的词集（大小写不敏感；方言超集口径——Dialect 关键字集为渲染链专用，注入链用平集）。
     */
    public static final Set<String> KEYWORDS = Set.copyOf(Arrays.asList(
            "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
            "IN", "AS", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY",
            "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "VALUES", "INTO", "SET", "DISTINCT",
            "CASE", "WHEN", "THEN", "ELSE", "END", "LIKE", "BETWEEN", "EXISTS", "IS", "ASC", "DESC"
    ));
}
