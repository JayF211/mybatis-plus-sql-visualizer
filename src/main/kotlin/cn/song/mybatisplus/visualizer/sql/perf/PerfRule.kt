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
 * 规则执行上下文（T-DialectQuoteWarn §十二）。
 *
 * 把随调用链一起流动的「SQL 文本 + AST + 方言 + 连接选项」收成一个对象，避免
 * [DialectAwarePerfRule] 的参数随维度增加而膨胀（原 `checkWithDialect(sql, ast, dialect)`
 * 在 §十二 接入 ANSI_QUOTES 后需第四维）。
 *
 * @param sql        原始 SQL 文本（未修改）
 * @param ast        JSqlParser 解析后的语句树；解析失败时为 null
 * @param dialect    当前方言；无上下文时为 null（方言感知规则此时应返回空列表，不做判定）
 * @param ansiQuotes 目标连接是否**已声明** `sql_mode` 含 `ANSI_QUOTES`（T-DialectQuoteWarn §十二）：
 *                   `true` = 数据源 URL 明示开启（可证明合法）、`false` = 已知但未声明、
 *                   `null` = 未知（无绑定数据源 / 探测失败 / 调用点无预览面板）。
 *
 *                   **三态不可折叠为布尔**：URL 只能证明「合法」，不能证明「非法」——生效
 *                   `sql_mode` = 服务端全局 + 会话覆盖，URL 里的 `sessionVariables` 只是会话覆盖，
 *                   未声明时服务端全局仍可能开着。故 `false` 与 `null` 在判定上等价（都只能 WARN），
 *                   但保留三态以便缓存键与测试精确区分。
 */
data class PerfContext(
    val sql: String,
    val ast: Statement?,
    val dialect: Dialect?,
    val ansiQuotes: Boolean? = null,
)

/**
 * **方言感知**性能规则（T-DialectQuoteWarn）。
 *
 * 少数规则的正确性依赖当前数据库方言（如「标识符引号是否被本方言接受」）。为把方言接进引擎而
 * **不改** [PerfRule.check] 签名（既有 20 条规则与两批测试零改动），新增本子接口：
 * [PerfRuleEngine.apply] 发现规则实现本接口时改调 [check]，否则仍调 [PerfRule.check]。
 *
 * 契约与 [PerfRule] 一致（纯函数、耗时 < 5ms、内部异常必须自吞返回空列表），额外约束：
 *  - `ctx.dialect == null` 或方言无引号上下文时，**不得**硬编码任何默认方言——应返回空列表（宁可不报）；
 *  - 本接口的实现类仍须实现 [check]（作为无方言上下文的降级，通常直接返回空列表）。
 */
interface DialectAwarePerfRule : PerfRule {

    /**
     * 带上下文的检测（T-DialectQuoteWarn §十二：原 `checkWithDialect(sql, ast, dialect)` 升级为
     * [PerfContext]，以承载新增的 `ansiQuotes` 三态）。
     *
     * @param ctx 执行上下文（SQL / AST / 方言 / 连接 ANSI_QUOTES 三态）
     * @return 警告列表；无问题返回空列表，不返回 null
     */
    fun check(ctx: PerfContext): List<PerfWarning>
}
