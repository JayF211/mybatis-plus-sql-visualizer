package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.IdentityInsertStatements
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.BooleanValue
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.Parenthesis
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.expression.operators.relational.LikeExpression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.update.Update

/**
 * 无界写探测器（T-UnboundedWriteGuard / O-9）：识别 UPDATE/DELETE 中「可能影响全表或大量行」
 * 的静态特征，供执行确认流升级文案与回滚视图弱谓词警示复用。
 *
 * 规则集对齐 DBX `sql_risk.rs`（源码级验证过的无界写判定），经 JSqlParser AST 表达——
 * parser 权威原则与 RollbackSqlGenerator 一致：
 *  - [FindingKind.MISSING_WHERE] UPDATE/DELETE 无 WHERE 子句；
 *  - [FindingKind.TAUTOLOGY] 恒真谓词（`1=1` / `a=a` / `'x'='x'` / `TRUE`）；
 *  - [FindingKind.OR_TAUTOLOGY] OR 任一侧恒真（整行命中）；
 *  - [FindingKind.LIKE_WILDCARD] `LIKE` 纯通配（`'%'` / `'%%'`——等价无过滤）；
 *  - [FindingKind.NO_COLUMN_REF] WHERE 仅常量表达式（无任何列引用）；
 *  - [FindingKind.SUBQUERY] WHERE 含子查询（提示型，不算危险，供文案分级）；
 *  - [FindingKind.PARSE_FAILED] 解析失败 fail closed（按最高风险处理，走既有确认流不阻断）。
 *
 * 消费者限定两处（KDoc 契约）：execute/LogToSqlPanel 确认流、sql/rollback/RollbackSqlGenerator
 * 弱谓词警示；MCP 侧（McpSelectGuard）维持 SELECT 白名单不动，不接入本探测器。
 *
 * T-PS5-02：本对象同时承载**确认流/回滚链共享解析槽**（[parseStatementsShared]）——两条链对
 * 同一 SQL 文本的解析由「各一次」收敛为「共一次」，AST 只读共享（纪律见字段 KDoc）。
 *
 * 零 PSI / 零 IDE 依赖（仅 JSqlParser + SqlCommentStripper），普通 JUnit 可直接覆盖。
 */
object UnboundedWriteDetector {

    /** 发现类别（危险升序大致排列；SUBQUERY 仅提示）。 */
    enum class FindingKind { MISSING_WHERE, TAUTOLOGY, OR_TAUTOLOGY, LIKE_WILDCARD, NO_COLUMN_REF, SUBQUERY, PARSE_FAILED }

    /**
     * 单条发现。
     *
     * @param kind   类别（i18n 键派生自类别，UI 层渲染）
     * @param detail 命中片段原文（截断，供日志/测试定位；不进用户文案）
     */
    data class Finding(val kind: FindingKind, val detail: String = "")

    /** 探测结果：全部发现（按语句序聚合，多语句取并集=最高风险面）。 */
    data class WriteRisk(val findings: List<Finding>) {
        /** 是否存在危险发现（SUBQUERY 仅提示不算；PARSE_FAILED fail closed 算危险）。 */
        val dangerous: Boolean get() = findings.any { it.kind != FindingKind.SUBQUERY }

        /** 去重后的类别集合（确认文案每类一行）。 */
        fun kinds(): Set<FindingKind> = findings.map { it.kind }.toSet()
    }

    /** detail 片段长度上限（日志/测试定位用，非用户文案）。 */
    private const val DETAIL_LIMIT = 60

    /**
     * T-PS5-02：AST 单槽共享缓存——确认流（本探测器 [detect]）与回滚链
     * （[cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator.generate]）对**同一 SQL 文本**
     * 各解析一次（一次大 DML 点击最坏 4 次解析、3 次在 EDT）；单槽命中即复用同一次解析产物，
     * 最坏路径的 3 次降为 1 次，且**零调用点改动**（两条链都以「文本」为输入，槽位天然对上）。
     *
     * 命中判据与 T-PS5-01 同款三重快判 + 内容全等收口（引用相等 → 长度 → 哈希 → 全等）；
     * 单槽持有上次解析文本引用，上界=一条 SQL 文本。
     *
     * **AST 只读纪律（硬约束）**：两处消费者（[detect] 的 [analyzeWhere]、`RollbackSqlGenerator`
     * 的渲染链）经 grep 核实均为纯遍历——无 `set…`/`with…` 写节点调用，故共享同一 AST 实例不会
     * 互相污染；后续任何一方引入节点改写必须先脱离本槽（改为独立解析）。
     */
    private class AstSlot(val cleaned: String, val statements: List<net.sf.jsqlparser.statement.Statement>)

    @Volatile
    private var astSlot: AstSlot? = null

    /**
     * T-PS5-02 测试缝：AST 解析器（默认 JSqlParser）。生产链路勿改；测试注入替身以断言
     * 「确认流 → 回滚链只解析一次」。
     */
    internal var parseStatementsForTest: (String) -> List<net.sf.jsqlparser.statement.Statement> =
        { sql -> SqlAstParser.parseStatements(sql) }

    /** T-PS5-02 测试缝：清空单槽（用例隔离；生产链路无调用点）。 */
    internal fun clearAstSlotForTest() {
        astSlot = null
    }

    /**
     * T-PS5-02：共享解析入口——命中单槽直接复用，未命中解析后入槽。**解析失败原样抛出**
     * （fail 语义由调用方决定：[detect] 返回 PARSE_FAILED，RollbackSqlGenerator 返回 UNABLE）。
     */
    internal fun parseStatementsShared(cleaned: String): List<net.sf.jsqlparser.statement.Statement> {
        astSlot?.let { slot ->
            if (slot.cleaned === cleaned) return slot.statements
            if (slot.cleaned.length == cleaned.length && slot.cleaned.hashCode() == cleaned.hashCode() &&
                slot.cleaned == cleaned
            ) {
                return slot.statements
            }
        }
        val statements = parseStatementsForTest(cleaned)
        astSlot = AstSlot(cleaned, statements)
        return statements
    }

    /** LIKE 纯通配：至少一个 `%`，且只含 `%`/`_`。 */
    private val LIKE_WILDCARD_REGEX = Regex("[%_]*%[%_]*")

    /**
     * 探测 SQL 文本（可含注释与多条语句）。UPDATE/DELETE 逐条判定；INSERT 不适用（忽略）；
     * 解析失败整体 fail closed 返回 [FindingKind.PARSE_FAILED]。
     *
     * T-PS5-02：解析走 [parseStatementsShared] 单槽——同文本紧随其后的回滚链
     * （RollbackSqlGenerator.generate）复用本产物，不二次解析。
     */
    fun detect(sql: String): WriteRisk {
        val cleaned = SqlCommentStripper.strip(sql)
        if (cleaned.isBlank()) return WriteRisk(emptyList())
        // O-64/T-RollbackIdentityParse（P19）：先剥离插件自产 identity 包装语句（仅精确
        // `SET IDENTITY_INSERT <表> ON|OFF;` 形态，判据见 IdentityInsertStatements KDoc）再解析——
        // JSqlParser 对该 T-SQL 语句解析期抛 ParseException，不剥离则「开关 + 真写语句」的回滚产物
        // 整体 fail closed 报「无法解析语句结构」（误报：一行 INSERT 的产物被说成可能影响全表）。
        // 剥离只影响解析输入，findings 仍按剥离后的真实语句计算——真写语句（UPDATE/DELETE）照常报，
        // 开关语句本就不产生发现。无 identity 痕迹时 stripForParse 返回原引用，单槽 `===` 快路径不动。
        val parseable = IdentityInsertStatements.stripForParse(cleaned)
        val statements = try {
            parseStatementsShared(parseable)
        } catch (e: Exception) {
            return WriteRisk(listOf(Finding(FindingKind.PARSE_FAILED, e.message?.take(DETAIL_LIMIT) ?: "")))
        }
        val findings = ArrayList<Finding>()
        for (statement in statements) {
            when (statement) {
                is Update -> findings += analyzeWhere(statement.where)
                is Delete -> findings += analyzeWhere(statement.where)
                // INSERT 不适用（无 WHERE 语义）；其他类型非本探测器职责
            }
        }
        return WriteRisk(findings)
    }

    /**
     * WHERE 子句分析（RollbackSqlGenerator 复用——T-UW-03 弱谓词警示）。
     * 无 WHERE → [FindingKind.MISSING_WHERE]；恒真与纯通配在 AND/OR 叶子层逐原子判定。
     */
    internal fun analyzeWhere(where: Expression?): List<Finding> {
        if (where == null) return listOf(Finding(FindingKind.MISSING_WHERE))
        val findings = ArrayList<Finding>()
        val atoms = ArrayList<Expression>()
        collectAndAtoms(where, atoms)
        var hasColumnRef = false
        for (atom in atoms) {
            val unwrapped = unwrapGroup(atom)
            if (unwrapped is net.sf.jsqlparser.expression.operators.conditional.OrExpression) {
                val sides = ArrayList<Expression>()
                collectOrSides(unwrapped, sides)
                if (sides.any { isTautology(unwrapGroup(it)) }) {
                    findings += Finding(FindingKind.OR_TAUTOLOGY, shorten(unwrapped))
                }
                sides.forEach { side ->
                    val s = unwrapGroup(side)
                    if (isLikeWildcard(s)) findings += Finding(FindingKind.LIKE_WILDCARD, shorten(s))
                    if (containsColumn(s) || containsSelect(s)) hasColumnRef = true
                }
            } else {
                if (isTautology(unwrapped)) findings += Finding(FindingKind.TAUTOLOGY, shorten(unwrapped))
                if (isLikeWildcard(unwrapped)) findings += Finding(FindingKind.LIKE_WILDCARD, shorten(unwrapped))
            }
            if (containsColumn(unwrapped) || containsSelect(unwrapped)) hasColumnRef = true
        }
        if (!hasColumnRef && findings.none { it.kind == FindingKind.TAUTOLOGY }) {
            findings += Finding(FindingKind.NO_COLUMN_REF, shorten(where))
        }
        if (containsSelect(where)) {
            findings += Finding(FindingKind.SUBQUERY, shorten(where))
        }
        return findings
    }

    /**
     * 展开顶层 AND 两侧为原子；括号包住的 AND 组递归摊平（`WHERE (a=1 AND 1=1)` 的
     * 内嵌恒真不因括号漏报）；`(b=1 OR 1=1) AND c=2` 的括号 OR 保留 OrExpression
     * 形态，由调用方按 OR 处理。
     */
    private fun collectAndAtoms(expr: Expression, out: ArrayList<Expression>) {
        when {
            expr is net.sf.jsqlparser.expression.operators.conditional.AndExpression -> {
                collectAndAtoms(expr.leftExpression, out)
                collectAndAtoms(expr.rightExpression, out)
            }
            else -> {
                val inner = unwrapGroup(expr)
                if (inner !== expr && inner is net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
                    collectAndAtoms(inner, out)
                } else {
                    out += expr
                }
            }
        }
    }

    /** 展开 OR 两侧（OR 链 `a OR b OR c` 无论结合方向均摊平）；AND/OR 混合时 AND 侧整体作为一个 side。 */
    private fun collectOrSides(expr: Expression, out: ArrayList<Expression>) {
        when {
            expr is net.sf.jsqlparser.expression.operators.conditional.OrExpression -> {
                collectOrSides(expr.leftExpression, out)
                collectOrSides(expr.rightExpression, out)
            }
            else -> out += expr
        }
    }

    /**
     * 解包括号组：JSqlParser 5.3 两种形态——`Parenthesis` 与 `ParenthesedExpressionList`
     * （布尔括号组解析为单元素列表，实证 `(b = 2 OR 1 = 1)` 即后者）；多层括号递归解到底。
     */
    private fun unwrapGroup(expr: Expression): Expression {
        var current = expr
        while (true) {
            current = when {
                current is Parenthesis -> current.expression
                current is net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<*> &&
                    current.size == 1 -> current[0] as Expression

                else -> return current
            }
        }
    }

    /** 恒真判定：`TRUE` 字面量 / 等值两侧可渲染为同一常量或同名列。 */
    private fun isTautology(expr: Expression): Boolean {
        if (expr is BooleanValue) return expr.value
        if (expr is EqualsTo) {
            val left = renderComparable(expr.leftExpression)
            val right = renderComparable(expr.rightExpression)
            return left != null && left == right
        }
        return false
    }

    /** 可比较渲染：列名小写归一 / 字面量原样；不可比形态返回 null。 */
    private fun renderComparable(expr: Expression): String? = when (expr) {
        is Column -> "col:" + expr.columnName.lowercase()
        is LongValue, is DoubleValue, is StringValue, is BooleanValue -> "lit:" + expr.toString()
        else -> null
    }

    /** LIKE 纯通配：右值为只含 `%`/`_`（至少一个 `%`）的字符串；NOT LIKE 匹配空集不算危险。 */
    private fun isLikeWildcard(expr: Expression): Boolean {
        if (expr !is LikeExpression || expr.isNot) return false
        val right = expr.rightExpression as? StringValue ?: return false
        return LIKE_WILDCARD_REGEX.matches(right.value)
    }

    /** 子树含任何列引用（子查询按含列处理——其内部引用外部列）。 */
    private fun containsColumn(expr: Expression): Boolean = when (expr) {
        is Column -> true
        is Select -> true
        is BinaryExpression -> containsColumn(expr.leftExpression) || containsColumn(expr.rightExpression)
        is Parenthesis -> containsColumn(expr.expression)
        is SignedExpression -> containsColumn(expr.expression)
        is InExpression -> containsColumn(expr.leftExpression) || containsColumn(expr.rightExpression)
        is IsNullExpression -> containsColumn(expr.leftExpression)
        is Function -> expr.parameters?.any { containsColumn(it) } == true
        else -> false
    }

    /** 子树含子查询（5.x 子查询在表达式位置即 ParenthesedSelect extends Select）。 */
    private fun containsSelect(expr: Expression): Boolean = when (expr) {
        is Select -> true
        is BinaryExpression -> containsSelect(expr.leftExpression) || containsSelect(expr.rightExpression)
        is Parenthesis -> containsSelect(expr.expression)
        is SignedExpression -> containsSelect(expr.expression)
        is InExpression -> containsSelect(expr.leftExpression) || containsSelect(expr.rightExpression)
        is IsNullExpression -> containsSelect(expr.leftExpression)
        is Function -> expr.parameters?.any { containsSelect(it) } == true
        else -> false
    }

    private fun shorten(expr: Expression): String {
        val text = expr.toString().replace('\n', ' ')
        return if (text.length > DETAIL_LIMIT) text.take(DETAIL_LIMIT) + "…" else text
    }
}
