package cn.song.mybatisplus.visualizer.sql.perf.rules

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OceanBaseDialect
import cn.song.mybatisplus.visualizer.sql.perf.DialectAwarePerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfContext
import cn.song.mybatisplus.visualizer.sql.perf.PerfRule
import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.util.TablesNamesFinder

/**
 * 第四批性能规则（方言感知，T-DialectQuoteWarn）。
 *
 * 与前三批的差别：本批规则的正确性依赖当前数据库方言，实现 [DialectAwarePerfRule]，
 * 由 [cn.song.mybatisplus.visualizer.sql.perf.PerfRuleEngine.apply] 在拿到方言时改调
 * [DialectAwarePerfRule.check]。
 *
 * 规则清单：
 *  - `dialect_quote_mismatch`：标识符用了当前方言不接受的引号字符（如 MySQL 方言下出现 `FROM "user" t`）。
 */
object PerfRulesBatch4 {

    /** 所有第四批规则实例。 */
    val all: List<PerfRule> = listOf(
        DialectQuoteMismatchRule,
    )

    /**
     * `dialect_quote_mismatch`：SQL 里出现「**已知标识符引号字符**（双引号 `"` / 反引号 `` ` `` /
     * 方括号 `[]`）包裹的标识符，而该字符不在当前方言的**可接受引号集合**
     * （[Dialect.identifierQuotes]）内」→ 告警。
     *
     * **只报告不改写**：本规则不触碰 SQL 文本。理由见计划 §一——插件渲染的是 MyBatis-Plus 真实
     * 发出的 SQL，把它改写成「能跑」的形态会让预览偏离真机、把用户代码里的真 bug 静默抹平。
     *
     * 判定路径：
     *  - AST 可用（首选）：用 [TablesNamesFinder] 遍历语句树，收集表名与列名——JSqlParser 5.3 对
     *    `"user"` / `` `user` `` / `[user]` 均**保留原始引号**（`Table.getName()` / `Column.getColumnName()`
     *    实测），且字符串字面量里的引号（`'say "hi"'`）不是标识符节点，**天然不误伤**；
     *  - AST 为 null（降级）：正则扫描 SQL 文本，**先剥离单引号字符串字面量与注释**，再找引号包裹的标识符。
     *
     * 严重度分两档（T-DialectQuoteWarn §十二）：
     *  - **无歧义档 → ERROR**：PG / Oracle / DM / KingBase / openGauss 遇反引号、非 SQL Server 遇方括号
     *    等——这些引号字符在对应方言下**一定**不是标识符引号，报语法错无例外；
     *  - **MySQL 家族（MySQL / MariaDB / OceanBase）+ 双引号 → 至多 WARN**：双引号是否是标识符取决于
     *    连接的 `sql_mode` 是否含 `ANSI_QUOTES`。**URL 只能证明「合法」，不能证明「非法」**——生效
     *    `sql_mode` = 服务端全局 + 会话覆盖，URL 里的 `sessionVariables` 只是会话覆盖，未声明时服务端
     *    全局仍可能开着。故：
     *    - [PerfContext.ansiQuotes] `== true`（URL 明示声明）→ **不报**（已确知合法）；
     *    - `== false`（已知但未声明）或 `== null`（未知）→ WARN，文案带前提（若实际未开启则报语法错）。
     *    缓存键含该三态，见 [cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer]。
     *
     * 无方言上下文（`dialect == null`）或方言未声明引号集合（基类兜底空集）→ 返回空列表（宁可不报）。
     * 命中时**至多一条**告警：优先报无歧义档（MySQL 家族下同时出现 `"x"` 与 `[y]` 时，`[y]` 的 ERROR
     * 不被双引号的 WARN 掩盖）。
     */
    object DialectQuoteMismatchRule : DialectAwarePerfRule {

        override val ruleId = "dialect_quote_mismatch"

        /** 无方言上下文的降级：不做判定（[DialectAwarePerfRule] 契约要求实现，语义即「不报」）。 */
        override fun check(sql: String, ast: Statement?): List<PerfWarning> = emptyList()

        override fun check(ctx: PerfContext): List<PerfWarning> {
            val dialect = ctx.dialect ?: return emptyList()
            val accepted = dialect.identifierQuotes
            // 空集 = 基类兜底「不判」（无方言上下文时宁可不报也不误报）
            if (accepted.isEmpty()) return emptyList()

            val identifiers = if (ctx.ast != null) collectIdentifiersFromAst(ctx.ast) else collectIdentifiersFromText(ctx.sql)

            val offending = identifiers.asSequence()
                .mapNotNull { quoteCharOf(it) }
                .filter { it !in accepted }
                .distinct()
                .toList()
            if (offending.isEmpty()) return emptyList()

            // 无歧义档优先：MySQL 家族之外的引号不匹配（或 MySQL 家族下的方括号等）一定报语法错 → ERROR
            val unambiguous = offending.firstOrNull { !isAmbiguousMysqlDoubleQuote(dialect, it) }
            if (unambiguous != null) {
                return listOf(
                    PerfWarning(
                        ruleId = ruleId,
                        severity = PerfSeverity.ERROR,
                        messageKey = "perf.dialect_quote_mismatch",
                        messageArgs = listOf(quoteDisplay(unambiguous), dialect.name, acceptedDisplay(accepted)),
                    ),
                )
            }

            // 只剩「MySQL 家族 + 双引号」这一档：URL 只能证明合法、不能证明非法（§十二），故至多 WARN。
            // 已确知连接声明了 ANSI_QUOTES → 不报。
            if (ctx.ansiQuotes == true) return emptyList()
            return listOf(
                PerfWarning(
                    ruleId = ruleId,
                    severity = PerfSeverity.WARN,
                    messageKey = "perf.dialect_quote_mismatch_ansi_quotes",
                    messageArgs = listOf(quoteDisplay(offending.first()), dialect.name, acceptedDisplay(accepted)),
                ),
            )
        }
    }

    /** MySQL 家族（双引号是否为标识符取决于连接 `sql_mode` 是否含 `ANSI_QUOTES`）。 */
    private val MYSQL_FAMILY: Set<Dialect> = setOf(MySqlDialect, MariaDbDialect, OceanBaseDialect)

    /**
     * 「MySQL 家族 + 双引号」＝判定**有歧义**的档：URL 只能证明合法、不能证明非法，故不得用 ERROR。
     * 其余组合（PG 族遇反引号、非 SQL Server 遇方括号等）引号字符一定不是标识符引号 → 无歧义。
     */
    private fun isAmbiguousMysqlDoubleQuote(dialect: Dialect, quote: Char): Boolean =
        quote == '"' && dialect in MYSQL_FAMILY

    // ------------------------------------------------------------------
    // 标识符收集
    // ------------------------------------------------------------------

    /**
     * AST 路径：遍历语句树收集**表名 + 列名**（含 JOIN / 子查询内的）。
     *
     * 复用 JSqlParser 官方 [TablesNamesFinder] 的整树遍历（它实现了 SelectVisitor / ExpressionVisitor /
     * SelectItemVisitor 等全部访问面），仅覆写 [TablesNamesFinder.visit] 把表名与列名的**原始文本**
     * （引号保留）记下来。`getTables(ast)` 只是驱动这次遍历的入口，其返回值（去重后的表集合）不直接使用。
     */
    private fun collectIdentifiersFromAst(ast: Statement): List<String> {
        val collector = IdentifierCollector()
        // 驱动整树遍历；收集是 visit 覆写的副作用（返回值此处不用）
        collector.getTables(ast)
        return collector.identifiers
    }

    private class IdentifierCollector : TablesNamesFinder<Any?>() {
        val identifiers = mutableListOf<String>()

        override fun <S> visit(table: Table, context: S): Any? {
            table.name?.let { identifiers += it }
            table.schemaName?.let { identifiers += it }
            return super.visit(table, context)
        }

        override fun <S> visit(column: Column, context: S): Any? {
            column.columnName?.let { identifiers += it }
            return super.visit(column, context)
        }
    }

    /**
     * 正则降级路径（ast == null 时）：剥离单引号字符串字面量与注释后，找出引号包裹的标识符。
     *
     * 单引号字符串字面量里的引号（`WHERE name = 'say "hi"'`）**不得**触发告警——这是降级路径
     * 与 AST 路径的对齐点（AST 路径靠节点类型天然排除）。
     */
    private fun collectIdentifiersFromText(sql: String): List<String> {
        val stripped = stripLiteralsAndComments(sql)
        return QUOTED_IDENTIFIER.findAll(stripped).map { it.value }.toList()
    }

    /** 逐字符剥离：单引号字符串（含 `''` 转义）、`--` 行注释、`/* */` 块注释。 */
    private fun stripLiteralsAndComments(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    // 单引号字符串：连写两个单引号是一个转义的单引号
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                i += 2
                                continue
                            }
                            i++
                            break
                        }
                        i++
                    }
                }
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    if (i + 1 < sql.length) i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 引号判定与展示
    // ------------------------------------------------------------------

    /**
     * 取标识符的开引号字符（仅当**首尾成对**为已知标识符引号时）：
     * `"x"` → `"`、`` `x` `` → `` ` ``、`[x]` → `[`；其余返回 null。
     *
     * 「首尾成对」口径见计划 §六：不做逐字符扫描（反引号可能出现在标识符内部，罕见）。
     */
    private fun quoteCharOf(identifier: String): Char? {
        if (identifier.length < 2) return null
        val first = identifier.first()
        val last = identifier.last()
        return when {
            first == '"' && last == '"' -> '"'
            first == '`' && last == '`' -> '`'
            first == '[' && last == ']' -> '['
            else -> null
        }
    }

    private fun quoteDisplay(ch: Char): String = ch.toString()

    /** 可接受引号集合的展示串（排序后以 `/` 连接，如 SQLite 的 `` "/[/` ``）。 */
    private fun acceptedDisplay(accepted: Set<Char>): String =
        accepted.map { quoteDisplay(it) }.sorted().joinToString("/")

    /** 引号包裹的标识符：双引号 / 反引号 / 方括号三种形态（已剥离字面量与注释的文本上匹配）。 */
    private val QUOTED_IDENTIFIER = Regex("\"[^\"]*\"|`[^`]*`|\\[[^\\]]*]")
}
