package cn.song.mybatisplus.visualizer.sql.perf

import net.sf.jsqlparser.statement.Statement

/**
 * 性能规则接口（F-2 SQL 性能分析）。
 *
 * 每条规则实现一个性能反模式检测逻辑。规则分两类：
 *  - 正则规则：仅需 SQL 文本，[check] 的 `ast` 参数可忽略；
 *  - AST 规则：需要 JSqlParser 解析后的 [Statement]，解析失败时 `ast` 为 null，
 *    AST 规则应直接返回空列表（降级到正则规则）。
 *
 * 实现要求：
 *  - 纯函数，无副作用；
 *  - 执行耗时 < 5ms（典型 SQL）；
 *  - 内部异常必须捕获并返回空列表，不得上抛（规则隔离）。
 */
interface PerfRule {

    /** 规则 ID（如 `missing_where`、`leading_wildcard_like`）。 */
    val ruleId: String

    /**
     * 检测 SQL 性能问题。
     *
     * @param sql 原始 SQL 文本（未修改）
     * @param ast JSqlParser 解析后的语句树；解析失败时为 null
     * @return 警告列表；无问题返回空列表，不返回 null
     */
    fun check(sql: String, ast: Statement?): List<PerfWarning>
}
