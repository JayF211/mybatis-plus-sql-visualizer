package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue

/**
 * 数据库方言 SPI（T-M5-03/F-14）。
 *
 * 方言差异点（M5 实现范围）：
 *  - 分页子句：[renderLimit]（MySQL 用 LIMIT，PostgreSQL 用 LIMIT+OFFSET，Oracle 用 FETCH）
 *  - 标识符引用：[quoteIdentifier]（MySQL 反引号，PG/Oracle 双引号；默认不引用，按需启用）
 *  - 关键字判定：[reservedWords]（T-M23.4 按方言保留字集合，[quoteIfKeyword] 只判本方言）
 *  - upsert 冲突子句：[renderUpsert]（T-M43 方言差异点第 4 项；null → 无原生 upsert，渲染降级纯 INSERT）
 *
 * 新增方言：实现本接口并注册进 [DialectRegistry]（五件套：分页形态、引用符号、保留字集合、upsert 形态、测试）。
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

    /**
     * 本方言保留字集合（T-M23.4，小写；[quoteIfKeyword] 判定用）——
     * 按方言判定：MySQL 只判 MySQL 保留字（如 `number` 是 Oracle 保留字但 MySQL 合法：MySQL 不包）。
     */
    val reservedWords: Set<String>

    /**
     * 标识符**按需引用**（T-M23.4）：命中**本方言**保留字（大小写不敏感）→ [quoteIdentifier]
     * 包裹（符号按方言：MySQL 反引号 / PG 双引号 / SQLServer 方括号）；否则原样返回。
     *
     * 语义：引用幂等安全（被引用的词在本方言是保留字，包裹后合法）——宁可多包、不漏包；
     * 仅渲染层调用，模型层列名保持原始值（参数对照/补全/勾选匹配不受影响）。
     */
    fun quoteIfKeyword(identifier: String): String =
        if (identifier.lowercase() in reservedWords) quoteIdentifier(identifier) else identifier

    /**
     * UPSERT 冲突子句（T-M43：设置里出现 = 承诺可用）。
     *
     * 返回 null → 本方言无原生 upsert 语法（Oracle/SQL Server/DM 的原生形态是复杂 MERGE），
     * 渲染方降级为纯 INSERT（保证 SQL 在本方言可执行）；非 null → 追加到 INSERT 语句末尾的冲突子句。
     *
     * @param conflictKey    冲突键列名（原始值，实现内按需引用；MySQL 形态无冲突键列表，仍须传入用于勾选判定）
     * @param updateColumns  冲突后更新列名（原始值，不含冲突键；实现内按需引用）
     */
    fun renderUpsert(conflictKey: String, updateColumns: List<String>): String? = null
}

/**
 * 共享渲染辅助：MySQL 形态 `LIMIT [offset,] count`（无 offset 时 `LIMIT count`）。
 * 供 MySQL / MariaDB / DM 复用。
 */
private fun renderMySqlStyleLimit(limit: LimitClause): String {
    val offsetPart = if (limit.offset != null) "${renderLimitValue(limit.offset)}, " else ""
    return "LIMIT $offsetPart${renderLimitValue(limit.count)}"
}

/**
 * 共享渲染辅助：PostgreSQL 形态 `LIMIT {count} OFFSET {offset}`（无 offset 时仅 `LIMIT {count}`）。
 * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
 */
private fun renderPgStyleLimit(limit: LimitClause): String {
    val offsetPart = if (limit.offset != null) " OFFSET ${renderLimitValue(limit.offset)}" else ""
    return "LIMIT ${renderLimitValue(limit.count)}$offsetPart"
}

/** T-M29-03：分页子句数值渲染——字面量原样、运行时值 `?`（service.page 的 pageNum/pageSize 变量）。 */
private fun renderLimitValue(value: SqlValue): String = when (value) {
    is SqlValue.Literal -> value.raw
    is SqlValue.Placeholder -> "?"
}

/**
 * 共享 upsert 辅助：MySQL 形态 `ON DUPLICATE KEY UPDATE col = VALUES(col), ...`。
 * 供 MySQL / MariaDB 复用（VALUES() 对 5.7~8.0+/MariaDB 全版本可用）。
 */
private fun Dialect.renderMySqlStyleUpsert(updateColumns: List<String>): String =
    "ON DUPLICATE KEY UPDATE " + updateColumns.joinToString(", ") { "${quoteIfKeyword(it)} = VALUES(${quoteIfKeyword(it)})" }

/**
 * 共享 upsert 辅助：PostgreSQL 形态 `ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col, ...`。
 * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
 */
private fun Dialect.renderPgStyleUpsert(conflictKey: String, updateColumns: List<String>): String =
    "ON CONFLICT (${quoteIfKeyword(conflictKey)}) DO UPDATE SET " +
        updateColumns.joinToString(", ") { "${quoteIfKeyword(it)} = EXCLUDED.${quoteIfKeyword(it)}" }

/**
 * MySQL 方言：`LIMIT [offset,] count`。
 */
object MySqlDialect : Dialect {
    override val name: String = "MySQL"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val reservedWords: Set<String> = MYSQL_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderMySqlStyleUpsert(updateColumns)
}

/**
 * PostgreSQL 方言：`LIMIT count OFFSET offset`。
 */
object PostgreSQLDialect : Dialect {
    override val name: String = "PostgreSQL"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = PG_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderPgStyleUpsert(conflictKey, updateColumns)
}

/**
 * Oracle 方言（12c+）：`OFFSET n ROWS FETCH NEXT m ROWS ONLY`（无 offset 时 `FETCH FIRST m ROWS ONLY`）。
 */
object OracleDialect : Dialect {
    override val name: String = "Oracle"

    override fun renderLimit(limit: LimitClause): String = if (limit.offset != null) {
        "OFFSET ${renderLimitValue(limit.offset)} ROWS FETCH NEXT ${renderLimitValue(limit.count)} ROWS ONLY"
    } else {
        "FETCH FIRST ${renderLimitValue(limit.count)} ROWS ONLY"
    }

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = ORACLE_RESERVED
}

/**
 * SQL Server 方言（2012+）：`OFFSET {offset|0} ROWS FETCH NEXT {count} ROWS ONLY`。
 *
 * 依据：T-M22-03，同 MyBatis-Plus SqlServerDialect 的 2012+ OFFSET/FETCH 语法，
 * 无 offset 时按 0 渲染。标识符引用方括号 `[x]`。
 */
object SqlServerDialect : Dialect {
    override val name: String = "SQL Server"

    override fun renderLimit(limit: LimitClause): String =
        "OFFSET ${renderLimitValue(limit.offset ?: SqlValue.Literal("0"))} ROWS FETCH NEXT ${renderLimitValue(limit.count)} ROWS ONLY"

    override fun quoteIdentifier(identifier: String): String = "[$identifier]"

    override val reservedWords: Set<String> = SQLSERVER_RESERVED
}

/**
 * H2 方言：`LIMIT {count} OFFSET {offset}`（无 offset 时仅 `LIMIT {count}`）。
 *
 * 依据：T-M22-03，同 PostgreSQL 的 LIMIT/OFFSET 形态。标识符引用双引号 `"x"`。
 * 关键字判定：按 PostgreSQL 集合近似（H2 关键字表与 PG 高度重叠，文档化近似）。
 */
object H2Dialect : Dialect {
    override val name: String = "H2"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = PG_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderPgStyleUpsert(conflictKey, updateColumns)
}

/**
 * SQLite 方言：同 H2 的 `LIMIT {count} OFFSET {offset}`。
 *
 * 依据：T-M22-03，SQLite 原生支持 `LIMIT count OFFSET offset`。标识符引用双引号 `"x"`。
 */
object SqliteDialect : Dialect {
    override val name: String = "SQLite"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = SQLITE_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderPgStyleUpsert(conflictKey, updateColumns)
}

/**
 * MariaDB 方言：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 依据：T-M22-03，MariaDB 兼容 MySQL LIMIT 语法。标识符引用反引号。
 * 关键字判定：按 MySQL 集合（MariaDB 关键字表与 MySQL 高度重叠）。
 */
object MariaDbDialect : Dialect {
    override val name: String = "MariaDB"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val reservedWords: Set<String> = MYSQL_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderMySqlStyleUpsert(updateColumns)
}

/**
 * DM（达梦）方言：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 依据：T-M22-03；T-M43 达梦官方社区实证——DM8 原生支持 4 种分页写法（`LIMIT N` / `LIMIT M, N` /
 * `LIMIT M OFFSET N` / `OFFSET..FETCH`），本实现取 `LIMIT [offset,] count` 形态（官方支持的写法之一）。
 * 标识符引用双引号 `"x"`。关键字判定：按 Oracle 集合近似（达梦兼容 Oracle，文档化近似）。
 * upsert：无原生简洁形态（MERGE 复杂）→ 默认 null 降级纯 INSERT。
 */
object DmDialect : Dialect {
    override val name: String = "DM（达梦）"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = ORACLE_RESERVED
}

/**
 * KingBase（人大金仓）方言：同 PostgreSQL 的 `LIMIT {count} OFFSET {offset}`。
 *
 * 依据：T-M22-03（假设性标注：人大金仓兼容 PostgreSQL）。标识符引用双引号 `"x"`。
 * 关键字判定：按 PostgreSQL 集合（人大金仓兼容 PG，文档化近似）。
 * upsert：同 PG 的 `ON CONFLICT` 形态（金仓兼容 PG 语法）。
 */
object KingBaseDialect : Dialect {
    override val name: String = "KingBase（人大金仓）"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = PG_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderPgStyleUpsert(conflictKey, updateColumns)
}

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
object DialectRegistry {

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    val dialects: List<Dialect> = listOf(
        MySqlDialect,
        PostgreSQLDialect,
        OracleDialect,
        SqlServerDialect,
        H2Dialect,
        SqliteDialect,
        MariaDbDialect,
        DmDialect,
        KingBaseDialect,
    )

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 [Dialect.name]）
     * @return 匹配方言；未知名称返回 [MySqlDialect]
     */
    fun byName(name: String?): Dialect =
        dialects.firstOrNull { it.name == name } ?: MySqlDialect
}
