package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.StatementVisitorAdapter
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter
import net.sf.jsqlparser.statement.update.Update

/**
 * 错列标注的**纯逻辑规划器**：把 [SchemaValidator] 的发现映射到 SQL 文本里的真实列 token。
 *
 * ## 为什么偏移必须取自 AST
 *
 * 需求红线是「标注落在真实列 token 上」且「不用字符串 indexOf 猜」。本对象用 JSqlParser 5.3
 * 的 AST token 位置（`SimpleNode.jjtGetFirstToken()/jjtGetLastToken()` 的
 * `beginLine/beginColumn/endLine/endColumn`）换算文档偏移：位置由**解析器**给出，不依赖列名
 * 在文本里首次出现的位置——SQL 里同名列出现在注释、字符串字面量、别名或别的表里时都不会
 * 标错位。
 *
 * ## 与校验器的关系（宁缺勿错）
 *
 * 发现清单由 [SchemaValidator] 产出（单表、无 JOIN/CTE/子查询、只认裸列），本对象只做
 * 「发现 → token」的定位，不扩大校验范围：
 *  - 只收集 UPDATE 的 SET 列/值/WHERE、DELETE 的 WHERE、单表 SELECT 的投影/WHERE——与
 *    `SchemaValidator.refsOf` 的遍历面逐处对齐；其它子句（ORDER BY 等）即使含同名列也不会
 *    被选中（校验器没有对它下结论，规划器也不替它下结论）；
 *  - 限定列（`t.a`）不参与匹配（校验器不校验限定列）；
 *  - 每个 issue 只标**首个**匹配 token（需求「同一 issue 只标一次」）；
 *  - token 文本归一化后必须与列名相等，否则放弃该条（解析器位置与文本不一致时不猜）。
 *
 * 纯逻辑：只依赖 JSqlParser + [SchemaValidator.Issue]，无 IDE 类型，可在普通 JUnit 直测。
 */
object PreviewUnknownColumnPlanner {

    /** 一条可渲染的标注（偏移为文档绝对偏移，`endOffset` 开区间）。 */
    data class Mark(
        val startOffset: Int,
        val endOffset: Int,
        val column: String,
        val table: String,
        val suggestion: String?,
    )

    /**
     * 把发现清单映射为标注清单。
     *
     * @param sql   与校验器**同一份**文档文本
     * @param issues 校验器发现（只消费 [SchemaValidator.UnknownColumn]）
     * @return 按发现顺序的标注；解析失败/位置不可证一律返回空清单（fail-open，不误报）
     */
    fun plan(sql: String, issues: List<SchemaValidator.Issue>): List<Mark> {
        val unknown = issues.filterIsInstance<SchemaValidator.UnknownColumn>()
        if (unknown.isEmpty() || sql.isBlank()) return emptyList()
        val occurrences = runCatching { collectOccurrences(sql) }.getOrDefault(emptyList())
        if (occurrences.isEmpty()) return emptyList()
        val starts = lineStarts(sql)
        val emitted = HashSet<String>()
        val marks = ArrayList<Mark>(unknown.size)
        for (issue in unknown) {
            // 同一 issue（表 + 列）只标一次：校验器本身已去重，这里再挡一道，防调用方传入重复清单。
            if (!emitted.add(issue.table + "\u0000" + issue.column)) continue
            val occurrence = occurrences.firstOrNull {
                it.table == null && it.name.equals(issue.column, ignoreCase = true)
            } ?: continue
            val range = offsetRange(sql, starts, occurrence) ?: continue
            marks += Mark(range.first, range.last + 1, issue.column, issue.table, issue.suggestion)
        }
        return marks
    }

    // ------------------------------------------------------------------
    // AST 遍历（与 SchemaValidator.refsOf 的可判定语句形态逐处对齐）
    // ------------------------------------------------------------------

    private class Occurrence(
        val name: String,
        val table: String?,
        val beginLine: Int,
        val beginColumn: Int,
        val endLine: Int,
        val endColumn: Int,
    )

    private fun collectOccurrences(sql: String): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (statement in SqlAstParser.parseStatements(sql)) collectStatement(statement, out)
        return out
    }

    private fun collectStatement(statement: Statement, out: MutableList<Occurrence>) {
        statement.accept(object : StatementVisitorAdapter<Unit>() {
            override fun <S> visit(update: Update, context: S) {
                update.updateSets?.forEach { set ->
                    set.columns?.forEach { column -> addColumn(column, out) }
                    set.values?.forEach { value -> walk(value as? Expression, out) }
                }
                walk(update.where, out)
            }

            override fun <S> visit(delete: Delete, context: S) {
                walk(delete.where, out)
            }

            override fun <S> visit(select: Select, context: S) {
                select.accept(object : SelectVisitorAdapter<Unit>() {
                    override fun <S2> visit(plain: PlainSelect, context2: S2) {
                        plain.selectItems?.forEach { item -> walk(item.expression, out) }
                        walk(plain.where, out)
                    }
                }, null)
            }
        }, null)
    }

    private fun walk(expression: Expression?, out: MutableList<Occurrence>) {
        if (expression == null) return
        expression.accept(object : ExpressionVisitorAdapter<Unit>() {
            override fun <S> visit(column: Column, context: S) {
                addColumn(column, out)
            }
        }, null)
    }

    private fun addColumn(column: Column?, out: MutableList<Occurrence>) {
        if (column == null) return
        val name = normalize(column.columnName) ?: return
        val table = column.table?.name?.let { normalize(it) }
        val node = column.astNode ?: return
        val first = node.jjtGetFirstToken() ?: return
        val last = node.jjtGetLastToken() ?: return
        out += Occurrence(
            name = name,
            table = table,
            beginLine = first.beginLine,
            beginColumn = first.beginColumn,
            endLine = last.endLine,
            endColumn = last.endColumn,
        )
    }

    // ------------------------------------------------------------------
    // 位置换算
    // ------------------------------------------------------------------

    /** 每行起始偏移（0-based 行号）；`\n`、`\r\n`、单独 `\r` 都算一次换行（与 JSqlParser 同口径）。 */
    private fun lineStarts(sql: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        var i = 0
        while (i < sql.length) {
            when (sql[i]) {
                '\r' -> {
                    if (i + 1 < sql.length && sql[i + 1] == '\n') i++
                    starts += i + 1
                }

                '\n' -> starts += i + 1
            }
            i++
        }
        return starts.toIntArray()
    }

    /**
     * AST 的 1-based 行列 → 文档绝对偏移（`endColumn` 是**闭区间**，故开区间上界为 +1）。
     *
     * 越界或 token 文本与列名不一致 → null（不猜）。
     */
    private fun offsetRange(sql: String, lineStarts: IntArray, occurrence: Occurrence): IntRange? {
        val beginLineStart = lineStarts.getOrNull(occurrence.beginLine - 1) ?: return null
        val endLineStart = lineStarts.getOrNull(occurrence.endLine - 1) ?: return null
        val start = beginLineStart + occurrence.beginColumn - 1
        val endExclusive = endLineStart + occurrence.endColumn
        if (start < 0 || start >= endExclusive || endExclusive > sql.length) return null
        val token = sql.substring(start, endExclusive)
        if (normalize(token) != occurrence.name) return null
        return start until endExclusive
    }

    /** 与 [SchemaValidator] 的归一化同口径：去空白/成对引用符、取最后一段、小写。 */
    private fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val stripped = raw.trim().trim('`', '"', '[', ']', ' ')
        if (stripped.isEmpty()) return null
        return stripped.substringAfterLast('.').trim('`', '"', '[', ']', ' ').lowercase().ifEmpty { null }
    }
}
