package cn.song.mybatisplus.visualizer.execute

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.parser.feature.Feature
import net.sf.jsqlparser.parser.feature.FeatureConfiguration
import net.sf.jsqlparser.statement.Statement

/**
 * SQL 文本 → AST 的**单一解析入口**（两条链共用：无界写探测/回滚推导与元数据失效）。
 *
 * **为什么需要它**（2026-09-23 真机走查发现的缺陷）：此前两处各自写 `CCJSqlParserUtil.parseStatements(sql)`
 * ——**不带任何 feature**，于是 SQL Server 的方括号引用 `[user]` 被 JSqlParser 拒：
 * `ParseException: Encountered unexpected token: "[" "[" at line 2, column 6.`。
 * 而方括号引用**正是本插件自己为 SQL Server 产出的形态**（`Dialect.quoteIfNeeded` 走 `[...]`），
 * 于是「回滚 SQL」对 SQL Server 产物整体不可用（真机读数：回滚弹窗直接报 `无法解析 DELETE: …ParseException`）。
 *
 * 只开 `allowSquareBracketQuotation` 一项，不放宽其它解析行为（最小面）。
 */
internal object SqlAstParser {

    private val features: FeatureConfiguration =
        FeatureConfiguration().setValue(Feature.allowSquareBracketQuotation, true)

    /** 解析（可含多条语句与注释）；失败原样抛出，由调用方决定 fail 语义。 */
    fun parseStatements(sql: String): List<Statement> =
        CCJSqlParserUtil.parseStatements(sql) { parser -> parser.withConfiguration(features) }
}
