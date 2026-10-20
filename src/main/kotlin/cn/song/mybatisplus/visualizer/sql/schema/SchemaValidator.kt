package cn.song.mybatisplus.visualizer.sql.schema

import cn.song.mybatisplus.visualizer.execute.SqlAstParser
import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.Parenthesis
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.update.Update

/**
 * R46 执行前 Schema 校验的**纯逻辑校验器**（V65：introspection 三通道/metaCache/未绑定降级全在位）。
 *
 * 「渲染正确 ≠ 能执行」——列名错字、字段改型后旧 SQL 未跟，是动态 SQL 最高频的运行时错误。本
 * 对象把渲染产物 SQL 的表/列引用与一份 [SchemaSnapshot]（绑定数据源的元数据快照）比对，给出
 * 未识别对象 + 最接近名建议（did-you-mean）。
 *
 * ## 诚实降级口径（V65 实测结论，勿过度承诺）
 * 表**存在性**校验需要「表全集枚举」——插件侧无零成本 API（数据源全量遍历代价随 schema 规模线性，
 * 且「匹配不到」的负命中缓存同义于「未连接/内省未完成」，无法区分「表不存在」与「未预热」）。
 * 故 v1 只校验**已预热表的列**：
 *  - 表名在快照里取不到列集 → **跳过该语句**（不误报「表不存在」，也不报列）；
 *  - 多表/JOIN/子查询/解析失败 → 跳过（列归属无法判定，宁可不报）；
 *  - 只报**裸列名**（未加表限定）；限定列（`t.a`）的别名归属需别名表映射，v1 不猜。
 *
 * 纯逻辑层：只依赖 JSqlParser 5.3 + [SchemaSnapshot]（无 IDE 类型），普通 JUnit 可直接覆盖。
 */
object SchemaValidator {

    /** 校验发现（v1 只有未识别列一类；表级发现按上「诚实降级」暂不产出）。 */
    sealed interface Issue {
        val table: String
        val detail: String
    }

    /** 未识别列：渲染 SQL 引用的列在快照里不存在。 */
    data class UnknownColumn(override val table: String, val column: String, val suggestion: String?) : Issue {
        override val detail: String = if (suggestion != null) "$column (did you mean $suggestion?)" else column
    }

    /**
     * 校验 SQL 的对象引用。
     *
     * @param snapshot 绑定数据源的元数据快照（未绑定数据源时调用方**不应调用本方法**，见
     *   [cn.song.mybatisplus.visualizer.execute.SchemaValidationGate]）
     * @return 去重后的发现清单；无可判定对象或解析失败返回空清单（fail-open：不误报）
     */
    fun validate(sql: String, snapshot: SchemaSnapshot): List<Issue> {
        if (sql.isBlank() || snapshot.tables.isEmpty()) return emptyList()
        val statements: List<Statement> = try {
            SqlAstParser.parseStatements(sql)
        } catch (e: Exception) {
            return emptyList()
        }
        val issues = LinkedHashSet<Issue>()
        for (statement in statements) {
            val refs = refsOf(statement) ?: continue
            val columns = snapshot.columnsOf(refs.table) ?: continue
            for (column in refs.columns) {
                if (column !in columns) {
                    issues += UnknownColumn(refs.table, column, closestName(column, columns))
                }
            }
        }
        return issues.toList()
    }

    /** 单语句的可判定引用：单一表 + 其裸列引用（不可判定返回 null）。 */
    private data class Refs(val table: String, val columns: List<String>)

    /**
     * 提取 SQL 涉及的表名（最后一段，保留原大小写；供 IDE 侧按名预热元数据缓存）。
     *
     * 只覆盖 v1 校验支持的语句形态（UPDATE/DELETE/单表 SELECT）；解析失败返回空清单。
     */
    fun tableNames(sql: String): List<String> {
        if (sql.isBlank()) return emptyList()
        val statements = try {
            SqlAstParser.parseStatements(sql)
        } catch (e: Exception) {
            return emptyList()
        }
        return statements.mapNotNull { refsOf(it)?.table }.distinct()
    }

    private fun refsOf(statement: Statement): Refs? = when (statement) {
        is Delete -> {
            if (statement.tables != null && statement.tables.size > 1) return null
            if (!statement.joins.isNullOrEmpty()) return null
            val table = tableNameOf(statement.table) ?: return null
            val collector = Collector()
            collector.walk(statement.where)
            if (collector.ambiguous) return null
            Refs(table, collector.columns.toList())
        }

        is Update -> {
            if (!statement.joins.isNullOrEmpty()) return null
            val table = tableNameOf(statement.table) ?: return null
            val collector = Collector()
            statement.updateSets?.forEach { set ->
                set.columns?.forEach { col -> collector.addColumn(col) }
                set.values?.forEach { value -> collector.walk(value as? Expression) }
            }
            collector.walk(statement.where)
            if (collector.ambiguous) return null
            Refs(table, collector.columns.toList())
        }

        is PlainSelect -> {
            if (!statement.joins.isNullOrEmpty()) return null
            if (statement.fromItem !is Table) return null
            val table = tableNameOf(statement.fromItem as Table) ?: return null
            val collector = Collector()
            statement.selectItems?.forEach { item -> collector.walk(item?.expression as? Expression) }
            collector.walk(statement.where)
            if (collector.ambiguous) return null
            Refs(table, collector.columns.toList())
        }

        // 其它语句类型（INSERT/MERGE/DDL/SetOperationList 等）v1 不校验
        else -> null
    }

    /**
     * 收集裸列引用；命中子查询即置 [ambiguous]（列归属不可判定 → 调用方跳过整条语句）。
     */
    private class Collector {
        val columns = LinkedHashSet<String>()
        var ambiguous = false

        fun addColumn(column: Column?) {
            val name = normalizeIdentifier(column?.columnName) ?: return
            // 限定列（t.a）需别名表映射才能判定归属——v1 不猜
            if (!column?.table?.name.isNullOrBlank()) return
            if (name.isNotEmpty()) columns += name
        }

        fun walk(expr: Expression?) {
            when (expr) {
                null -> Unit
                is Column -> addColumn(expr)
                is Select -> ambiguous = true
                is ParenthesedExpressionList<*> -> expr.forEach { walk(it as? Expression) }
                is ExpressionList<*> -> expr.forEach { walk(it as? Expression) }
                is Parenthesis -> walk(expr.expression)
                is SignedExpression -> walk(expr.expression)
                is BinaryExpression -> {
                    walk(expr.leftExpression)
                    walk(expr.rightExpression)
                }

                is InExpression -> {
                    walk(expr.leftExpression)
                    walk(expr.rightExpression)
                }

                is IsNullExpression -> walk(expr.leftExpression)
                is Function -> expr.parameters?.forEach { walk(it) }
                else -> Unit
            }
        }
    }

    /** 表名取最后一段（`db.t` / `` `db`.`t` `` → `t`）并小写归一。 */
    private fun tableNameOf(table: Table?): String? = normalizeIdentifier(table?.name)

    private fun normalizeIdentifier(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val stripped = raw.trim().trim('`', '"', '[', ']', ' ')
        if (stripped.isEmpty()) return null
        return stripped.substringAfterLast('.').trim('`', '"', '[', ']', ' ').lowercase().ifEmpty { null }
    }

    /**
     * 最接近名建议（did-you-mean）：编辑距离 ≤ 2（且不超过名字长度的一半）取距离最小者；
     * 无候选返回 null（宁可不建议，不给错建议）。
     */
    fun closestName(target: String, candidates: Collection<String>): String? {
        val maxDistance = minOf(2, target.length / 2)
        if (maxDistance <= 0) return null
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in candidates) {
            val d = editDistance(target, candidate)
            if (d in 1..maxDistance && d < bestDistance) {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /** 标准 Levenshtein 距离（滚动数组，O(min(n,m)) 空间）。 */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}

/**
 * 元数据快照（键与列名统一小写归一，比对大小写不敏感）。由 IDE 侧
 * [cn.song.mybatisplus.visualizer.execute.SchemaValidationGate] 从数据源元数据缓存构造。
 */
data class SchemaSnapshot(val tables: Map<String, Set<String>>) {

    /** 取某表的列集；未在快照内返回 null（= 不可判定，调用方跳过）。 */
    fun columnsOf(table: String): Set<String>? = tables[table.lowercase()]

    companion object {
        /** 归一化构造：表名/列名统一小写；空名条目丢弃。 */
        fun of(tables: Map<String, Collection<String>>): SchemaSnapshot = SchemaSnapshot(
            tables.entries
                .filter { it.key.isNotBlank() }
                .associate { entry ->
                    entry.key.trim().lowercase() to entry.value.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                },
        )
    }
}
