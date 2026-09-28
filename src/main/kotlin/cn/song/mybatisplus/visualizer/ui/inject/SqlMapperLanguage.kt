package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.Language

/**
 * T-XmlSynHi D1：mapper XML 语句文本注入用的自研 SQL-like 语言（lexer-only，决策 2-A）。
 *
 * 场景 = mapper XML 的语句标签（select/insert/update/delete）文本：关键字着色 +
 * `#{}`/`${}` 参数占位整体 token 底色（决策 3-A：表达式整段一个 token，不细分 OGNL）。
 * 与插件自家预览面板的 `SqlLexer`（预览面板专用）分立——注入语言要求平台 Language 身份。
 */
object SqlMapperLanguage : Language("MpSqlMapper") {

    /** 命中即按关键字着色的词集（大小写不敏感；方言超集口径——Dialect 关键字集为渲染链专用，注入链用平集）。 */
    val KEYWORDS: Set<String> = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
        "IN", "AS", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY",
        "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "VALUES", "INTO", "SET", "DISTINCT",
        "CASE", "WHEN", "THEN", "ELSE", "END", "LIKE", "BETWEEN", "EXISTS", "IS", "ASC", "DESC",
    )
}
