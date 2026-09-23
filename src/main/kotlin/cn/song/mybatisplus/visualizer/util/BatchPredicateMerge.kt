package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.HexValue
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.delete.Delete

/**
 * 批量等值谓词 IN 合并预览（T-BatchInMerge / O-14，吸收 DBX `data_grid_sql.rs`
 * push_mysql_delete_batches / parse_mysql_equality_predicate 形态）：
 * foreach 批删日志还原后的几十条同构 `DELETE FROM t WHERE col = k` 生成一条
 * `DELETE FROM t WHERE col IN (k1, k2, …)` **参考形态**。
 *
 * 边界红线：**合并仅用于预览参考，执行文本永远是逐条原样**——等价性（NULL 语义/类型/
 * 方言函数差异）不由本插件背书；调用方文案必须双保险「仅预览参考，执行仍逐条」。
 *
 * 判定（全部满足才进合并集；任一不满足的单条语句留在集外，集外有语句时调用方按
 * 「N/M 条」诚实披露，绝不静默丢语句）：
 *  - DELETE 语句（UPDATE 不合并——SET 不同构；其他类型不适用）；
 *  - 同表（表名忽略大小写）；
 *  - WHERE 为单列等值谓词 `col = 字面量`（同一列；字面量 = 数值/字符串/十六进制/带符号数值，
 *    列对列、函数包裹、子查询、IS NULL、复合谓词任一命中即出局）；
 *  - 合并集 ≥ 2 条才产出（1 条无合并价值）。
 *
 * 双约束（DBX 对齐）：行数上限 [MAX_ROWS]（默认 500）+ 字节预算 [MAX_BYTES]（默认 8KB），
 * 超限截断计数（[MergePreview.hiddenValues]）交调用方以「…等 N 条」披露。
 * 字面量**原样文本拼接**（不转义不再格式化——含引号值照抄显示，等价性不由展示形态承担）。
 *
 * 解析失败 / 空 / 无合并集 → null（回退原形态，绝不部分合并）。
 * 纯函数零 UI 依赖，普通 JUnit 可直接覆盖。
 */
object BatchPredicateMerge {

    /** 行数上限（DBX 批删合批对齐）。 */
    const val MAX_ROWS = 500

    /** 合并产物字节预算（展示串超限截断计数）。 */
    const val MAX_BYTES = 8 * 1024

    /**
     * 合并预览产物。
     *
     * @param table         目标表名（原样大小写）
     * @param column        等值谓词列名（原样大小写）
     * @param values        字面量渲染清单（双约束截断后的可见部分，≥1 条）
     * @param hiddenValues  因行数上限/字节预算被截断的值个数（0 = 无截断）
     * @param coveredCount  进入合并集的语句数
     * @param totalStatements 输入语句总数（>coveredCount 时调用方按「N/M 条」披露异构语句在场）
     */
    data class MergePreview(
        val table: String,
        val column: String,
        val values: List<String>,
        val hiddenValues: Int,
        val coveredCount: Int,
        val totalStatements: Int,
    ) {
        /** 合并参考 SQL（不含截断后缀——后缀文案属 UI 层 i18n）。 */
        fun mergedSql(): String = "DELETE FROM $table WHERE $column IN (${values.joinToString(", ")})"
    }

    /** 单条语句的解析结果：等值谓词或出局。 */
    private data class ParsedDelete(val table: String, val column: String, val value: String)

    /**
     * 生成合并预览；无可合并集（<2 条同构等值删除）返回 null。
     * 输入建议为切分产物（util/SqlStatementSplitter.split）；注释与解析失败的单条出局。
     */
    fun mergePreview(statements: List<String>): MergePreview? {
        if (statements.size < 2) return null
        val parsed = ArrayList<ParsedDelete>(statements.size)
        for (statement in statements) {
            parseEqualityDelete(statement)?.let { parsed += it }
        }
        if (parsed.size < 2) return null
        // 同表同列分组取最大组（组内全同构；异构组语句留在集外由计数披露）
        val groups = parsed.groupBy { it.table.lowercase() to it.column.lowercase() }
        val best = groups.values.maxByOrNull { it.size } ?: return null
        if (best.size < 2) return null
        val first = best[0]
        val rendered = best.map { it.value }
        // 行数上限
        val visibleByRows = rendered.take(MAX_ROWS)
        var hidden = rendered.size - visibleByRows.size
        // 字节预算：前缀 + 已选值逗号占位后逐值试探
        val prefix = "DELETE FROM ${first.table} WHERE ${first.column} IN ("
        var budget = MAX_BYTES - prefix.length - 2 // 预留 ") " 与余量
        val visible = ArrayList<String>(visibleByRows.size)
        for ((index, value) in visibleByRows.withIndex()) {
            val cost = value.length + if (index > 0) 2 else 0
            if (visible.isNotEmpty() && budget - cost < 0) break
            budget -= cost
            visible += value
        }
        hidden += visibleByRows.size - visible.size
        return MergePreview(
            table = first.table,
            column = first.column,
            values = visible,
            hiddenValues = hidden,
            coveredCount = best.size,
            totalStatements = statements.size,
        )
    }

    /** 解析单条 `DELETE FROM t WHERE col = 字面量`；非该形态（含解析失败）返回 null。 */
    private fun parseEqualityDelete(statement: String): ParsedDelete? {
        val cleaned = cn.song.mybatisplus.visualizer.sql.SqlCommentStripper.strip(statement)
        if (cleaned.isBlank()) return null
        // T-PS7-03 收拢：走 SqlAstParser.parseSingle——方括号引用的 DELETE 不再判「不可合并」
        val parsed = try {
            SqlAstParser.parseSingle(cleaned)
        } catch (_: Exception) {
            return null
        }
        val delete = parsed as? Delete ?: return null
        val table = delete.table?.fullyQualifiedName ?: return null
        val where = delete.where as? EqualsTo ?: return null
        val column = where.leftExpression as? Column ?: return null
        if (!isPlainLiteral(where.rightExpression)) return null
        return ParsedDelete(table, column.columnName, renderLiteral(where.rightExpression))
    }

    /** 纯字面量判定：数值/字符串/十六进制/带符号数值；列/函数/子查询/NULL 一律出局。 */
    private fun isPlainLiteral(expr: Expression): Boolean = when (expr) {
        is LongValue, is DoubleValue, is HexValue, is StringValue -> true
        is SignedExpression -> expr.expression is LongValue || expr.expression is DoubleValue
        else -> false
    }

    /** 字面量渲染：原样 toString（StringValue 含引号形态），不再转义。 */
    private fun renderLiteral(expr: Expression): String = when (expr) {
        is SignedExpression -> "${expr.sign}${renderLiteral(expr.expression)}"
        else -> expr.toString()
    }
}
