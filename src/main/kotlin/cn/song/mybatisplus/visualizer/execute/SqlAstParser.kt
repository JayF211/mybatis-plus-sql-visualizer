package cn.song.mybatisplus.visualizer.execute

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.parser.feature.Feature
import net.sf.jsqlparser.parser.feature.FeatureConfiguration
import net.sf.jsqlparser.statement.Statement

/**
 * SQL 文本 → AST 的**单一解析入口**（T-PerfSweep7 T-PS7-03 收拢后共四处：无界写探测/回滚推导、
 * 元数据失效、DDL 目标提取、SQL 性能分析、批量等值谓词合并、MCP SELECT 闸门）。
 *
 * **为什么需要它**（2026-09-23 真机走查发现的缺陷）：此前各处各自写 `CCJSqlParserUtil.parse*(sql)`
 * ——**不带任何 feature**，于是 SQL Server 的方括号引用 `[user]` 被 JSqlParser 拒：
 * `ParseException: Encountered unexpected token: "[" "[" at line 2, column 6.`。
 * 而方括号引用**正是本插件自己为 SQL Server 产出的形态**（`Dialect.quoteIfNeeded` 走 `[...]`），
 * 于是「回滚 SQL」对 SQL Server 产物整体不可用（真机读数：回滚弹窗直接报 `无法解析 DELETE: …ParseException`）。
 *
 * 只开 `allowSquareBracketQuotation` 一项，不放宽其它解析行为（最小面）。
 * 失败语义：**原样抛出**，由调用方决定 fail 方向（闸门 fail-closed / 探测 fail-through / 分析降级）。
 */
internal object SqlAstParser {

    private val features: FeatureConfiguration =
        FeatureConfiguration().setValue(Feature.allowSquareBracketQuotation, true)

    /** 解析（可含多条语句与注释）；失败原样抛出，由调用方决定 fail 语义。 */
    fun parseStatements(sql: String): List<Statement> =
        CCJSqlParserUtil.parseStatements(sql) { parser -> parser.withConfiguration(features) }

    /** 解析单条语句（T-PS7-03 新增，共享既有 [features]）；失败原样抛出，由调用方决定 fail 语义。 */
    fun parseSingle(sql: String): Statement =
        CCJSqlParserUtil.parse(sql) { parser -> parser.withConfiguration(features) }
}
