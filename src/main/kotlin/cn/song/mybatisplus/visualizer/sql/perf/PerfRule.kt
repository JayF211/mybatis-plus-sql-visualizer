package cn.song.mybatisplus.visualizer.sql.perf

import cn.song.mybatisplus.visualizer.sql.Dialect
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

/**
 * **方言感知**性能规则（T-DialectQuoteWarn）。
 *
 * 少数规则的正确性依赖当前数据库方言（如「标识符引号是否被本方言接受」）。为把方言接进引擎而
 * **不改** [PerfRule.check] 签名（既有 20 条规则与两批测试零改动），新增本子接口：
 * [PerfRuleEngine.apply] 发现规则实现本接口时改调 [checkWithDialect]，否则仍调 [check]。
 *
 * 契约与 [PerfRule] 一致（纯函数、耗时 < 5ms、内部异常必须自吞返回空列表），额外约束：
 *  - `dialect == null` 或方言无引号上下文时，**不得**硬编码任何默认方言——应返回空列表（宁可不报）；
 *  - 本接口的实现类仍须实现 [check]（作为无方言上下文的降级，通常直接返回空列表）。
 */
interface DialectAwarePerfRule : PerfRule {

    /**
     * 带方言上下文的检测。
     *
     * @param sql     原始 SQL 文本（未修改）
     * @param ast     JSqlParser 解析后的语句树；解析失败时为 null
     * @param dialect 当前方言；无上下文时为 null（此时应返回空列表，不做判定）
     * @return 警告列表；无问题返回空列表，不返回 null
     */
    fun checkWithDialect(sql: String, ast: Statement?, dialect: Dialect?): List<PerfWarning>
}
