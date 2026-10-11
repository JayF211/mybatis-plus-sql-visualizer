package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SetClause
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UnionClause
import cn.song.mybatisplus.visualizer.model.UpdateStatement

/**
 * 反向结果 → 正向模型 的测试桥接工具（T-SqlToJavaPromote M1）。
 *
 * 往返门禁用：把 [SqlReverseResult] 桥接为 [SqlStatement]（SELECT/UPDATE/DELETE），
 * 交给 [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 二次渲染，与输入 SQL 规范化比对。
 * INSERT 无正向模型（SqlGenerator 不支持）→ 走 [renderInsertSql] 测试渲染器。
 *
 * 桥接仅做字段搬运（反向模型与正向模型同源 Condition 家族，无需转换条件本身）；
 * 列原文（`t.id` / `` `t`.`id` ``）拆为 tableAlias + columnName。
 * （T-SyncK2JMaster482 C9b-1：生产类已转 Java record，具名实参改位置实参、访问器改 Java 形态。）
 */
fun SqlReverseResult.toSqlStatement(): SqlStatement = when (kind) {
    ReverseStatementKind.SELECT -> SelectStatement(
        TableInfo("", tableName, emptyList()),
        selectColumns?.map { columnRefOf(it.expr(), it.alias()) } ?: emptyList(),
        tableAlias,
        joins.map { it.toJoinClause() },
        distinct,
        conditions,
        groupBy,
        having,
        orderBy,
        limit,
        null,               // rawFirst
        null,               // rawAround
        null,               // comment
        unions.map { UnionClause(it.keyword(), it.sql()) },
        null,               // fromSubQuery
        null,               // fillClauses
    )
    ReverseStatementKind.UPDATE -> UpdateStatement(
        TableInfo("", tableName, emptyList()),
        sets.map { SetClause(ColumnRef(it.column()), it.value(), it.rawSql()) },
        conditions,
        null,               // rawTail
        tableAlias,
        joins.map { it.toJoinClause() },
    )
    ReverseStatementKind.DELETE -> DeleteStatement(
        TableInfo("", tableName, emptyList()),
        conditions,
        deleteTargets,
        tableAlias,
        joins.map { it.toJoinClause() },
        limit,
    )
    ReverseStatementKind.INSERT -> throw IllegalStateException("INSERT 无正向模型，走 renderInsertSql")
}

private fun ReverseJoin.toJoinClause(): JoinClause = JoinClause(
    type,
    tableName,
    alias ?: "",
    onConditions,
)

/** 列原文（含表别名前缀/反引号）→ [ColumnRef]（拆 tableAlias + columnName，保留 AS 别名）。 */
fun columnRefOf(expr: String, alias: String? = null): ColumnRef {
    val parts = expr.split('.').map { it.trim('`') }
    return if (parts.size > 1) {
        ColumnRef(parts.dropLast(1).joinToString("."), parts.last(), alias)
    } else {
        ColumnRef(null, parts.first(), alias)
    }
}

/** INSERT 测试渲染器（无正向模型，往返门禁用）：反向 insertColumns/insertValues → INSERT 语句。 */
fun renderInsertSql(result: SqlReverseResult): String = buildString {
    append("INSERT INTO ").append(result.tableName())
    append(" (").append(result.insertColumns().joinToString(", ")).append(")")
    append(" VALUES ")
    append(result.insertValues().joinToString(", ") { row ->
        "(" + row.joinToString(", ") { v ->
            when (v) {
                is SqlValue.Literal -> v.raw
                is SqlValue.Placeholder -> "?"
            }
        } + ")"
    })
}

/**
 * 词法安全规范化（往返比对用）：折叠空白、字母大写、剥反引号/双引号标识符包裹、剥离注释；
 * 字符串字面量内容原样保留（不折叠不换大小写，防 `'A B'` 与 `'A'` 误判相等）。
 * 语义等价形态（关键字大小写/空白/保留字引用方式/注释）归一到同一文本。
 */
fun normalizeForCompare(sql: String): String {
    val sb = StringBuilder(sql.length)
    var i = 0
    var prevSpace = false
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' -> {
                val end = findStringEnd(sql, i)
                sb.append(sql, i, end)
                i = end
                prevSpace = false
            }
            c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                // 行注释剥离
                while (i < sql.length && sql[i] != '\n') i++
            }
            c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                // 块注释剥离
                val end = sql.indexOf("*/", i + 2)
                i = if (end < 0) sql.length else end + 2
            }
            c == '`' || c == '"' -> {
                // 标识符引用剥壳（`` `user` `` / `"member"` → 内容大写归一，与渲染侧小写标识符对齐）；未闭合则原样保留
                val end = sql.indexOf(c, i + 1)
                if (end < 0) {
                    sb.append(c)
                    i++
                } else {
                    sql.substring(i + 1, end).forEach { ch ->
                        sb.append(if (ch in 'a'..'z') ch - 32 else ch)
                    }
                    i = end + 1
                }
                prevSpace = false
            }
            c.isWhitespace() -> {
                if (sb.isNotEmpty() && !prevSpace) sb.append(' ')
                prevSpace = true
                i++
            }
            c in 'a'..'z' -> {
                sb.append(c - 32)
                prevSpace = false
                i++
            }
            else -> {
                sb.append(c)
                prevSpace = false
                i++
            }
        }
    }
    return sb.toString().trim()
}

/** 字符串终点（SQL 双写转义 `''` 不结束）。 */
private fun findStringEnd(sql: String, start: Int): Int {
    var j = start + 1
    while (j < sql.length) {
        if (sql[j] == '\'') {
            if (j + 1 < sql.length && sql[j + 1] == '\'') {
                j += 2
                continue
            }
            return j + 1
        }
        j++
    }
    return sql.length
}
