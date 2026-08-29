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
 *  - upsert 冲突子句：[renderUpsert]（T-M43 方言差异点第 4 项；null → 无「INSERT+后缀」原生
 *    upsert，渲染降级纯 INSERT）与 [renderUpsertStatement]（P-立项-05：整语句形态，如 H2 MERGE..KEY）
 *
 * 新增方言：实现本接口并注册进 [DialectRegistry]（五件套：分页形态、引用符号、保留字集合、upsert 形态、测试）。
 * 验证基线（P-立项-05）：各方言输出形态经 H2 兼容模式 / SQLite 真实引擎 / 官方文档验证，
 * 详见 docs/开发计划/20260828-2000-P-立项-05-方言输出真实验证-报告.md。
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
     * 返回 null → 本方言无「INSERT + 后缀」形态的原生 upsert 语法（Oracle/SQL Server/DM 的
     * 原生形态是复杂 MERGE；P-立项-05 修正：H2 的 `ON CONFLICT` 在其任何模式下都不可用），
     * 渲染方降级为纯 INSERT（保证 SQL 在本方言可执行）；非 null → 追加到 INSERT 语句末尾的冲突子句。
     *
     * 若本方言的原生 upsert 是**整语句形态**（如 H2 的 MERGE..KEY），则实现
     * [renderUpsertStatement] 并保持本方法返回 null。
     *
     * @param conflictKey    冲突键列名（原始值，实现内按需引用；MySQL 形态无冲突键列表，仍须传入用于勾选判定）
     * @param updateColumns  冲突后更新列名（原始值，不含冲突键；实现内按需引用）
     */
    fun renderUpsert(conflictKey: String, updateColumns: List<String>): String? = null

    /**
     * UPSERT **整语句**形态（P-立项-05）：方言原生 upsert 非「INSERT + 后缀」结构时使用——
     * 如 H2 的 `MERGE INTO t (cols) KEY (conflict) VALUES (...)`（H2 2.3.232 引擎实证：
     * `ON CONFLICT` 后缀在任何 H2 模式均语法错误，`DO UPDATE` 仅存在于真 PostgreSQL）。
     *
     * 返回非 null → 渲染方（BatchModel UPSERT 形态）直接使用该整语句，不再渲染 INSERT 基座；
     * 默认返回 null → 走 [renderUpsert] 后缀形态（或其 null 降级纯 INSERT）。
     *
     * @param tableName   表名（原始值，实现内按需引用）
     * @param columns     全部列名（原始值，含冲突键，与 VALUES 的 `?` 顺序一致；实现内按需引用）
     * @param conflictKey 冲突键列名（原始值；必在 [columns] 内）
     */
    fun renderUpsertStatement(tableName: String, columns: List<String>, conflictKey: String): String? = null
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
 *
 * 验证来源（P-立项-05）：分页/引用/UPSERT 经 H2 MySQL 兼容模式实测（`DialectH2VerificationTest`，
 * `LIMIT 5, 10` / 反引号 / `ON DUPLICATE KEY UPDATE ... VALUES()` 全通过）；
 * 保留字 [MYSQL_RESERVED] 为 MySQL 8.0 官方保留字（R）全量。
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
 *
 * 验证来源（P-立项-05）：分页/引用经 H2 PostgreSQL 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字 [PG_RESERVED] 为 PG 官方附录全量（含 reserved (can be function or type name) 类别，
 * 原集合漏 binary/isnull/notnull 等 7 词已补）；UPSERT `ON CONFLICT (k) DO UPDATE SET col =
 * EXCLUDED.col` 为 PG 9.5+ 官方文法（真 PG 验证在 Docker 测试，本机 H2 不支持 DO UPDATE 无法模拟）。
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
 *
 * 验证来源（P-立项-05）：分页/引用经 H2 Oracle 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字 [ORACLE_RESERVED] 为 Oracle 官方保留字全量；UPSERT 无原生简洁形态
 * （官方仅 MERGE INTO）→ null 降级纯 INSERT。
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
 * 验证来源（P-立项-05）：分页/引用经 H2 MSSQLServer 兼容模式实测（`DialectH2VerificationTest`）。
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
 * 标识符引用双引号 `"x"`。
 *
 * 验证来源（P-立项-05，移除原「≈PG 关键字」假设性标注）：
 *  - 分页/引用：H2 2.3.232 引擎实测（`DialectH2VerificationTest` regular 模式 TC-01~08 全通过）；
 *  - 保留字：H2 官方关键字表全量（[H2_RESERVED]），引擎实测核对（原 PG 集合缺
 *    key/value/values/day/year/interval/row/if/minus/qualify/rownum/top 等 20+ 词，裸用即语法错误）；
 *  - upsert：H2 原生形态为整语句 `MERGE INTO t (cols) KEY (conflict) VALUES (...)`
 *    （[renderUpsertStatement]）——`ON CONFLICT` 在 H2 任何模式均语法错误（引擎实证），
 *    原 `ON CONFLICT (k) DO UPDATE SET ...` 后缀输出已删除。
 */
object H2Dialect : Dialect {
    override val name: String = "H2"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = H2_RESERVED

    override fun renderUpsertStatement(tableName: String, columns: List<String>, conflictKey: String): String? {
        val cols = columns.joinToString(", ") { quoteIfKeyword(it) }
        return "MERGE INTO ${quoteIfKeyword(tableName)} ($cols) KEY (${quoteIfKeyword(conflictKey)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
    }
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
 * 标识符引用反引号。关键字判定：[MARIA_RESERVED]（P-立项-05 移除原「= MySQL 集合」假设——
 * MariaDB 官方保留字含 except/intersect/binary/offset/returning 等 MySQL 8.0 非保留词，
 * 裸用作列名在 MariaDB 语法错误）。
 *
 * 验证来源：分页/引用/UPSERT 形态经 H2 MariaDB 兼容模式实测（`DialectH2VerificationTest`）；
 * 保留字集合为 MariaDB 官方保留字全量（mariadb.com/kb/en/reserved-words/ 逐词核对）。
 */
object MariaDbDialect : Dialect {
    override val name: String = "MariaDB"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val reservedWords: Set<String> = MARIA_RESERVED

    override fun renderUpsert(conflictKey: String, updateColumns: List<String>): String =
        renderMySqlStyleUpsert(updateColumns)
}

/**
 * DM（达梦）方言：同 MySQL 的 `LIMIT [offset,] count`。
 *
 * 标识符引用双引号 `"x"`。关键字判定：[DM_RESERVED]（P-立项-05 移除原「≈Oracle 集合」假设——
 * 达梦官方附录 1 保留字与 Oracle 双向差异大：DM 额外保留 top/section/domain/identity/if/int/
 * index/array 等（Oracle 集合漏包）；Oracle 保留的 number/size/level/raw 等 DM 仅关键字
 * （错包后双引号大小写敏感易失配））。
 * upsert：无原生简洁形态（DM 官方仅支持 MERGE INTO 复杂形态，ON CONFLICT/ON DUPLICATE KEY
 * UPDATE 均不支持——官方文档证实）→ 默认 null 降级纯 INSERT。
 *
 * 验证来源：达梦官方文档（eco.dameng.com《DM8 SQL 语言使用手册》分页文法 + 附录 1 保留字 +
 * FAQ），真实环境验证待后续（`DialectDockerVerificationTest` 无 DM 容器，需用户侧执行）。
 */
object DmDialect : Dialect {
    override val name: String = "DM（达梦）"

    override fun renderLimit(limit: LimitClause): String = renderMySqlStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = DM_RESERVED
}

/**
 * KingBase（人大金仓）方言：同 PostgreSQL 的 `LIMIT {count} OFFSET {offset}`。
 *
 * 标识符引用双引号 `"x"`。关键字判定：[KINGBASE_RESERVED]（P-立项-05 移除原「≈PG 集合」
 * 假设——金仓 V9 官方保留字含 PG 没有的 auto_increment/binary/collation/ilike/minus/nvl/
 * online/rowid/rownum/synonym/top 等，裸用作列名在金仓语法错误）。
 * upsert：同 PG 的 `ON CONFLICT` 形态——金仓官方 INSERT 文法含 on_conflict_clause，
 * 官方示例即 `ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname`。
 *
 * 验证来源：金仓官方文档（help.kingbase.com.cn V8/V9《SQL 语句:INSERT》+《SQL保留字和关键字》
 * 章节 22），真实环境验证待后续。
 */
object KingBaseDialect : Dialect {
    override val name: String = "KingBase（人大金仓）"

    override fun renderLimit(limit: LimitClause): String = renderPgStyleLimit(limit)

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = KINGBASE_RESERVED

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
