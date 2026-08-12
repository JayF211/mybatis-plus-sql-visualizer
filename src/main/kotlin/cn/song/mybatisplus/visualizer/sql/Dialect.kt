package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LimitClause

/**
 * 数据库方言 SPI（T-M5-03/F-14）。
 *
 * 方言差异点（M5 实现范围）：
 *  - 分页子句：[renderLimit]（MySQL 用 LIMIT，PostgreSQL 用 LIMIT+OFFSET，Oracle 用 FETCH）
 *  - 标识符引用：[quoteIdentifier]（MySQL 反引号，PG/Oracle 双引号；默认不引用，按需启用）
 *
 * 新增方言：实现本接口并注册进 [DialectRegistry]。
 */
interface Dialect {

    /** 方言名称（与 Settings 下拉项一致）。 */
    val name: String

    /**
     * 渲染完整分页子句（含关键字，如 `LIMIT 10` / `LIMIT 10 OFFSET 5`）。
     *
     * @param limit 分页模型
     * @return 追加到 SQL 末尾的分页子句
     */
    fun renderLimit(limit: LimitClause): String

    /**
     * 标识符引用（表名/列名包裹）。
     * 默认原样返回（不引用）；需要时子类覆写（如 MySQL 反引号）。
     */
    fun quoteIdentifier(identifier: String): String = identifier
}

/**
 * MySQL 方言：`LIMIT [offset,] count`。
 */
object MySqlDialect : Dialect {
    override val name: String = "MySQL"

    override fun renderLimit(limit: LimitClause): String {
        val offsetPart = if (limit.offset != null) "${limit.offset}, " else ""
        return "LIMIT $offsetPart${limit.count}"
    }

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"
}

/**
 * PostgreSQL 方言：`LIMIT count OFFSET offset`。
 */
object PostgreSQLDialect : Dialect {
    override val name: String = "PostgreSQL"

    override fun renderLimit(limit: LimitClause): String {
        val offsetPart = if (limit.offset != null) " OFFSET ${limit.offset}" else ""
        return "LIMIT ${limit.count}$offsetPart"
    }

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""
}

/**
 * Oracle 方言（12c+）：`OFFSET n ROWS FETCH NEXT m ROWS ONLY`（无 offset 时 `FETCH FIRST m ROWS ONLY`）。
 */
object OracleDialect : Dialect {
    override val name: String = "Oracle"

    override fun renderLimit(limit: LimitClause): String = if (limit.offset != null) {
        "OFFSET ${limit.offset} ROWS FETCH NEXT ${limit.count} ROWS ONLY"
    } else {
        "FETCH FIRST ${limit.count} ROWS ONLY"
    }

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""
}

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
object DialectRegistry {

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    val dialects: List<Dialect> = listOf(MySqlDialect, PostgreSQLDialect, OracleDialect)

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 [Dialect.name]）
     * @return 匹配方言；未知名称返回 [MySqlDialect]
     */
    fun byName(name: String?): Dialect =
        dialects.firstOrNull { it.name == name } ?: MySqlDialect
}
