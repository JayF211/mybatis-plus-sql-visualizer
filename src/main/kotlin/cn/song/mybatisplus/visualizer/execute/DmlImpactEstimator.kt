package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.Dialect
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.update.Update

/**
 * R42 DML 影响面预演（V60 已证伪入批）。
 *
 * UPDATE/DELETE 执行前不知道会碰多少行，是开发者最贵的恐惧之一。本对象把 WHERE 谓词改写为
 * 等价 `COUNT(*)` **只读试算**语句（复用执行链的真机通道，不做自带连接），并给出目标表与
 * 行数上限提示；由 IDE 侧动作/确认链决定何时提交（本对象只产出语句，不执行）。
 *
 * ## V60 语义边界（改写等价性判据，全部落在此处）
 *  - **单表** UPDATE/DELETE：`DELETE FROM t WHERE p` ≡ `SELECT COUNT(*) FROM t WHERE p`。
 *  - **JOIN / 多表 / USING**：[Plan.NotApplicable]——多表下一条目标行可能被 JOIN 匹配多次，
 *    COUNT 会**多计**，等价性不成立（宁可不报，不给错数）。
 *  - **子查询（单表）**：等价性成立（子查询是筛选条件的一部分，两侧同构），放行。
 *  - **无 WHERE**：[Plan.NotApplicable]——全表 `COUNT(*)` 即全表扫描，正是 V60 要求做成本防护的
 *    形态；影响面无界已在执行确认弹窗（[UnboundedWriteDetector]）如实警示，此处不再制造一次
 *    可能拖垮库的扫描。
 *  - **DELETE ... LIMIT n**：仅当方言支持该尾段（[Dialect.supportsDeleteLimit]）时，实际影响
 *    上界 = min(匹配行数, n)——此时试算也**收窄到 n 行**（`SELECT COUNT(*) FROM (SELECT 1 …
 *    LIMIT n) …`），天然获得成本上限；方言不支持该尾段时，渲染产物本就不含 LIMIT（见 R52
 *    能力缺口提示），故按完整匹配行数试算（不设上限，这才是真实影响面）。
 *  - **INSERT/MERGE/DDL/多语句**：不适用（本对象只覆盖 UPDATE/DELETE 影响面）。
 *
 * ## 只读红线联动（V60 口径）
 * 产出的试算语句是单条 SELECT，**必须通过 MCP 只读护栏** [cn.song.mybatisplus.visualizer.mcp.McpSelectGuard]
 * ——由测试钉住（`DmlImpactEstimatorTest`）；即 SQL 含 LIMIT/子查询等形态时也不会被护栏改判为写。
 *
 * 纯逻辑层：只依赖 JSqlParser 5.3 + [Dialect]（无 IDE 类型），普通 JUnit 可直接覆盖。
 */
object DmlImpactEstimator {

    /** 试算计划。 */
    sealed interface Plan {
        /** 可试算：产出只读 COUNT 语句。 */
        data class Applicable(val probe: Probe) : Plan

        /** 不适用（改写不等价/成本不可控），[reasonKey] 为 bundle 文案键。 */
        data class NotApplicable(val reasonKey: String) : Plan
    }

    /**
     * 试算语句与提示信息。
     *
     * @param probeSql     只读 COUNT 试算语句（单条 SELECT）
     * @param targetTable  影响表（原始写法，含库/模式限定）
     * @param rowCap       行数上限（DELETE LIMIT n 且方言支持时 = n；否则 null）
     * @param whereText    WHERE 谓词原文（截断；无 WHERE 不会走到这里）
     */
    data class Probe(val probeSql: String, val targetTable: String, val rowCap: Long?, val whereText: String)

    /** WHERE 原文在提示里的长度上限。 */
    private const val WHERE_LIMIT = 200

    /**
     * 生成影响面试算计划。
     *
     * @param sql     渲染产物 SQL（单条 UPDATE/DELETE）
     * @param dialect 当前生效方言（决定 DELETE LIMIT 是否收窄试算）
     */
    fun plan(sql: String, dialect: Dialect): Plan {
        val statements: List<Statement> = try {
            SqlAstParser.parseStatements(sql)
        } catch (e: Exception) {
            return Plan.NotApplicable("dml.impact.reason.unparseable")
        }
        if (statements.size != 1) return Plan.NotApplicable("dml.impact.reason.multiStatement")
        return when (val statement = statements[0]) {
            is Delete -> planFor(
                tableText = statement.table?.toString() ?: return Plan.NotApplicable("dml.impact.reason.unparseable"),
                where = statement.where,
                joins = !statement.joins.isNullOrEmpty(),
                multiTable = (statement.tables?.size ?: 0) > 1,
                limitRows = statement.limit?.rowCount?.let { (it as? LongValue)?.value },
                dialect = dialect,
            )

            is Update -> planFor(
                tableText = statement.table?.toString() ?: return Plan.NotApplicable("dml.impact.reason.unparseable"),
                where = statement.where,
                // JSqlParser 5.3 把 `UPDATE a JOIN b ON …` 的 JOIN 放在 startJoins（实测：joins=null、
                // startJoins=[JOIN b ON …]）——两处都要看，否则多表 UPDATE 会被误判为单表（COUNT 多计）。
                joins = !statement.joins.isNullOrEmpty() || !statement.startJoins.isNullOrEmpty(),
                // Update 只有单一 getTable()（5.3 javap 实证：无 getTables()）——多表 UPDATE 由 joins 侧覆盖
                multiTable = false,
                limitRows = null,
                dialect = dialect,
            )

            else -> Plan.NotApplicable("dml.impact.reason.notDml")
        }
    }

    private fun planFor(
        tableText: String,
        where: net.sf.jsqlparser.expression.Expression?,
        joins: Boolean,
        multiTable: Boolean,
        limitRows: Long?,
        dialect: Dialect,
    ): Plan {
        if (joins || multiTable) return Plan.NotApplicable("dml.impact.reason.multiTable")
        if (where == null) return Plan.NotApplicable("dml.impact.reason.noWhere")
        if (containsSubqueryFailSafe(where) && where.toString().length > SUBQUERY_WHERE_LIMIT) {
            // 超长子查询谓词：试算语句会非常长且成本不可控，诚实降级
            return Plan.NotApplicable("dml.impact.reason.complexWhere")
        }
        val whereText = where.toString().replace('\n', ' ').take(WHERE_LIMIT)
        val cap = limitRows?.takeIf { dialect.supportsDeleteLimit && it > 0 }
        val probeSql = if (cap != null) {
            "SELECT COUNT(*) FROM (SELECT 1 FROM $tableText WHERE $whereText LIMIT $cap) mpsv_impact_probe"
        } else {
            "SELECT COUNT(*) FROM $tableText WHERE $whereText"
        }
        return Plan.Applicable(Probe(probeSql, tableText, cap, whereText))
    }

    /** 谓词是否含子查询（仅用于超长降级判据；不影响等价性结论）。 */
    private fun containsSubqueryFailSafe(where: net.sf.jsqlparser.expression.Expression?): Boolean =
        where != null && where.toString().let { !it.isBlank() && SUBQUERY_HINT.containsMatchIn(it) }

    /** 子查询谓词原文长度上限（超过即判成本不可控）。 */
    private const val SUBQUERY_WHERE_LIMIT = 400

    private val SUBQUERY_HINT = Regex("\\bSELECT\\b", RegexOption.IGNORE_CASE)
}
