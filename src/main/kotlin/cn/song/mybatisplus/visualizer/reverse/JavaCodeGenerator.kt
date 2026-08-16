package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.SqlValue

/**
 * MyBatis-Plus 代码生成器（SQL→代码 反向链路，P0 + T-M36.2 lambda 模式）。
 *
 * 把 [SqlReverseResult] 渲染为 Wrapper 链式代码片段。两种形态：
 *  - 字符串列名 `QueryWrapper`（默认，无实体上下文）：`new QueryWrapper<User>()` + `.eq("name", ...)`；
 *  - Lambda `LambdaQueryWrapper`（T-M36.2，[EntityTarget] 提供列→字段映射）：
 *    `new LambdaQueryWrapper<User>()` + `.eq(User::getName, ...)`——映射不到的列回落字符串列名。
 *
 * 生成约定：
 *  - 条件方法映射：= → eq、!= / <> → ne、> → gt、>= → ge、< → lt、<= → le、
 *    IS NULL → isNull、IS NOT NULL → isNotNull、IN/NOT IN → in/notIn、
 *    BETWEEN → between/notBetween、LIKE 通配符位置 → like/notLike + Left/Right 变体；
 *  - [Condition.Raw]（LIKE 降级/复杂表达式透传）→ apply("...")；
 *  - 组（T-M36.1）→ nested/not lambda，组内 OR 用 `.or()` 分隔，嵌套递归 w2/w3；
 *  - ORDER BY 相邻同向合并为一个 orderByAsc/orderByDesc 调用；
 *  - LIMIT → last("LIMIT ...")（MP 无分页参数上下文时的一致降级形态）。
 *
 * 本类为纯函数，不依赖 PSI / IDE。
 */
object JavaCodeGenerator {

    /**
     * Lambda 模式实体上下文（T-M36.2）：实体类名 + 列名 → 字段名映射
     * （由 [cn.song.mybatisplus.visualizer.psi.EntityClassFinder] + EntityResolver 解析）。
     * T-M37：subEntities 为联表副实体（键 = SQL 表别名 → 实体），多表列归属用。
     */
    data class EntityTarget(
        val className: String,
        val columnToField: Map<String, String>,
        val subEntities: Map<String, EntityTarget> = emptyMap(),
    )

    /**
     * 调用上下文（T-M36.7，面板输入，非必选）：调用类（Service 或 Mapper，类名以
     * `Mapper` 结尾判 Mapper，否则按 Service）+ 可选返回 VO 类名（仅 SELECT 生效，
     * 生成 MPJ 的 selectVoList/listVo——需引入 MPJ 依赖，注释已标注）。
     */
    data class InvocationTarget(
        val callerClass: String,
        val voClass: String? = null,
    )

    /** 生成完整代码片段（字符串列名 QueryWrapper 形态）。 */
    fun generate(result: SqlReverseResult): String = generate(result, null)

    /**
     * 生成完整代码片段（T-M36.7：增删改查全支持）。
     *
     * @param entity      非 null → lambda 列引用形态；null → 字符串列名形态
     * @param wrapperKind 实体可用时的 Wrapper 类型（T-M36.3）：LAMBDA → LambdaQueryWrapper、
     *                    MPJ_LAMBDA → MPJLambdaWrapper；entity 为 null 时恒 QueryWrapper
     * @param invocation  T-M36.7：调用上下文（Service/Mapper 类名 + 可选返回 VO 类名）；
     *                    null → 生成纯 Wrapper/实体表达式，非 null → 生成完整调用语句
     */
    fun generate(
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper = GeneratedWrapper.LAMBDA,
        invocation: InvocationTarget? = null,
    ): String = when (result.kind) {
        ReverseStatementKind.SELECT -> generateSelect(result, entity, wrapperKind, invocation)
        ReverseStatementKind.UPDATE -> generateUpdate(result, entity, wrapperKind, invocation)
        ReverseStatementKind.DELETE -> generateDelete(result, entity, wrapperKind, invocation)
        ReverseStatementKind.INSERT -> generateInsert(result, entity, wrapperKind, invocation)
    }

    // ------------------------------------------------------------------
    // 语句渲染（T-M36.7：增删改查）
    // ------------------------------------------------------------------

    /** SELECT：现有 wrapper 表达式；T-M37 多表 JOIN → MPJ 形态（selectAll + join 链）。 */
    private fun generateSelect(
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper,
        invocation: InvocationTarget?,
    ): String {
        val sb = StringBuilder()
        appendHeader(sb, result, entity, wrapperKind, invocation)
        val isJoin = result.joins.isNotEmpty()
        // T-M37：多表强制 MPJ 形态（converter 已分发；此处按 joins 防御兜底）
        val effectiveKind = if (isJoin && wrapperKind !in JOIN_WRAPPERS) {
            if (entity != null) GeneratedWrapper.MPJ_LAMBDA else GeneratedWrapper.MPJ_QUERY
        } else {
            wrapperKind
        }
        val chain = buildString {
            if (isJoin) {
                // 多表：主表 selectAll（列清单未显式指定时）——MPJ 语义
                if (result.selectColumns == null || result.selectColumns.isEmpty()) {
                    append("\n    .selectAll(")
                        .append(entity?.className ?: entityClassName(result.tableName))
                        .append(".class)")
                } else {
                    result.selectColumns.forEach {
                        append("\n    .select(").append(renderSelectColumn(it, entity)).append(")")
                    }
                }
                renderJoins(result, entity).forEach { append("\n    .").append(it) }
                result.conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
                renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
                result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
            } else {
                if (result.selectColumns != null && result.selectColumns.isNotEmpty()) {
                    append("\n    .select(")
                        .append(result.selectColumns.joinToString(", ") { renderSelectColumn(it, entity) })
                        .append(")")
                }
                result.conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
                renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
                result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
            }
        }
        val wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, effectiveKind)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, effectiveKind, chain, invocation) {
            selectInvocation(result, entity, invocation!!)
        }
    }

    // ------------------------------------------------------------------
    // 多表 JOIN 渲染（T-M37）
    // ------------------------------------------------------------------

    /** JOIN 方法名（MPJ）：INNER → innerJoin / LEFT → leftJoin / RIGHT → rightJoin / FULL → fullJoin。 */
    private fun joinMethod(type: cn.song.mybatisplus.visualizer.model.JoinType): String = when (type) {
        cn.song.mybatisplus.visualizer.model.JoinType.INNER -> "innerJoin"
        cn.song.mybatisplus.visualizer.model.JoinType.LEFT -> "leftJoin"
        cn.song.mybatisplus.visualizer.model.JoinType.RIGHT -> "rightJoin"
        cn.song.mybatisplus.visualizer.model.JoinType.FULL -> "fullJoin"
    }

    /**
     * JOIN 链渲染（T-M37）：ON 单等值列对且主副实体均可映射 → MPJ 三参
     * `leftJoin(副实体.class, 副::getX, 主::getY)`；否则字符串 raw join
     * `leftJoin("address t1 ON <原文>")`（MPJ leftJoin(String) 形态）。
     */
    private fun renderJoins(result: SqlReverseResult, entity: EntityTarget?): List<String> =
        result.joins.map { join ->
            val single = join.onConditions.singleOrNull() as? Condition.ColumnComparison
            val sub = join.alias?.let { entity?.subEntities?.get(it) }
            if (single != null && sub != null) {
                val leftField = sub.columnToField[single.left.columnName]
                val rightField = entity?.columnToField?.get(single.right.columnName)
                if (leftField != null && rightField != null) {
                    return@map "${joinMethod(join.type)}(${sub.className}.class, " +
                        "${sub.className}::get${leftField.replaceFirstChar { it.uppercase() }}, " +
                        "${entity?.className}::get${rightField.replaceFirstChar { it.uppercase() }})"
                }
            }
            val onText = join.rawOn ?: join.onConditions.joinToString(" AND ") { renderRawCondition(it) }
            val tablePart = join.tableName + (join.alias?.let { " $it" } ?: "")
            "${joinMethod(join.type)}(\"$tablePart ON $onText\")"
        }

    /** 条件 → SQL 文本（raw join 的 ON 原文重建；仅 ColumnComparison/Simple 等结构化形态）。 */
    private fun renderRawCondition(condition: Condition): String = when (condition) {
        is Condition.ColumnComparison ->
            "${condition.left.qualifiedName} = ${condition.right.qualifiedName}"
        is Condition.Simple ->
            "${condition.column.qualifiedName} ${condition.op} ${(condition.value as? SqlValue.Literal)?.raw ?: "?"}"
        is Condition.Raw -> condition.sqlText
        else -> condition.toString()
    }

    /** MPJ 形态集合（多表强制分发判定）。 */
    private val JOIN_WRAPPERS = setOf(GeneratedWrapper.MPJ_LAMBDA, GeneratedWrapper.MPJ_QUERY)

    /** UPDATE：UpdateWrapper 链（set/setSql）+ 条件；带调用类 → Service.update / Mapper.update(null, wrapper)。 */
    private fun generateUpdate(
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper,
        invocation: InvocationTarget?,
    ): String {
        val sb = StringBuilder()
        appendHeader(sb, result, entity, wrapperKind, invocation)
        if (result.conditions.isEmpty()) {
            sb.append("// ⚠ 无 WHERE 条件，将更新全表数据！\n")
        }
        val chain = buildString {
            result.sets.forEach { set ->
                append("\n    .")
                if (set.rawSql != null) {
                    append("setSql(\"").append(javaEscape(set.rawSql)).append("\")")
                } else {
                    append("set(").append(renderColumnRef(set.column, entity)).append(", ")
                        .append(set.value?.let { javaValue(it) } ?: "null")
                        .append(")")
                }
            }
            result.conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
            renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
            result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
        }
        val wrapperClass = wrapperClassFor(UPDATE_WRAPPER_CLASSES, entity, wrapperKind)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain, invocation) {
            if (isMapper(invocation!!)) {
                "${invocation.callerClass}.update(null, wrapper);"
            } else {
                "${invocation.callerClass}.update(wrapper);"
            }
        }
    }

    /**
     * DELETE：单表（deleteTargets 为空）→ 条件 wrapper + Service.remove / Mapper.delete（现状不变）；
     * 多表（deleteTargets 非空）→ MPJ DeleteJoinWrapper 形态（对齐正向链路 DELETE_JOIN）：
     * `new DeleteJoinWrapper<>(主实体.class, 副实体.class, ...)` + join 链 + 条件链；
     * 调用类存在 → `xxxMapper.deleteJoin(wrapper)` / `xxxService.deleteJoin(wrapper)`。
     */
    private fun generateDelete(
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper,
        invocation: InvocationTarget?,
    ): String {
        val sb = StringBuilder()
        appendHeader(sb, result, entity, wrapperKind, invocation)
        if (result.conditions.isEmpty()) {
            sb.append("// ⚠ 无 WHERE 条件，将删除全表数据！\n")
        }
        val multiTable = result.deleteTargets.isNotEmpty()
        if (multiTable) {
            if (entity == null) {
                // DeleteJoinWrapper 构造需要实体类（无字符串形态），无实体上下文无法生成
                throw SqlReverseException(0, SqlReverseParser.KEY_UNSUPPORTED, "多表删除需要实体类上下文")
            }
            // 副实体类：按 join 别名在 subEntities 查找；缺失回落主实体类名并注释提醒
            val subClasses = result.joins.map { join ->
                val sub = join.alias?.let { entity.subEntities[it] }
                if (sub != null) {
                    sub.className
                } else {
                    sb.append("// 注意：联表 ${join.tableName} 未找到对应实体，DeleteJoinWrapper 副类回落 ${entity.className}\n")
                    entity.className
                }
            }
            val subPart = if (subClasses.isEmpty()) "" else ", " + subClasses.joinToString(", ") { "$it.class" }
            val chain = buildString {
                renderJoins(result, entity).forEach { append("\n    .").append(it) }
                result.conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
            }
            return appendWrapperAndInvocation(
                sb,
                "DeleteJoinWrapper",
                entity.className,
                wrapperKind,
                chain,
                invocation,
                invocationLine = { "${invocation!!.callerClass}.deleteJoin(wrapper);" },
                headOverride = { "new DeleteJoinWrapper<>(${entity.className}.class$subPart)" },
            )
        }
        // 单表删除：现状不变
        val chain = buildString {
            result.conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
            renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
            result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
        }
        val wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, wrapperKind)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain, invocation) {
            if (isMapper(invocation!!)) {
                "${invocation.callerClass}.delete(wrapper);"
            } else {
                "${invocation.callerClass}.remove(wrapper);"
            }
        }
    }

    /**
     * INSERT：实体 setter 链。
     *  - 单行：`new User().setName("a").setAge(1);`；带调用类 → `User user = ...;` + Service.save / Mapper.insert；
     *  - 多行：无调用类 → 每行一个实体 setter 语句，逐行换行输出；
     *    Service 调用类 → `User user1 = ...; User user2 = ...;` + saveBatch(Arrays.asList(user1, user2))；
     *    Mapper 调用类 → 逐条 insert(user1); insert(user2);。
     */
    private fun generateInsert(
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper,
        invocation: InvocationTarget?,
    ): String {
        val sb = StringBuilder()
        appendHeader(sb, result, entity, wrapperKind, invocation)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        val entityVar = entityName.replaceFirstChar { it.lowercaseChar() }
        val rows = result.insertValues

        // 单行实体 setter 链（列→字段：实体映射优先，缺失按列名驼峰推断）
        fun setterChain(row: List<SqlValue>): String = buildString {
            result.insertColumns.forEachIndexed { i, column ->
                val field = entity?.columnToField?.get(column)
                    ?: column.split('_').joinToString("") { seg -> seg.replaceFirstChar { it.uppercase() } }
                    .replaceFirstChar { it.lowercaseChar() }
                val value = row.getOrNull(i)
                append(if (i == 0) "new $entityName()" else "\n    ")
                append(".set").append(field.replaceFirstChar { it.uppercase() }).append("(")
                    .append(value?.let { javaValue(it) } ?: "null")
                    .append(")")
            }
        }

        if (rows.size <= 1) {
            // 单行：现状形态不变
            val chain = setterChain(rows.firstOrNull() ?: emptyList())
            val invocationLine = invocation?.let {
                "$entityName $entityVar = $chain;\n\n" + if (isMapper(invocation)) {
                    "${invocation.callerClass}.insert($entityVar);"
                } else {
                    "${invocation.callerClass}.save($entityVar);"
                }
            }
            return if (invocationLine != null) {
                sb.append(invocationLine)
                sb.toString()
            } else {
                sb.append(chain).append(";")
                sb.toString()
            }
        }
        if (invocation == null) {
            // 多行无调用类：每行一个实体 setter 语句，逐行换行输出
            sb.append(rows.joinToString("\n") { setterChain(it) + ";" })
            return sb.toString()
        }
        // 多行 + 调用类：变量 user1/user2... + 批量（Service）/ 逐条（Mapper）调用
        rows.forEachIndexed { i, row ->
            sb.append("$entityName $entityVar${i + 1} = ").append(setterChain(row)).append(";\n")
        }
        sb.append("\n")
        if (isMapper(invocation)) {
            rows.indices.forEach { i ->
                sb.append("${invocation.callerClass}.insert($entityVar${i + 1});")
                if (i < rows.lastIndex) sb.append("\n")
            }
        } else {
            val vars = rows.indices.joinToString(", ") { "$entityVar${it + 1}" }
            sb.append("${invocation.callerClass}.saveBatch(Arrays.asList($vars));")
        }
        return sb.toString()
    }

    /** SELECT 调用行：Service → list/listVo；Mapper → selectList/selectVoList（VO 需 MPJ）。 */
    private fun selectInvocation(
        result: SqlReverseResult,
        entity: EntityTarget?,
        invocation: InvocationTarget,
    ): String {
        val entityName = entity?.className ?: entityClassName(result.tableName)
        val vo = invocation.voClass
        val generic = vo ?: entityName
        val method = if (isMapper(invocation)) {
            if (vo != null) "selectVoList" else "selectList"
        } else {
            if (vo != null) "listVo" else "list"
        }
        val arg = if (vo != null) ", $vo.class" else ""
        return "List<$generic> list = ${invocation.callerClass}.$method(wrapper$arg);" +
            if (vo != null) "  // VO 查询需 MyBatis-Plus-Join" else ""
    }

    /** wrapper 变量化 + 调用行；无调用 → 纯表达式。headOverride 可定制构造表达式（如 DeleteJoinWrapper 多类构造）。 */
    private fun appendWrapperAndInvocation(
        sb: StringBuilder,
        wrapperClass: String,
        entityName: String,
        wrapperKind: GeneratedWrapper,
        chain: String,
        invocation: InvocationTarget?,
        headOverride: (() -> String)? = null,
        invocationLine: () -> String,
    ): String {
        val head = headOverride?.invoke() ?: wrapperHead(wrapperClass, entityName, wrapperKind)
        if (invocation == null) {
            sb.append(head).append(chain).append(";")
            return sb.toString()
        }
        sb.append("$wrapperClass<$entityName> wrapper = $head")
            .append(chain)
            .append(";\n\n")
            .append(invocationLine())
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 包装形态公共（T-M36.6 Wrappers 工厂 / T-M36.7 UPDATE/DELETE 类映射）
    // ------------------------------------------------------------------

    /** QUERY 族 wrapper 类名（SELECT 条件/DELETE 条件）。 */
    private val QUERY_WRAPPER_CLASSES = WrapperClassMap(
        plain = "QueryWrapper",
        lambda = "LambdaQueryWrapper",
        mpj = "MPJLambdaWrapper",
        mpjPlain = "QueryWrapper",
    )

    /** UPDATE 族 wrapper 类名（MPJ 无单表更新形态 → 回落 UpdateWrapper）。 */
    private val UPDATE_WRAPPER_CLASSES = WrapperClassMap(
        plain = "UpdateWrapper",
        lambda = "LambdaUpdateWrapper",
        mpj = "UpdateWrapper",
        mpjPlain = "UpdateWrapper",
    )

    private data class WrapperClassMap(
        val plain: String,
        val lambda: String,
        val mpj: String,
        val mpjPlain: String,
    )

    /** 形态 → wrapper 变量类名（WRAPPERS 工厂返回 plain/lambda 类；MPJ 无实体回落 plain）。 */
    private fun wrapperClassFor(map: WrapperClassMap, entity: EntityTarget?, wrapperKind: GeneratedWrapper): String =
        when (wrapperKind) {
            GeneratedWrapper.QUERY -> map.plain
            GeneratedWrapper.WRAPPERS_QUERY -> map.plain
            GeneratedWrapper.LAMBDA -> if (entity != null) map.lambda else map.plain
            GeneratedWrapper.WRAPPERS_LAMBDA -> if (entity != null) map.lambda else map.plain
            GeneratedWrapper.MPJ_LAMBDA -> if (entity != null) map.mpj else map.mpjPlain
            GeneratedWrapper.MPJ_QUERY -> map.mpjPlain
        }

    /** wrapper 构造表达式：`new X<E>()` 或 `Wrappers.<E>factory()`（T-M36.6）。 */
    private fun wrapperHead(wrapperClass: String, entityName: String, wrapperKind: GeneratedWrapper): String =
        when (wrapperKind) {
            GeneratedWrapper.WRAPPERS_QUERY -> "Wrappers.<$entityName>query()"
            GeneratedWrapper.WRAPPERS_LAMBDA -> "Wrappers.<$entityName>lambdaQuery()"
            GeneratedWrapper.MPJ_QUERY -> "new MPJQueryWrapper<>($entityName.class)"
            else -> "new $wrapperClass<$entityName>()"
        }

    /** 注释头（语句类型 + 形态 + 实体来源；T-M36.7 按 kind 标注）。 */
    private fun appendHeader(
        sb: StringBuilder,
        result: SqlReverseResult,
        entity: EntityTarget?,
        wrapperKind: GeneratedWrapper,
        invocation: InvocationTarget?,
    ) {
        val kindLabel = when (result.kind) {
            ReverseStatementKind.SELECT -> "QueryWrapper"
            ReverseStatementKind.UPDATE -> "UpdateWrapper"
            ReverseStatementKind.DELETE -> "DeleteWrapper"
            ReverseStatementKind.INSERT -> "Entity"
        }
        val formLabel = when {
            invocation != null -> "（含 ${invocation.callerClass} 调用" + (invocation.voClass?.let { "，返回 $it" } ?: "") + "）"
            wrapperKind == GeneratedWrapper.WRAPPERS_QUERY || wrapperKind == GeneratedWrapper.WRAPPERS_LAMBDA ->
                "（Wrappers 工厂形态）"
            wrapperKind == GeneratedWrapper.MPJ_LAMBDA || wrapperKind == GeneratedWrapper.MPJ_QUERY -> "（MPJ 形态）"
            else -> ""
        }
        sb.append("// SQL → MyBatis-Plus $kindLabel 反向生成（MyBatis-Plus SQL Visualizer）$formLabel\n")
        if (entity == null) {
            val entityName = entityClassName(result.tableName)
            sb.append("// 实体类名 $entityName 由表名 ${result.tableName} 推断，请按实际类名调整\n")
        } else {
            sb.append("// 实体类 ${entity.className} 由表名 ${result.tableName} 匹配\n")
            val unmapped = unmappedColumns(result, entity)
            if (unmapped.isNotEmpty()) {
                sb.append("// 注意：以下列未在实体中找到对应字段，已回落字符串列名：${unmapped.joinToString(", ")}\n")
            }
        }
        if (wrapperKind == GeneratedWrapper.MPJ_LAMBDA && entity != null) {
            sb.append("// 需引入 MyBatis-Plus-Join（MPJ）依赖\n")
        }
        result.warnings.forEach { sb.append("// 注意：$it\n") }
    }

    /** 调用类是否为 Mapper（类名以 Mapper 结尾；否则按 Service 处理，注释提示）。 */
    private fun isMapper(invocation: InvocationTarget): Boolean =
        invocation.callerClass.endsWith("Mapper")

    // ------------------------------------------------------------------
    // 列渲染（T-M36.2：lambda 模式列→属性）
    // ------------------------------------------------------------------

    /**
     * 列引用渲染（T-M37 列归属）：lambda 模式且列有字段映射 → `Entity::getField`——
     * alias 非空时按表别名归属副实体（subEntities），否则主实体；
     * 未映射 → 字符串列名（多表带别名前缀 `"t1.city"`）。
     */
    private fun renderColumnRef(columnName: String, entity: EntityTarget?, alias: String? = null): String {
        val target = if (alias != null) entity?.subEntities?.get(alias) ?: entity else entity
        val field = target?.columnToField?.get(columnName)
        if (field != null && target != null) {
            return "${target.className}::get${field.replaceFirstChar { it.uppercase() }}"
        }
        val qualified = if (alias != null) "$alias.$columnName" else columnName
        return javaString(qualified)
    }

    /** SELECT 列表达式渲染（T-M37）：原文拆分别名前缀后走列归属；反引号剥壳。 */
    private fun renderSelectColumn(expr: String, entity: EntityTarget?): String {
        val parts = expr.split('.')
        if (parts.size > 1) {
            val alias = parts.dropLast(1).joinToString(".").trim('`')
            val column = parts.last().trim('`')
            return renderColumnRef(column, entity, alias)
        }
        return renderColumnRef(expr.trim('`'), entity, null)
    }

    /** 未映射列（lambda 模式）：结果中引用但实体映射缺失的列名，按出现顺序去重。 */
    private fun unmappedColumns(result: SqlReverseResult, entity: EntityTarget): List<String> {
        val used = mutableListOf<String>()
        result.selectColumns?.let { used.addAll(it) }
        fun collect(condition: Condition) {
            when (condition) {
                is Condition.Simple -> used.add(condition.column.columnName)
                is Condition.Unary -> used.add(condition.column.columnName)
                is Condition.In -> used.add(condition.column.columnName)
                is Condition.Between -> used.add(condition.column.columnName)
                is Condition.Like -> used.add(condition.column.columnName)
                is Condition.Group -> condition.children.forEach { collect(it) }
                else -> Unit
            }
        }
        result.conditions.forEach { collect(it) }
        result.orderBy.forEach { used.add(it.column.columnName) }
        return used.distinct().filter { entity.columnToField[it] == null }
    }

    // ------------------------------------------------------------------
    // 条件渲染
    // ------------------------------------------------------------------

    private fun renderCondition(condition: Condition, entity: EntityTarget?): String = when (condition) {
        is Condition.Simple ->
            "${opMethod(condition.op)}(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)}, ${javaValue(condition.value)})"

        is Condition.Unary -> when (condition.op) {
            "IS NULL" -> "isNull(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)})"
            else -> "isNotNull(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)})"
        }

        is Condition.In -> {
            val method = if (condition.negated) "notIn" else "in"
            "$method(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)}, ${condition.values.joinToString(", ") { javaValue(it) }})"
        }

        is Condition.Between -> {
            val method = if (condition.negated) "notBetween" else "between"
            "$method(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)}, ${javaValue(condition.lo)}, ${javaValue(condition.hi)})"
        }

        is Condition.Like -> {
            val method = when (condition.position) {
                LikePosition.ANYWHERE -> if (condition.negated) "notLike" else "like"
                LikePosition.LEFT -> if (condition.negated) "notLikeLeft" else "likeLeft"
                LikePosition.RIGHT -> if (condition.negated) "notLikeRight" else "likeRight"
                LikePosition.NONE -> if (condition.negated) "notLike" else "like"
            }
            "$method(${renderColumnRef(condition.column.columnName, entity, condition.column.tableAlias)}, ${javaValue(condition.pattern)})"
        }

        is Condition.Raw -> "apply(\"${javaEscape(condition.sqlText)}\")"

        is Condition.Group -> renderGroup(condition, entity)

        is Condition.ColumnComparison ->
            "apply(\"${javaEscape(condition.left.columnName)} = ${condition.right.columnName}\")"
    }

    // ------------------------------------------------------------------
    // 组渲染（T-M36.1）：括号组 / OR 分组 → nested / not lambda
    // ------------------------------------------------------------------

    /**
     * 组渲染（T-M36.1）：`(a = 1 OR b = 2)` → `nested(w -> w.eq("a", 1).or().eq("b", 2))`；
     * `NOT (...)` → `not(w -> ...)`；组内嵌套子组 → `.and(w2 -> ...)` / `.or(w2 -> ...)` 递归。
     * 组连接符 OR 时子条件间以无参 `.or()` 分隔（对齐 MP `or()` 分隔符语义）。
     */
    private fun renderGroup(group: Condition.Group, entity: EntityTarget?): String {
        val wrapper = if (group.negated) "not" else "nested"
        return "$wrapper(w -> w" + renderGroupChildren(group, 2, entity) + ")"
    }

    /** 组内子条件链（nextVarIndex：嵌套 lambda 变量名序号，w2/w3...）。 */
    private fun renderGroupChildren(group: Condition.Group, nextVarIndex: Int, entity: EntityTarget?): String {
        val sb = StringBuilder()
        group.children.forEachIndexed { i, child ->
            if (i > 0 && group.connector == Connector.OR) sb.append("\n        .or()")
            when (child) {
                is Condition.Group -> {
                    val method = if (child.connector == Connector.OR) "or" else "and"
                    val varName = "w$nextVarIndex"
                    sb.append("\n        .$method($varName -> $varName")
                        .append(renderGroupChildren(child, nextVarIndex + 1, entity))
                        .append(")")
                }
                else -> sb.append("\n        .").append(renderCondition(child, entity))
            }
        }
        return sb.toString()
    }

    /** 二元比较操作符 → 方法名。 */
    private fun opMethod(op: String): String = when (op) {
        "=" -> "eq"
        "!=", "<>" -> "ne"
        ">" -> "gt"
        ">=" -> "ge"
        "<" -> "lt"
        "<=" -> "le"
        else -> "apply"
    }

    /** 值渲染：字符串（引号字面量）→ Java 字符串；数字/布尔 → 原样。 */
    private fun javaValue(value: SqlValue): String = when (value) {
        is SqlValue.Placeholder -> "\"?\""
        is SqlValue.Literal -> {
            if (value.raw.startsWith("'") && value.raw.endsWith("'") && value.raw.length >= 2) {
                javaString(value.unquoted)
            } else {
                value.raw
            }
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY / LIMIT
    // ------------------------------------------------------------------

    /** ORDER BY 渲染：相邻同向合并（orderByAsc("a","b")），异向拆开。 */
    private fun renderOrderBy(result: SqlReverseResult, entity: EntityTarget?): List<String> {
        if (result.orderBy.isEmpty()) return emptyList()
        val calls = mutableListOf<String>()
        var group = mutableListOf<Pair<String, String?>>()
        var groupAsc = result.orderBy[0].asc
        result.orderBy.forEach { item ->
            if (item.asc != groupAsc && group.isNotEmpty()) {
                calls.add(orderByCall(groupAsc, group, entity))
                group = mutableListOf()
                groupAsc = item.asc
            }
            group.add(item.column.columnName to item.column.tableAlias)
        }
        if (group.isNotEmpty()) calls.add(orderByCall(groupAsc, group, entity))
        return calls
    }

    private fun orderByCall(asc: Boolean, columns: List<Pair<String, String?>>, entity: EntityTarget?): String {
        val method = if (asc) "orderByAsc" else "orderByDesc"
        return "$method(${columns.joinToString(", ") { (name, alias) -> renderColumnRef(name, entity, alias) }})"
    }

    /** LIMIT 渲染（MySQL 形态；与 SqlGenerator MySqlDialect.renderLimit 对齐）。 */
    private fun renderLimit(limit: cn.song.mybatisplus.visualizer.model.LimitClause): String {
        val offset = (limit.offset as? SqlValue.Literal)?.raw
        val count = (limit.count as? SqlValue.Literal)?.raw ?: "?"
        return if (offset != null) "LIMIT $offset, $count" else "LIMIT $count"
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** Java 字符串字面量（含引号与转义）。 */
    private fun javaString(value: String): String = "\"${javaEscape(value)}\""

    /** Java 字符串转义（引号/反斜杠/控制字符；单引号无需转义）。 */
    internal fun javaEscape(value: String): String = buildString(value.length + 4) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /** 表名 → 实体类名推断（下划线 → 驼峰首字母大写）：`car_device_info` → `CarDeviceInfo`。 */
    internal fun entityClassName(tableName: String): String =
        tableName.split('_').joinToString("") { seg ->
            seg.replaceFirstChar { it.uppercase() }
        }
}
