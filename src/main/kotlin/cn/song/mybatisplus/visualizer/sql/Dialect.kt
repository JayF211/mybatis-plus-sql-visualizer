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
 *  - identity 包装：[renderInsertWrapper]（O-52；null → 本方言无需包装，默认）
 *
 * 新增方言：实现本接口并注册进 [DialectRegistry]（六件套：分页形态、引用符号、保留字集合、
 * 折叠方向族 [identifierFolding]、upsert 形态、测试）。
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
     * 标识符**按名字大小写来源**引用（O-42 / T-OracleQuoteCaseSource；O-51 / T-IdentifierFoldingFamily
     * 把「单方言特判」升级为按 [identifierFolding] 折叠方向族分流）。
     *
     * 与 [quoteIfKeyword] 的差别：本方法额外知道该名字**从哪来**——同一串字符在两种来源下的
     * 「正确引用形态」可以相反。默认实现按 [identifierFolding] 分流：NONE 族逐字节等价于
     * [quoteIfKeyword]（故 MySQL / MariaDB / H2 / SQLite / SQL Server / DM 产物不变），
     * UPPER / LOWER 两族只在目录来源下加判定。
     *
     * 分流口径（条件**显式含** [IdentifierCaseSource.CATALOG]）：
     *  - CATALOG + [IdentifierFolding.UPPER]：名字是**字典里的真实名字**（自省元数据返回，未归一）。
     *    Oracle 裸标识符折叠为大写，故「含小写字母」的名字**必须加引号**才能命中；全大写才可裸写；
     *  - CATALOG + [IdentifierFolding.LOWER]：裸标识符折叠为小写，故「含大写字母」的名字**必须加引号**
     *    （以引号创建的大写名裸写后被折叠为小写 → 查不到，方向与 UPPER 族相反）；
     *  - CATALOG + [IdentifierFolding.NONE]：不折叠（或大小写不敏感匹配）→ 按名判定无意义，落 [quoteIfKeyword]；
     *  - [IdentifierCaseSource.ENTITY]：名字是**小写派生名**（`@TableName`/`@TableField` 注解值，
     *    或 camelCase → snake_case 的 [cn.song.mybatisplus.visualizer.util.NamingStrategy.toSnakeCase] 产物）
     *    ——这些正是「不带引号创建」的名字，折叠后恰好命中，**加引号反而必然失败**。
     *    故实体来源**不论族**都走既有 [quoteIfKeyword] 逐字不变（这是 C 方案「无条件收紧」被否决的依据；
     *    族判定只允许写在 CATALOG 分支里——写进默认路径会连带污染 ENTITY）。
     *
     * 未接线点（[IdentifierCaseSource.ENTITY] 默认）维持旧行为——新增重载 + 逐点接线，不改既有函数语义。
     */
    fun quoteIfNeeded(identifier: String, caseSource: IdentifierCaseSource): String =
        if (caseSource == IdentifierCaseSource.CATALOG) quoteCatalogName(identifier) else quoteIfKeyword(identifier)

    /**
     * 标识符**折叠方向族**（O-51 / T-IdentifierFoldingFamily）：本方言把裸标识符折叠到哪个方向。
     *
     * [quoteIfNeeded] 的目录来源分支按本属性分流——同一串字典名在不同族下的正确引用形态相反
     * （判据落点见 [quoteIfNeeded] 的 KDoc）：
     *  - [IdentifierFolding.UPPER]（Oracle 真库实测：`FROM dual` 命中 `DUAL`、`FROM "dual"` → ORA-00942）；
     *  - [IdentifierFolding.LOWER]（PG / KingBase 真机实测：`FROM INFORMATION_SCHEMA.TABLES` 折为小写命中、
     *    `FROM information_schema."TABLES"` → relation does not exist）；
     *  - [IdentifierFolding.NONE]（不折叠或大小写不敏感匹配 → 按名判定无意义）。
     *
     * 默认 [IdentifierFolding.NONE]（保持既有行为）。维度是**折叠方向**而非数据库品牌——DM8 实测大小写
     * 不敏感（`sys.sysobjects` / `sys."sysobjects"` / `sys."SYSOBJECTS"` 三形态均 1029 行）→ NONE，
     * 与 DBX 把 `{dameng, db2}` 归 `UPPER_FOLDING` 的口径分歧按我方实测处理（计划 §八 待验证前提 1）。
     *
     * H2 2.3.232 引擎实测裸写折为大写（与 Oracle 同族），本轮**有意维持 NONE 不扩面**（缺口登记为后续）；
     * SQLite / SQL Server 未实测（SQL Server 大小写取决于 collation）。
     */
    val identifierFolding: IdentifierFolding get() = IdentifierFolding.NONE

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
     * @param caseSource     列名的大小写来源（O-51）：目录来源时按 [identifierFolding] 族引用，与 INSERT 基座
     *                       （[cn.song.mybatisplus.visualizer.model.BatchModel.renderSql]）同一判定——否则会产出
     *                       「基座加引号、后缀裸写」的半包形态（PG 族含大写目录名下后者被折叠为小写 → 列不存在）
     */
    fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

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
     * @param caseSource  列名的大小写来源（O-51）：语义同 [renderUpsert] 的同名参数
     */
    fun renderUpsertStatement(
        tableName: String,
        columns: List<String>,
        conflictKey: String,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

    /**
     * INSERT 的 **identity 包装**（O-52 / T-IdentityInsertWrap）：显式把值写进自增列所需的
     * 前置/后置语句（SQL Server 为 `SET IDENTITY_INSERT <t> ON/OFF`）。
     *
     * 返回 null → **本方言无需包装**（默认）——调用方按原样渲染 INSERT，
     * 故不含自增列的 INSERT 与全部非包装方言的产物**逐字节不变**。
     * 非 null → 调用方以返回值整体替换 INSERT 文本（返回值内已含 [sql] 原文）。
     *
     * **判定不在这里**：「本次 INSERT 的列清单是否含自增列」由调用方判定（自增标记只有生成侧
     * 持有：CRUD 侧 [cn.song.mybatisplus.visualizer.generator.CrudColumn.autoGenerated]、回滚侧
     * 快照登记的 identity 列），本方法只回答「本方言的包装形态长什么样」。
     *
     * 表名走 [quoteIfNeeded]（**与 INSERT 基座同一函数、同一 [caseSource]**）——包装与 INSERT
     * 里的引用形态因此一致（SQL Server 上两者是不同的语法位，引用形态不一致会指向不同对象）。
     *
     * @param tableName  表名（原始值，实现内按 [quoteIfNeeded] 引用）
     * @param sql        已渲染的 INSERT 文本（不含结尾分号）
     * @param caseSource 表名的大小写来源（O-42/O-51）：语义同 [renderUpsert] 的同名参数
     */
    fun renderInsertWrapper(
        tableName: String,
        sql: String,
        caseSource: IdentifierCaseSource = IdentifierCaseSource.ENTITY,
    ): String? = null

    /**
     * EXPLAIN 执行计划语句（M37.2 / P-立项-06 N-1）：把任意查询包装为本方言的 EXPLAIN 形态。
     *
     * 返回 null → 本方言不支持（调用方按钮降级提示）。**支持面与输出格式的单一事实源是
     * [ExplainSupportTable]**（T-ExplainChainHardening T-ECH-02）：已接入的 7 个方言覆写本方法时
     * 读表渲染，未覆写者（Oracle / SQL Server）继承默认 null。支持矩阵真机实证（DBX 通道 2026-09-02）：
     *  - MySQL 5.7/8.4、MariaDB：`EXPLAIN <sql>`（T-ExplainFirstNative 对齐 IDEA 原生
     *    MysqlBaseRawPlanData 表格形态——原生 Explain Plan 对 MySQL 家族跑的即裸 EXPLAIN
     *    表格行，按 id 嵌套建树；M37.2 曾用 `EXPLAIN FORMAT=JSON`，属自创形态且全树无
     *    渲染器消费，真机网格只能呈现一坨 JSON 文本）；
     *  - PostgreSQL：`EXPLAIN (FORMAT JSON, VERBOSE)`（PG 16 实测；PG 18.3 复验为单列 JSON 文本）；
     *  - KingBase：`EXPLAIN (FORMAT JSON)`（KingbaseES V8 实测，PG 兼容完整）；
     *  - DM：`EXPLAIN <sql>`（DM8 实测，返回 #NSET2 计划文本）；
     *  - H2：`EXPLAIN PLAN FOR`；SQLite：`EXPLAIN QUERY PLAN`（引擎级单测覆盖）；
     *  - Oracle（需两步链查 PLAN_TABLE）与 SQL Server（需会话级 SET SHOWPLAN_XML）不接入。
     *
     * 被包语句的合法性由 [ExplainGate] 判定（T-ECH-01，两链同调；本方法自身不做首关键字判定——
     * 直接调用者如 MCP 链在调用点接门）。
     *
     * @param sql 不含结尾分号的查询语句
     * @return 可直接提交执行的 EXPLAIN 语句，null = 不支持
     */
    fun renderExplainStatement(sql: String): String? = null

    /**
     * T-ExplainDbl：幂等 EXPLAIN 包装——文本已以 EXPLAIN 关键字开头（词边界、忽略大小写）时
     * 原样返回（去首尾空白与结尾分号后），否则委托 [renderExplainStatement]。
     *
     * 根因场景：首次点击「执行计划」后包装好的 EXPLAIN 语句经执行链写入控制台文档，
     * 再点一次在已包装文本上二次加前缀 → `EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...`
     * → MySQL 1064 语法错误。
     *
     * T-ExplainChainHardening T-ECH-01：**幂等短路之后**另接一道 [ExplainGate] 门——被包语句首关键字
     * 不在白名单（DML/DDL/`SELECTED`/`SELECT_foo` 之类，或多语句脚本中任一条非查询）时返回 null，
     * 由调用方按「非查询形态」提示且不执行（D1=A 严格判定）。门排在短路之后是硬约束：已包装文本
     * 首词是 EXPLAIN（不在白名单），若门在前，T-ExplainDbl 的防双前缀价值会被自己的门打掉。
     */
    fun renderExplainStatementIdempotent(sql: String): String? {
        val trimmed = sql.trim().trimEnd(';').trim()
        if (startsWithExplainKeyword(trimmed)) return trimmed
        if (!ExplainGate.isExplainableSource(trimmed)) return null
        return renderExplainStatement(trimmed)
    }

    /**
     * DELETE 语句是否支持 `LIMIT n` 尾段（O-8 缺陷 C）：MySQL 语法族支持
     * （`DELETE FROM t WHERE ... LIMIT n`），PostgreSQL/Oracle/SQL Server 等不支持——
     * DELETE 的 `last("LIMIT n")` 尾段仅在本方言为 true 时渲染，否则静默省略
     * （与分页子句的方言渲染策略一致）。
     */
    val supportsDeleteLimit: Boolean get() = false
}

/**
 * 标识符名字的**大小写来源**（O-42 / T-OracleQuoteCaseSource）：[Dialect.quoteIfNeeded] 的分流依据。
 *
 * 引用判定需要它，是因为同一串字符在两种来源下的正确形态可能相反——Oracle 把裸标识符折叠为
 * 大写，于是「字典里含小写字母的真实名字」必须加引号才命中，而「以裸写创建的小写派生名」
 * 恰恰不能加引号。
 *
 * 判据落点（实测定位，非计划初稿所写）：目录来源的**唯一**入口是
 * [cn.song.mybatisplus.visualizer.database.DbTableSpecAdapter.toSpec]（`das.name` / `col.name`
 * 直取自省元数据）；实体来源经 [cn.song.mybatisplus.visualizer.psi.EntityResolver] 产出
 * `ColumnInfo`（`:103`/`:109`/`:151`/`:502` 走 `NamingStrategy.toSnakeCase`），再由
 * `GenerateCrudSqlFromEntityAction.entitySpec` 的 `toCrudSpec()`（`:129`）适配进
 * [cn.song.mybatisplus.visualizer.generator.CrudTableSpec]——该路径**逐字不变**。
 */
enum class IdentifierCaseSource {
    /** 目录来源：自省元数据（DbTable/DasTable）返回的**字典真实名字**，未做大小写归一。 */
    CATALOG,

    /** 实体来源：注解值或 camelCase → snake_case 的**小写派生名**（也是默认值，保持既有行为）。 */
    ENTITY,
}

/**
 * 标识符**折叠方向族**（O-51 / T-IdentifierFoldingFamily）：[Dialect.quoteIfNeeded] 的目录来源分流依据。
 *
 * 与 [IdentifierCaseSource] 正交——后者说「名字从哪来」，本枚举说「本方言把裸标识符折到哪个方向」；
 * 二者相乘才决定引用形态：同一串字典名在 UPPER 族要「含小写即包」，在 LOWER 族要「含大写即包」，
 * 在 NONE 族则不判。
 *
 * 归属依据（真机实测，2026-09-22，DBX MCP 只读通道）：
 *  - [UPPER] Oracle：`SELECT COUNT(*) FROM dual` → 1（裸写折为大写命中）、`FROM "dual"` → `ORA-00942`；
 *  - [LOWER] PostgreSQL / KingBase：`SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` → 366 / 492 行
 *    （裸写折为小写命中小写字典名）、`FROM information_schema."TABLES"` → `relation ... does not exist`
 *    （引号保留原样 → 大写名不存在）；
 *  - [NONE] MySQL / MariaDB / H2 / SQLite / SQL Server / DM：DM8 实测三形态同命中（大小写不敏感）。
 */
enum class IdentifierFolding {
    /** 裸标识符折叠为**大写**（Oracle）：字典名含小写字母时必须加引号。 */
    UPPER,

    /** 裸标识符折叠为**小写**（PostgreSQL / KingBase）：字典名含大写字母时必须加引号。 */
    LOWER,

    /** 不折叠（或大小写不敏感匹配）：目录名按名判定无意义，逐字走 [Dialect.quoteIfKeyword]。 */
    NONE,
}

/**
 * 目录来源名字的按族引用（O-51）：[Dialect.identifierFolding] 决定「哪种大小写形态必须加引号」。
 *
 * 不满足折叠命中条件时落回 [Dialect.quoteIfKeyword]——**保留字命中即包的既有行为不变**
 * （Oracle 的 `NUMBER` / `LEVEL`、PG 的 `table` 等仍包）。
 */
private fun Dialect.quoteCatalogName(identifier: String): String = when (identifierFolding) {
    IdentifierFolding.UPPER ->
        if (identifier.any { it.isLowerCase() }) quoteIdentifier(identifier) else quoteIfKeyword(identifier)

    IdentifierFolding.LOWER ->
        if (identifier.any { it.isUpperCase() }) quoteIdentifier(identifier) else quoteIfKeyword(identifier)

    IdentifierFolding.NONE -> quoteIfKeyword(identifier)
}

/**
 * T-ExplainDbl：判断语句是否已以 EXPLAIN 关键字开头——紧随字符须非字母/数字/下划线
 * （词边界），避免 `EXPLAINING_TABLE` 之类标识符误判。
 */
private fun startsWithExplainKeyword(sql: String): Boolean {
    if (!sql.startsWith("EXPLAIN", ignoreCase = true)) return false
    return sql.length == 7 || (!sql[7].isLetterOrDigit() && sql[7] != '_')
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
 *
 * O-51：列名引用走 [Dialect.quoteIfNeeded]（而非裸 [Dialect.quoteIfKeyword]），与 INSERT 基座同一判定；
 * MySQL / MariaDB 为 NONE 族，实体/目录来源产物逐字节不变。
 */
private fun Dialect.renderMySqlStyleUpsert(
    updateColumns: List<String>,
    caseSource: IdentifierCaseSource,
): String = "ON DUPLICATE KEY UPDATE " +
    updateColumns.joinToString(", ") { "${quoteIfNeeded(it, caseSource)} = VALUES(${quoteIfNeeded(it, caseSource)})" }

/**
 * 共享 upsert 辅助：PostgreSQL 形态 `ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col, ...`。
 * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
 *
 * O-51：列名引用走 [Dialect.quoteIfNeeded]——PG 族（LOWER）目录来源含大写时必须加引号，否则会产出
 * 「基座加引号、后缀裸写」的半包形态：`INSERT INTO "Issue_New" ("Id") ... ON CONFLICT (Id) DO UPDATE
 * SET Id = EXCLUDED.Id` 里的裸写 `Id` 被折叠为小写 → 列不存在。SQLite 为 NONE 族，产物逐字节不变。
 */
private fun Dialect.renderPgStyleUpsert(
    conflictKey: String,
    updateColumns: List<String>,
    caseSource: IdentifierCaseSource,
): String =
    "ON CONFLICT (${quoteIfNeeded(conflictKey, caseSource)}) DO UPDATE SET " +
        updateColumns.joinToString(", ") {
            "${quoteIfNeeded(it, caseSource)} = EXCLUDED.${quoteIfNeeded(it, caseSource)}"
        }

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

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val reservedWords: Set<String> = MYSQL_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)

    // T-ECH-02：支持面/输出格式读 [ExplainSupportTable]（单一事实源），逐字节产物由 DialectExplainTest 冻结断言
    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
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

    /**
     * O-51：PG 裸标识符**折叠为小写**，故目录来源（字典真实名字）按「含大写字母即加引号」判定
     * ——以引号创建的大写名裸写后被折叠为小写 → 查不到。
     *
     * 真机依据（DBX MCP 只读实测，2026-09-22）：`SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES`
     * → 366 行（裸写折为小写，命中小写字典名）；`FROM information_schema."tables"` → 366 行；
     * `FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist`。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.LOWER

    override val reservedWords: Set<String> = PG_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)

    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
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

    /**
     * O-42 的「含小写字母即加引号」规则自 O-51 起由基类按族实现（[Dialect.quoteIfNeeded]），
     * 本类**不再覆写**该方法——只声明族归属，判据落在 `IdentifierFolding.UPPER` 分支。
     *
     * 真库依据（Oracle Free 23 / FREEPDB1，DBX MCP 实测）：
     *  - `SELECT COUNT(*) FROM dbx_test.issue_new` → `ORA-00942: table or view "DBX_TEST"."ISSUE_NEW"
     *    does not exist`（裸写折叠成大写，命不中以引号创建的小写表）；
     *  - `SELECT COUNT(*) FROM dbx_test."issue_new"` → 成功（100 行）；
     *  - `SELECT COUNT(*) FROM dual` → 1，`FROM "dual"` → `ORA-00942`（裸写折叠为 DUAL）。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.UPPER

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

    /**
     * O-52 / T-IdentityInsertWrap：SQL Server 拒绝向 identity 列显式写值（错误 544
     * `Cannot insert explicit value for identity column in table ... when IDENTITY_INSERT is set to OFF`），
     * 必须先 `SET IDENTITY_INSERT <表> ON`，语句结束后 OFF。
     *
     * 该开关是**会话级**且同一时刻每会话只能有一个表为 ON，故包装必须**逐条 INSERT 成对**出现
     * （多条 INSERT 合批时不能只在整段首尾各包一次）。
     *
     * 表名与 INSERT 基座共用 [quoteIfNeeded]（同一 [caseSource]）——`SET IDENTITY_INSERT` 的目标位
     * 与 `INSERT INTO` 的目标位是两处独立语法位，引用形态不一致会指向不同对象。
     * **未真机验证**：本环境无 SQL Server 实例（DBX MCP 在册连接无 sqlserver 类型），形态取自
     * DBX `d715b16b` 同款实现与 T-SQL 文法；真机端到端登记为环境受限未验证。
     */
    override fun renderInsertWrapper(
        tableName: String,
        sql: String,
        caseSource: IdentifierCaseSource,
    ): String {
        val ref = quoteIfNeeded(tableName, caseSource)
        return "SET IDENTITY_INSERT $ref ON;\n$sql\nSET IDENTITY_INSERT $ref OFF;"
    }
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

    override fun renderUpsertStatement(
        tableName: String,
        columns: List<String>,
        conflictKey: String,
        caseSource: IdentifierCaseSource,
    ): String? {
        val cols = columns.joinToString(", ") { quoteIfNeeded(it, caseSource) }
        return "MERGE INTO ${quoteIfNeeded(tableName, caseSource)} ($cols) KEY (${quoteIfNeeded(conflictKey, caseSource)}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
    }

    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
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

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)

    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
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

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "`$identifier`"

    override val reservedWords: Set<String> = MARIA_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderMySqlStyleUpsert(updateColumns, caseSource)

    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
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

    override val supportsDeleteLimit: Boolean = true

    override fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    override val reservedWords: Set<String> = DM_RESERVED

    override fun renderExplainStatement(sql: String): String? = "EXPLAIN $sql"
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

    /**
     * O-51：金仓与 PG 同折叠方向（裸标识符折叠为小写）→ LOWER 族。
     *
     * 真机依据（KingbaseES V8，DBX MCP 只读实测，2026-09-22）：`SELECT COUNT(*) FROM
     * INFORMATION_SCHEMA.TABLES` → 492 行（裸写折为小写命中）；`FROM information_schema."TABLES"`
     * → `kb: relation "information_schema.TABLES" does not exist`。此前为「按 PG 兼容推断」，
     * 本轮实测证实（计划 §八 待验证前提 2 已可升级为已证实）。
     */
    override val identifierFolding: IdentifierFolding = IdentifierFolding.LOWER

    override val reservedWords: Set<String> = KINGBASE_RESERVED

    override fun renderUpsert(
        conflictKey: String,
        updateColumns: List<String>,
        caseSource: IdentifierCaseSource,
    ): String = renderPgStyleUpsert(conflictKey, updateColumns, caseSource)

    override fun renderExplainStatement(sql: String): String? = ExplainSupportTable.render(name, sql)
}

/**
 * T-ExplainChainHardening T-ECH-02：EXPLAIN **支持面与输出格式**的显式单一事实源（O-48）。
 *
 * 此前「哪些方言支持 EXPLAIN」只体现为 [Dialect.renderExplainStatement] 的覆写（默认 null 降级），
 * 「输出是什么格式」硬编码在各方言字面量里——下游（索引建议器）只能按**方言名**猜格式，
 * 于是 PG 家族实际产出 `FORMAT JSON` 单列文本、建议器却按文本树正则解析（真缺陷，见
 * [cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor]）。本表把两者收敛为数据：
 *  - [ExplainSupport.template]：EXPLAIN 语句**前缀**（渲染 = `template + sql`，含尾随空格）；
 *  - [ExplainSupport.format]：计划输出的**形态**（[OutputFormat]）——消费方按格式分派解析器，
 *    不再按方言名猜。
 *
 * 登记口径：只登记**本插件真正接入**的方言（7 个）——[Dialect.renderExplainStatement] 未覆写的
 * Oracle / SQL Server（各有独立前置：Oracle 两步链查 PLAN_TABLE、SQL Server 会话级
 * `SET SHOWPLAN_XML`）**不入表**，[formatOf] 返回 null → 消费方静默降级（与「方言不支持」同形）。
 * 「表里 template」与「逐方言渲染结果」由 `DialectExplainTest` 的守卫断言钉住：模板字面量在
 * **测试侧冻结成期望值**再比对（表与实现同源比较是同义反复，测不出抄错模板）。
 *
 * 格式取值依据（与 [Dialect.renderExplainStatement] 的支持矩阵同源的真机/引擎实证）：
 *  - MySQL / MariaDB：`EXPLAIN <sql>` 返回**结果集**（id/select_type/table/type/key/rows/Extra 列）→ [OutputFormat.TABLE]；
 *  - PostgreSQL / KingBase：`EXPLAIN (FORMAT JSON …)` 返回**单列 JSON 文本**（PG 18.3 实测紧凑单行、
 *    KingbaseES V8 实测多行美化——**空白形态不敏感**，解析走 JSON 而非字符串匹配）→ [OutputFormat.JSON]；
 *  - H2（`EXPLAIN PLAN FOR`）、DM（`EXPLAIN`，返回 `#NSET2` 计划文本）、SQLite（`EXPLAIN QUERY PLAN`
 *    逐行 `SCAN t` 文本）：**文本树/文本行**→ [OutputFormat.TEXT]。
 *
 * 计划正文里列的 `XML` 形态（SQL Server `SET SHOWPLAN_XML` 的输出）**不设枚举成员**：本插件无任何
 * 产出点，`EnumReachabilityGuardTest` 判据（「零产出 且 类型活跃」即违规）会直接判红——接入
 * SQL Server 时再加成员并同步该守卫。
 */
object ExplainSupportTable {

    /** EXPLAIN 计划输出形态（消费方按此分派解析器，而非按方言名）。 */
    enum class OutputFormat {
        /** 单列 JSON 文本（PG 家族 `FORMAT JSON`）。 */
        JSON,

        /** 文本树/文本行（H2 / DM / SQLite）。 */
        TEXT,

        /** 结果集表格（MySQL 家族）。 */
        TABLE,
    }

    /** 单方言登记项：[template] 语句前缀（含尾随空格），[format] 计划输出形态。 */
    data class ExplainSupport(val template: String, val format: OutputFormat)

    /** 方言显示名（[Dialect.name]）→ 登记项。键即 [DialectRegistry.dialects] 中已接入 EXPLAIN 的方言名。 */
    val byDialectName: Map<String, ExplainSupport> = mapOf(
        MySqlDialect.name to ExplainSupport("EXPLAIN ", OutputFormat.TABLE),
        PostgreSQLDialect.name to ExplainSupport("EXPLAIN (FORMAT JSON, VERBOSE) ", OutputFormat.JSON),
        H2Dialect.name to ExplainSupport("EXPLAIN PLAN FOR ", OutputFormat.TEXT),
        SqliteDialect.name to ExplainSupport("EXPLAIN QUERY PLAN ", OutputFormat.TEXT),
        MariaDbDialect.name to ExplainSupport("EXPLAIN ", OutputFormat.TABLE),
        DmDialect.name to ExplainSupport("EXPLAIN ", OutputFormat.TEXT),
        KingBaseDialect.name to ExplainSupport("EXPLAIN (FORMAT JSON) ", OutputFormat.JSON),
    )

    /** 本方言计划输出形态；未接入（Oracle / SQL Server / 未知名）返回 null。 */
    fun formatOf(dialectName: String): OutputFormat? = byDialectName[dialectName]?.format

    /**
     * 渲染 EXPLAIN 语句（[Dialect.renderExplainStatement] 的读表实现）。
     *
     * @return `template + sql`；未接入方言返回 null（与「方言不支持」同形）
     */
    fun render(dialectName: String, sql: String): String? = byDialectName[dialectName]?.template?.plus(sql)
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

    /**
     * T-DialectAutoInfer：**渲染方言统一读取**（全部渲染消费点收口此函数）。
     *
     * T-TS-FixC：auto/manual 的**模式判定与手动值解析的唯一事实源在本函数内部**，按
     * 「项目级 > 全局」逐层解析（项目级语义由
     * [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings] 的 effective* 方法承载）：
     *
     *  1. 模式：项目级 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialectAuto]
     *     （启用项目设置且 dialectAuto 非 null 时取项目值）> 全局
     *     [cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialectAuto]（默认 true）。
     *     为什么项目级是权威：项目级「智能推断」是用户对该项目的**显式声明**，全局值只是**默认值**
     *     （与 ProjectPluginSettings.State 的「空/未覆盖=跟随全局」语义同构）。此前本函数直读全局
     *     auto，于是「项目 true + 全局 false」组合下项目声明被静默忽略——调用方已按项目级判为 auto
     *     进入本函数，却被全局 false 拉回手动分支返回全局手动值（T-TS-FixC 验证者实测口径缺陷）。
     *  2. auto=true → 智能推断（[DialectInference.infer]：agent 最近连接 URL > IDE 数据源 URL），
     *     未命中回退 [MySqlDialect]（与 [byName] 未知回退语义一致）。
     *  3. auto=false → 手动值：项目级
     *     [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialect]
     *     （启用且非空）> 全局 [cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialect]。
     *
     * 边界（**有意为之，非缺陷**）：[project]=null 时推断链不可用——[cn.song.mybatisplus.visualizer.log.agent.AgentManager]
     * 与 [DataSourceUrlProbe] 都要求项目上下文，故落回手动值（无项目则取全局手动值）。psi 侧
     * [cn.song.mybatisplus.visualizer.psi.currentDialect] 的调用点全部为无参调用，因此 PSI 路径
     * auto 推断不生效（与合流前一致，无回归）；要让 PSI 路径生效，须由调用方传入宿主 PSI 项目。
     *
     * @param project 数据源探测与项目级设置读取用项目（null=无项目上下文，见上「边界」）
     */
    fun effective(project: com.intellij.openapi.project.Project? = null): Dialect {
        val settings = runCatching {
            cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        }.getOrNull()
        // 手动值只解析一次：auto 分支的兜底与手动分支必须同源，否则两个分支会再次分叉
        val manualName = projectManualDialectName(project) ?: settings?.dialect
        val auto = projectDialectAuto(project) ?: settings?.dialectAuto ?: true
        if (!auto) return byName(manualName)
        // T-PS5-04：agentUrl 读轻量持有者（不再经 AgentManager.getInstance 触发项目级服务实例化
        // ——那是「预览一次即驻留 executor+monitor 双守护线程」的根因）；URL 由会话生命周期点回写，
        // 推断优先级语义（agent URL > 数据源 URL > null 回退）逐字不变。
        val agentUrl = cn.song.mybatisplus.visualizer.log.agent.AgentUrlRegistry.lastConnectionUrl(
            project ?: return byName(manualName),
        )
        val inferred = DialectInference.infer(agentUrl, DataSourceUrlProbe.firstUrl(project))
        return inferred.dialectName?.let { byName(it) } ?: MySqlDialect
    }

    /**
     * T-TS-FixC：项目级有效方言模式（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * 复用 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialectAuto] 而非直读
     * `state.dialectAuto`：项目级方法内部已内置「总开关关闭 / dialectAuto=null → 跟随全局」回退语义，
     * 直读会把这层回退丢掉（T-TS-06 已在 SqlPreviewService 侧踩过同款）。
     */
    private fun projectDialectAuto(project: com.intellij.openapi.project.Project?): Boolean? =
        project?.let { p ->
            runCatching {
                cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.getInstance(p).effectiveDialectAuto()
            }.getOrNull()
        }

    /**
     * T-TS-FixC：项目级有效手动方言名（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * 同样复用 [cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.effectiveDialect]
     * （启用且非空才取项目值，否则已回退全局）。
     */
    private fun projectManualDialectName(project: com.intellij.openapi.project.Project?): String? =
        project?.let { p ->
            runCatching {
                cn.song.mybatisplus.visualizer.config.ProjectPluginSettings.getInstance(p).effectiveDialect()
            }.getOrNull()
        }
}
