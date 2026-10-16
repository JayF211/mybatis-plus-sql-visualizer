package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement

/** 仅恢复普通 PSI 读取失败；取消与 VM/链接错误必须穿透转换管道。 */
internal inline fun <T> recoverJpaPsi(block: () -> T): T? = try {
    block()
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (failure: Exception) {
    if (failure is com.intellij.openapi.diagnostic.ControlFlowException) throw failure
    null
}

/** 单次转换内共享解析结果；context 始终是查询宿主，不能换成已找到的实体文件。 */
internal class JpaQueryEntityLookup(private val project: Project?, private val context: PsiElement?) {
    private val classes = HashMap<String, PsiClass?>()
    private val tables = HashMap<String, TableInfo?>()

    fun resolveClass(name: String): PsiClass? {
        if (!classes.containsKey(name)) {
            classes[name] = recoverJpaPsi {
                project?.let { EntityAliasSweep.resolve(it, name, context).psiClass }
            }
        }
        return classes[name]
    }

    fun resolveTable(name: String): TableInfo? {
        if (!tables.containsKey(name)) {
            tables[name] = recoverJpaPsi { resolveClass(name)?.let { JpaEntityResolver().resolve(it) } }
        }
        return tables[name]
    }

    companion object {
        private val ROOT = Regex("(?i)\\b(?:FROM|UPDATE|INTO|DELETE(?:\\s+FROM)?)\\s+([a-zA-Z_][a-zA-Z0-9_.$]*)")

        /** 忽略字面量和括号内 FROM，避免 SELECT 标量子查询抢占根表。 */
        fun rootEntity(sql: String): String? {
            val masked = JpaSqlLiteralMasking.mask(sql).first
            var depth = 0
            var offset = 0
            for (match in ROOT.findAll(masked)) {
                while (offset < match.range.first) {
                    when (masked[offset++]) { '(' -> depth++; ')' -> depth-- }
                }
                if (depth == 0) return match.groupValues[1]
            }
            return null
        }
    }
}

/**
 * 子句关键字表（沿用 [JpaJoinRewriter.normalizeAlias] 的口径，补 JOIN 族与集合运算词）。
 * O-71 T-01 起由 [JpaAliasPropertyMapper] 共用（裸词替换的排除表之一），故提为文件顶层。
 */
private val CLAUSE_KEYWORDS = setOf(
    "ON", "WHERE", "SET", "GROUP", "ORDER", "HAVING", "INNER", "LEFT",
    "RIGHT", "OUTER", "CROSS", "FULL", "JOIN", "FETCH", "AS", "UNION", "WITH",
    "BY", "AND", "OR", "NOT", "IN", "IS", "NULL", "EXISTS", "BETWEEN", "LIKE",
    "SELECT", "FROM", "DELETE", "UPDATE", "INSERT", "INTO", "VALUES", "NEW",
    "DISTINCT", "LIMIT", "OFFSET", "USING", "ASC", "DESC", "CASE", "WHEN", "THEN", "ELSE", "END",
)

/**
 * HQL/JPQL 别名作用域解析产物（O-57）。
 *
 * @param byAlias 别名（以及无别名表的实体名/表名自身）→ 该实体的表信息；属性映射按限定符分派到它。
 * @param knownQualifiers FROM/JOIN 中出现过的全部限定符（别名、实体名、表名；**含解析失败者**）。
 *        与 [byAlias] 分开是因为两者语义不同：前者回答「这个限定符属于哪张表」，
 *        后者回答「这个限定符在 FROM/JOIN 里出现过吗」——ON 未映射诊断只该对「根本没出现过」
 *        的限定符报警，对「出现过但实体解析不出」保持沉默（那属于已知降级，不是异常）。
 * @param entityTableNames 实体标识符 → 真实表名；供 [JpaFromEntityMapper] 复用，免二次 PSI 检索。
 */
internal data class JpaAliasScope(
    val byAlias: Map<String, TableInfo>,
    val knownQualifiers: Set<String>,
    val entityTableNames: Map<String, String>,
) {
    companion object {
        /** 空作用域：属性映射回落「只看根实体列清单」的历史行为。 */
        val EMPTY = JpaAliasScope(emptyMap(), emptySet(), emptyMap())
    }
}

/**
 * HQL/JPQL 别名作用域解析器（O-57）。
 *
 * 缺陷背景：属性→列名映射原先只遍历**根实体**列清单、且替换正则不带别名限定，于是
 * `join WmsPickTicketDetail detail on detail.pickTicketId = ...` 里的 `detail.pickTicketId`
 * 永远映射不到列名（真列是 `pick_ticket_id`），预览 SQL 复制出去执行报 `Unknown column`。
 * 副表实体的 [TableInfo] 其实已经算过一次（[JpaFromEntityMapper] 里的
 * `JpaEntityResolver().resolve`），只是当时只取了 `tableName` 就把列清单丢掉了。
 *
 * 本解析器把「FROM/JOIN 别名 → 该实体的 [TableInfo]」补上，供两条转换链按作用域分派属性映射。
 *
 * **扫描时点（关键）**：必须在实体名 → 表名替换**之前**——那时 FROM/JOIN 后面的实体标识符还在
 * SQL 里，一旦替换成小写表名，PSI 短名检索就不再命中。同时必须在 [JpaJoinRewriter] 的 JOIN
 * 改写**之后**，否则改写新增的副表别名（`JOIN u.rel r` → `INNER JOIN t_target r ON ...`）看不到。
 *
 * **解析失败静默跳过**：不抛异常、不按命名策略盲猜列名（猜错列名比保留属性名更误导，沿用
 * [JpaFromEntityMapper] 的既有原则）。已声明但未解析出的别名保持属性原文，不借根表列填补；
 * 未声明限定符及嵌入路径仍保留历史根表回退。明确绑定的根表别名正常映射。
 * [JpaAliasPropertyMapper] 的正则仍须**容忍限定符与点号之间的空白**
 * （`u .code` 这类合法写法改前会被旧实现替换，正则若要求紧邻就会漏掉，见该对象 KDoc）。
 *
 * **已接受的边界**：关联路径 JOIN（`JOIN 根别名.关联属性 副表别名`）的副表别名解析不出来——
 * 扫描时点 JOIN 后面跟的是根别名（`pickTicket`）而非实体名；即便在 [JpaJoinRewriter] 改写之后
 * 扫描，看到的也只是改写产物里的表名（如 `t_department`），按短名检索不到实体类，同样走
 * 未映射兜底（= 与改前行为一致）。`where d.createdTime = ...` 这类关联路径副表属性仍不映射。
 * 也不支持逗号分隔的 FROM 列表（`from A a, B b`）——HQL 已废弃形态，同样走兜底。
 */
internal object JpaAliasTableResolver {

    /**
     * `FROM|JOIN <标识符> [AS] <别名>`。
     *
     * 别名捕获组缺省（两个组都空）表示该表无从属别名；此时下一个词可能是子句关键字
     * （`FROM UserEntity WHERE ...`），由 [CLAUSE_KEYWORDS] 排除，不能当别名。
     */
    private val TABLE_REFERENCE = Regex(
        "(?i:\\b(?:FROM|JOIN)\\s+)([a-zA-Z_][a-zA-Z0-9_.\$]*)" +
            "(?:\\s+AS\\s+([a-zA-Z_][a-zA-Z0-9_]*)|\\s+([a-zA-Z_][a-zA-Z0-9_]*))?",
    )

    /**
     * 生产入口：按实体短名经 PSI 检索实体类后解析表信息。
     *
     * @param sql 已过参数替换与 JOIN 改写、**尚未做实体名→表名替换**的 SQL
     * @param table 根实体表信息（调用方已解析，直接绑定其别名，免重复 PSI）
     * @param project 项目；null 时全部副表别名解析不出（回落既有根实体列清单行为）
     */
    fun resolveAliases(
        sql: String,
        table: TableInfo,
        project: Project?,
        context: PsiElement? = null,
    ): JpaAliasScope {
        return resolveAliases(sql, table) { entityName ->
            recoverJpaPsi {
                project?.let { EntityAliasSweep.resolve(it, entityName, context).psiClass }
                    ?.let { JpaEntityResolver().resolve(it) }
            }
        }
    }

    /**
     * 解析函数注入形态（调用方已有 PsiClass / 解析缓存时直入，免按名检索；
     * LightPlatform 测试环境无需依赖索引）。
     *
     * [resolveEntity] 收到 FROM/JOIN 中的实体标识符（简单名），返回其表信息；
     * 返回 null 表示无元数据（该别名走未映射兜底）。
     */
    internal fun resolveAliases(
        sql: String,
        table: TableInfo,
        resolveEntity: (String) -> TableInfo?,
    ): JpaAliasScope {
        val byAlias = LinkedHashMap<String, TableInfo>()
        val knownQualifiers = LinkedHashSet<String>()
        val entityTableNames = LinkedHashMap<String, String>()
        // 同一实体名只解析一次（PSI 检索 + 注解遍历都不便宜）
        val memo = HashMap<String, TableInfo?>()

        if (table.entityClassName.isNotBlank() && JpaQueryEntityLookup.rootEntity(sql) == table.entityClassName) {
            memo[table.entityClassName] = table
            registerTableName(entityTableNames, table.entityClassName, table.tableName)
        }

        for (match in TABLE_REFERENCE.findAll(sql)) {
            val rawEntity = match.groupValues[1]
            if (rawEntity.uppercase() in CLAUSE_KEYWORDS) continue
            val entity = rawEntity.substringAfterLast('.')
            val captured = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val alias = captured.takeIf { it.isNotEmpty() && it.uppercase() !in CLAUSE_KEYWORDS }

            // FQN 不可截成简单名后命中根表缓存：两个模块可声明同名实体。
            if (!memo.containsKey(rawEntity)) {
                memo[rawEntity] = recoverJpaPsi { resolveEntity(rawEntity) }
            }
            val info = memo[rawEntity]
            if (info != null) registerTableName(entityTableNames, rawEntity, info.tableName)

            // 登记限定符（含解析失败者）：ON 诊断据此区分「没出现过」与「出现过但解析不出」
            alias?.let { knownQualifiers.add(it) }
            knownQualifiers.add(entity)
            if (info != null && info.tableName.isNotBlank()) knownQualifiers.add(info.tableName)

            if (info == null) continue
            // 有别名按别名绑定；无别名（或别名与实体名混用）用实体名/表名自身作键，
            // 覆盖 `from Entity where Entity.col` 与 `from 表名 where 表名.col` 两种限定写法
            alias?.let { putIfAbsent(byAlias, it, info) }
            putIfAbsent(byAlias, entity, info)
            if (info.tableName.isNotBlank()) putIfAbsent(byAlias, info.tableName, info)
        }

        return JpaAliasScope(byAlias, knownQualifiers, entityTableNames)
    }

    private fun registerTableName(target: MutableMap<String, String>, entity: String, tableName: String) {
        if (tableName.isNotBlank() && tableName != "unknown_table") target[entity] = tableName
    }

    /** 先到先得：同一键（别名/实体名/表名撞名）以最先登记的绑定为准，避免后出现的覆盖已用绑定。 */
    private fun putIfAbsent(target: MutableMap<String, TableInfo>, key: String, value: TableInfo) {
        if (!target.containsKey(key)) target[key] = value
    }
}

/**
 * 别名作用域属性映射（O-57 T-01/T-02/T-06 的替换核心；O-71 T-01/T-02 扩展）。
 *
 * 放在本文件而非两个转换器内，是因为 [HqlConverter] 与 [JpqlConverter] 必须逐字一致——
 * 逻辑只有一份才谈得上「两条链行为一致」。
 *
 * 点号路径单遍扫描：每个 `限定符.中间段.属性` 命中时按限定符分派到已解析别名；未命中
 * （裸限定符 / 别名解析不出实体）回落根实体列清单。O-71 起该回落不再「原样透传」：
 * 限定符位是嵌入段（`updateInfo.beDelete`）时剥离，裸属性（无点号的独立词）按根实体列
 * 清单精确映射——**契约变更**：O-57 以「只收窄不丢失」为由保守排除裸属性（正则要求带
 * 点号），实测证明该排除使无别名/漏写前缀的 HQL 的 WHERE/SELECT 投影永不映射，产物必为
 * 无效 SQL，故推翻。O-57 的既有红线（不产生「原本会被替换、现在不替换」的回归）仍然成立：
 * 本次只新增替换面，点号路径的行为逐字未变。
 */
internal object JpaAliasPropertyMapper {

    /**
     * 单引号字符串字面量（本仓唯一一份口径，属性映射/ON 诊断/[JpqlConverter] 子查询检测共用）。
     *
     * 标准 `''` 转义天然切成相邻两段（`'it''s b.c'` → `'it'` + `'s b.c'`），
     * 内层路径同样落在字面量分支里被保护。
     */
    internal val STRING_LITERAL = Regex("'[^']*'")

    /** 属性路径分支的 pattern 文本（[PROPERTY_OR_LITERAL] 与诊断扫描共用的唯一来源）。 */
    private const val PROPERTY_PATTERN =
        "\\b([a-zA-Z_][a-zA-Z0-9_]*)(\\s*\\.\\s*)" +
            "((?:[a-zA-Z_][a-zA-Z0-9_]*\\s*\\.\\s*)*)([a-zA-Z_][a-zA-Z0-9_]*)\\b"

    /**
     * 「字符串字面量 | 限定符.属性」单遍交替。
     *
     * 字面量分支**在前且不带捕获组**，命中即原样返回——引号内的 `x.createdTime`
     * 永远不被当属性路径改写（`where z.remark like '% x .createdTime %'` 必须逐字不变）。
     * 需要「抹平→还原」两段式，也就不会改写字面量内容。
     *
     * 未闭合单引号（`where x = 'abc` 到语句末尾）时字面量分支匹配不上，扫描继续向后，
     * 其后的合法属性照常映射——避让字面量不得顺手吞掉正常映射。
     *
     * 捕获组（只属于属性分支）：1 限定符 | 2 空白+点号 | 3 中间段（每段自带其后的空白+点号）| 4 属性名。
     * 限定符与点号之间的空白允许且**原样保留**（`u .code` / `u\n.code` 是合法写法，
     * 不容忍就会从「旧实现会替换」变成「不替换」，即回归）；中间段同样允许并保留空白。
     * 中间段本身按**解析出的列**的 `embeddedParent` 决定去留（见 [isFlattenedEmbeddedPath]）：
     * `@Embedded` 展平列的路径要去掉嵌入段（`pickTicket.updateInfo.createdTime` → `pickTicket.created_time`），
     * 其余情况原样保留。
     */
    private val PROPERTY_OR_LITERAL = Regex("${STRING_LITERAL.pattern}|${PROPERTY_PATTERN}")

    /**
     * O-71 T-01：裸标识符分支（无点号路径的独立词）——「字面量 | 完整点号路径 | 裸词」交替。
     * 前两项不带捕获组，防止本轮重新处理限定属性、绕过上轮作用域判定。词后紧跟 `(` 的负前瞻排除
     * 函数调用（`count(1)` / `count (1)` 均覆盖）。
     */
    private val BARE_WORD_OR_LITERAL = Regex(
        "${STRING_LITERAL.pattern}|" +
            "\\b[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*\\.\\s*[a-zA-Z_][a-zA-Z0-9_]*)+\\b|" +
            "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()",
    )

    /**
     * T-01 裸词替换的补充排除表：布尔/常量字面量与 HQL 特有操作词（子句关键字见文件顶层的
     * [CLAUSE_KEYWORDS]；函数调用已由负前瞻排除）。`OF` 是给 `MEMBER OF` 降级注释里残留词的
     * 双保险——该注释在本映射之前生成，其内的英文词不参与裸词替换。
     */
    private val BARE_WORD_EXCLUDED =
        setOf("TRUE", "FALSE", "EMPTY", "MEMBER", "ESCAPE", "SOME", "ANY", "ALL", "OF")

    /**
     * `ON <条件>` 区域：到下一个子句关键字为止（跨行，`.*?` 配 DOT_MATCHES_ALL）。
     *
     * 整体 `(?i)`——大小写不敏感必须覆盖**前瞻里的子句关键字**，否则小写 HQL（常态）下
     * 区域不停在 `join`/`where`，会一路扫到语句末尾：既点名根本不在 ON 里的限定符，
     * 又会把后续子句里的点号字符串字面量误判成 `限定符.属性`。
     */
    private val ON_CLAUSE = Regex(
        "(?i)\\bON\\s(.*?)(?=\\s*\\b(?:WHERE|GROUP\\s+BY|ORDER\\s+BY|HAVING|UNION|LIMIT|" +
            "LEFT|RIGHT|INNER|OUTER|CROSS|FULL|JOIN|ON)\\b|$)",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * 把 `限定符.属性` 映射为 `限定符.列名`，再对裸属性做同口径映射（O-71 T-01）。
     *
     * @param sql 已完成实体名→表名映射的 SQL（限定符是别名，不受实体名替换影响）
     * @param rootTable 根实体表信息（未映射别名的兜底来源，也是裸词映射的唯一来源）
     * @param scope 别名作用域（见 [JpaAliasTableResolver]）
     */
    fun mapProperties(sql: String, rootTable: TableInfo, scope: JpaAliasScope): String {
        val mapped = PROPERTY_OR_LITERAL.replace(sql) { match ->
            // 字面量分支（无捕获组）→ 原样返回：引号内的路径不是属性引用
            if (match.groupValues[1].isEmpty()) return@replace match.value
            val qualifier = match.groupValues[1]
            val dot = match.groupValues[2]
            val middle = match.groupValues[3]
            val field = match.groupValues[4]
            val owner = scope.byAlias[qualifier]
            // 已声明却解析失败（含歧义）的副表不能借用根表列；未知限定符/嵌入路径仍沿用旧契约。
            if (owner == null && qualifier in scope.knownQualifiers) return@replace match.value
            val column = (owner ?: rootTable).columnByField(field)
            if (column == null || column.fieldName == column.columnName) {
                match.value
            } else if (owner == null && column.embeddedParent == qualifier) {
                // O-71 T-02：无别名 HQL 下嵌入段占限定符位（`updateInfo.beDelete`）→ 剥离限定符
                // 产出裸列名（嵌入对象没有自己的表，`updateInfo.BE_DELETE` 在 SQL 语义下被当成
                // 「库.表」限定）。判据仍是列自身的 embeddedParent；带别名时 qualifier 是真别名
                // （owner 命中），走下方 isFlattenedEmbeddedPath 分支，行为不变
                column.columnName
            } else if (isFlattenedEmbeddedPath(middle, column)) {
                // @Embedded 展平（计划 §六 D2 已实施）：嵌入对象没有自己的表，其字段直接落在宿主表的列上，
                // 故路径里的嵌入段必须去掉——`pickTicket.updateInfo.createdTime` 的正确产物是
                // `pickTicket.created_time`；保留 `updateInfo.` 会产出无效 SQL（SQL 语义下
                // `pickTicket.updateInfo` 被当成「库.表」）。判据取自**列自身的 embeddedParent**
                // （展平时写入），而不是「中间段不是本表字段就丢掉」——后者会误删别的合法中间段。
                "$qualifier$dot${column.columnName}"
            } else {
                // 只换属性名，限定符/中间段及其间空白原样拼回；替换产物是列名（小写 snake），
                // 不再命中本规则，天然幂等
                "$qualifier$dot$middle${column.columnName}"
            }
        }
        return appendUnmappedOnHint(mapBareProperties(mapped, rootTable, scope), scope)
    }

    /**
     * O-71 T-01：裸属性（无点号路径的独立词）→ 列名映射。
     *
     * 覆盖两类形态：完全无别名的 HQL（`from WorkstationStatus where stationCode = ...`，用户报障
     * 场景），与有别名但个别属性漏写前缀的混合形态（`where z.createdTime > :t and beAutoZone = true`）。
     * 这两类产物此前原样透传，而 Hibernate 真实生成的列名是大写下划线——透传产物执行必报
     * `Unknown column`，故 O-57 的保守契约（裸属性不替换）被推翻（契约变更登记见类 KDoc）。
     *
     * 替换口径从严（误伤面压到最小）：
     *  - `columnByField` **精确命中**且 `fieldName != columnName` 才替换，不猜命名规则；
     *  - 子句关键字（[CLAUSE_KEYWORDS]）、布尔/常量词（[BARE_WORD_EXCLUDED]）不替换，
     *    词后紧跟 `(` 的（函数调用，`count(1)`）由正则负前瞻排除；
     *  - `scope.knownQualifiers`（FROM/JOIN 出现过的别名、实体名、表名）不替换——防「别名/实体名
     *    恰好撞字段名」的误伤；
     *  - 单引号字面量内不替换（与 [PROPERTY_OR_LITERAL] 同款「字面量分支在前」手法；
     *    未闭合单引号其后裸词照常映射，与点号路径口径一致）；
     *  - 多别名歧义场景沿用根实体列清单（与既有「未命中回落根实体」原则一致，不猜副表）。
     *
     * 替换产物是列名，再次扫描不命中（fieldName 是驼峰、列名是 snake/大写），天然幂等。
     */
    private fun mapBareProperties(sql: String, rootTable: TableInfo, scope: JpaAliasScope): String =
        BARE_WORD_OR_LITERAL.replace(sql) { match ->
            val word = match.groupValues[1]
            // 字面量分支（无捕获组）→ 原样返回：引号内的词不是属性引用
            if (word.isEmpty()) return@replace match.value
            val upper = word.uppercase()
            if (upper in CLAUSE_KEYWORDS || upper in BARE_WORD_EXCLUDED) return@replace match.value
            if (word in scope.knownQualifiers) return@replace match.value
            val column = rootTable.columnByField(word)
            if (column == null || column.fieldName == column.columnName) match.value else column.columnName
        }

    /**
     * 该匹配是否为「嵌入对象路径」——即 [column] 是 [middle] 首个嵌入段的展平列。
     *
     * 只认**首个**段：嵌套 `@Embedded` 展平后列名已按 `@AttributeOverride` 链算准，
     * 整条中间段都该丢；而 `@Embedded` 的字段名记录的是最外层那个字段（见
     * [ColumnInfo.embeddedParent] 的写入点），故与首段比较即可。
     *
     * 中间段为空（普通两段路径）恒为 false；中间段首段与列的 `embeddedParent` 不等时为 false
     * ——即**不丢**中间段，保持既有行为。
     */
    private fun isFlattenedEmbeddedPath(middle: String, column: ColumnInfo): Boolean {
        val parent = column.embeddedParent ?: return false
        if (middle.isBlank()) return false
        return middle.trim().substringBefore('.') == parent
    }

    /**
     * 收集文本里的属性路径限定符。
     *
     * 复用 [PROPERTY_OR_LITERAL] 的同一份匹配（过滤掉字面量分支），
     * 因此诊断与属性映射对「引号内的 `a.b` 不是限定符路径」是同一口径，不存在第二套实现。
     */
    private fun propertyQualifiers(text: String): List<String> =
        PROPERTY_OR_LITERAL.findAll(text)
            .filter { it.groupValues[1].isNotEmpty() } // 空 = 命中的是字面量分支
            .map { it.groupValues[1] }
            .toList()

    /**
     * T-06：ON 条件里出现「在 FROM/JOIN 中根本没出现过的限定符」时附一次性注释。
     *
     * 只提升可发现性，不改写属性（§八：不猜列名）；注释附在语句末尾，不改变 SQL 语义。
     * 判定口径刻意保守——「出现过但实体解析不出」不报警（那是已知降级，报警会淹没真实信号）。
     *
     * 只对 SELECT/FROM 语句追加：UPDATE/DELETE 的 HQL 不支持 JOIN ON，本提示对它们无意义，
     * 而尾注一旦落到 UPDATE 上，会让 [JpaUpdateParser] 的 WHERE 解析因尾注释降级为 Raw
     * （条件树渲染退化）。判定在语句类型分派之前完成，故必须在此处显式短路。
     *
     * **不变量（改本文案前必读）**：注释文本内不得出现 `FROM`/`JOIN` 后紧跟空白 + 标识符。
     * 下游 [SelectClauseExpander] 的 joinPattern 与 [JpaFromEntityMapper] 的 ENTITY_REFERENCE
     * 都是纯文本正则、不识别注释，会把它当成 FROM/JOIN 子句或实体名去解析。
     * 现文案写的是 `FROM/JOIN，`——`FROM`/`JOIN` 后紧跟的是 `/` 与全角逗号（非 `\s`），
     * 两个扫描器都不会命中，满足该不变量。
     */
    private fun appendUnmappedOnHint(sql: String, scope: JpaAliasScope): String {
        val head = sql.trimStart().uppercase()
        if (!head.startsWith("SELECT") && !head.startsWith("FROM")) return sql
        val unknown = ON_CLAUSE.findAll(sql)
            .flatMap { propertyQualifiers(it.groupValues[1]) }
            .filter { it !in scope.knownQualifiers }
            .distinct()
            .toList()
        if (unknown.isEmpty()) return sql
        return "$sql /* ON 条件中的 ${unknown.joinToString("、")} 未出现在 FROM/JOIN，其属性未映射，请核对实体注解 */"
    }
}
