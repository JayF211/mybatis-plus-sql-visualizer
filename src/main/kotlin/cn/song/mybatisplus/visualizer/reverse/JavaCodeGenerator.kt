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
        /** 调用类是否具备 MPJ 能力（继承链含 MPJBaseService/JoinService/MPJBaseMapper/JoinMapper）——
         * 由调用方解析（SqlToJavaConverter.invocationHasMpj）；false 时 VO 查询回落实体查询
         * （严禁生成不存在的 selectJoinList 族方法）。 */
        val mpj: Boolean = false,
        /** T-M44：调用类继承链方法名集合（SqlToJavaConverter.invocationMethods 后台 ReadAction 反查）——
         * 方法选择先查集合（真实存在的 API），缺失时沿 join 回退链（如 selectPage → selectJoinPage）；
         * 空集 = 特殊值 this/baseMapper 或类不可解析 → 保守用普通方法。 */
        val methods: Set<String> = emptySet(),
    )

    /** T-M44：按调用类真实方法集选择调用方法——优先链中第一个真实存在的方法；
     *  方法集为空（特殊值/类不可解析）→ 保守返回首选（普通方法）。 */
    private fun InvocationTarget.prefer(primary: String, vararg fallbacks: String): String {
        if (methods.isEmpty()) return primary
        return (listOf(primary) + fallbacks).firstOrNull { it in methods } ?: primary
    }

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
        // VO 查询（selectJoinList 族）参数类型为 MPJBaseJoin——须调用类具备 MPJ 能力（继承链实证）
        // 且 wrapper 强制 MPJ 形态；调用类无 MPJ 时 VO 回落实体查询（selectInvocation 内注释提示）
        val voQuery = invocation != null && invocation.mpj &&
            invocation.voClass != null &&
            invocation.voClass != (entity?.className ?: entityClassName(result.tableName))
        // T-M47：选中 VO 且为真实 VO 查询时，selectAs 第二参用 VO 属性方法引用（Vo::getXxx）——
        // 对齐正向 selectAs(列, Vo::getXxx)；否则字符串别名
        val selectAsVoClass = if (voQuery) invocation?.voClass else null
        // 多表 JOIN 依赖 MPJ wrapper 库类——调用类已选定且无 MPJ 能力时无法生成可编译代码，明确报错
        if (isJoin && invocation != null && !invocation.mpj) {
            throw SqlReverseException(
                0,
                SqlReverseParser.KEY_UNSUPPORTED,
                "多表 JOIN 需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 ${invocation.callerClass} 无 MPJ 能力",
            )
        }
        // T-M37：多表强制 MPJ 形态；字符串 raw join（副实体缺失/ON 多条件）仅 StringJoin（MPJQueryWrapper）
        // 合法——MPJLambdaWrapper 无 leftJoin(String)（1.5.6 实证），不能 lambda 化时一律回落 MPJ_QUERY
        // 保证编译（含用户显式选择 MPJ_LAMBDA 的场景）
        val effectiveKind = when {
            isJoin && !result.joins.all { canLambdaJoin(it, entity) } -> GeneratedWrapper.MPJ_QUERY
            wrapperKind in JOIN_WRAPPERS -> wrapperKind
            isJoin && entity != null -> GeneratedWrapper.MPJ_LAMBDA
            isJoin -> GeneratedWrapper.MPJ_QUERY
            voQuery -> if (entity != null) GeneratedWrapper.MPJ_LAMBDA else GeneratedWrapper.MPJ_QUERY
            else -> wrapperKind
        }
        // T-M48：MPJQueryWrapper（字符串联接）形态下列必须是字符串，不可用 lambda/selectAs(String, SFunction)
        val rawMode = effectiveKind == GeneratedWrapper.MPJ_QUERY
        val chain = buildString {
            // T-M42：serialVersionUID 系列列过滤后渲染（全部被过滤 → 空列表 → 自然回落 selectAll/不生成
            // select 调用，MP 全列语义）
            val selectColumns = result.selectColumns?.filterNot { isIgnoredColumn(it.expr) }
            val conditions = filterConditions(result.conditions)
            if (isJoin) {
                // 多表：主表 selectAll（列清单未显式指定时）——MPJ 语义
                if (selectColumns == null || selectColumns.isEmpty()) {
                    append("\n    .selectAll(")
                        .append(entity?.className ?: entityClassName(result.tableName))
                        .append(".class)")
                } else {
                    selectColumns.forEach {
                        append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode))
                    }
                }
                renderJoins(result, entity).forEach { append("\n    .").append(it) }
                conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
                renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
                // 分页调用形态（invocation 存在）由 page 参数承载，不再生成 last("LIMIT...")
                if (result.limit != null && invocation == null) {
                    append("\n    .last(\"").append(javaEscape(renderLimit(result.limit))).append("\")")
                }
            } else {
                if (selectColumns != null && selectColumns.isNotEmpty()) {
                    if (selectColumns.any { it.alias != null }) {
                        // 含 AS 别名的列无法并入单个 .select(...)，逐列渲染 .select/.selectAs（T-M46）
                        selectColumns.forEach {
                            append("\n    .").append(renderSelectCall(it, entity, selectAsVoClass, rawMode))
                        }
                    } else {
                        append("\n    .select(")
                            .append(selectColumns.joinToString(", ") { renderSelectRef(it, entity) })
                            .append(")")
                    }
                }
                conditions.forEach { append("\n    .").append(renderCondition(it, entity)) }
                renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
                // 分页调用形态（invocation 存在）由 page 参数承载，不再生成 last("LIMIT...")
                if (result.limit != null && invocation == null) {
                    append("\n    .last(\"").append(javaEscape(renderLimit(result.limit))).append("\")")
                }
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
     * JOIN 链渲染（T-M37）：ON 等值列对（忽略副表逻辑删除条件）且主副实体均可映射 → MPJ 三参
     * `leftJoin(副实体.class, 副::getX, 主::getY)`；否则字符串 raw join
     * `leftJoin("address t1 ON <原文>")`（仅 StringJoin 形态——MPJQueryWrapper 合法，
     * MPJLambdaWrapper 无 leftJoin(String)，1.5.6 实证；形态分发见 generateSelect.canLambdaJoin）。
     */
    private fun renderJoins(result: SqlReverseResult, entity: EntityTarget?): List<String> =
        result.joins.map { join ->
            if (canLambdaJoin(join, entity)) {
                val sub = entity!!.subEntities[join.alias]!!
                val single = joinKeyComparison(join, sub)
                    ?: return@map "${joinMethod(join.type)}(\"${javaEscape(joinString(join))}\")"
                val leftField = sub.columnToField[single.left.columnName]!!
                val rightField = entity.columnToField[single.right.columnName]!!
                "${joinMethod(join.type)}(${sub.className}.class, " +
                    "${sub.className}::get${leftField.replaceFirstChar { it.uppercase() }}, " +
                    "${entity.className}::get${rightField.replaceFirstChar { it.uppercase() }})"
            } else {
                "${joinMethod(join.type)}(\"${javaEscape(joinString(join))}\")"
            }
        }

    /** 字符串形态 join 的完整参数文本（`表 [别名] ON 原文`）；空白归一为单空格、换行折叠，防 Java 字面量非法。 */
    private fun joinString(join: ReverseJoin): String {
        val onText = (join.rawOn ?: join.onConditions.joinToString(" AND ") { renderRawCondition(it) })
            .replace(Regex("\\s+"), " ").trim()
        val tablePart = join.tableName + (join.alias?.let { " $it" } ?: "")
        return "$tablePart ON $onText"
    }

    /** join 是否可三参 lambda 化（ON 等值列对 + 主副实体列均可映射，忽略副表逻辑删除条件）——否则须字符串形态。 */
    private fun canLambdaJoin(join: ReverseJoin, entity: EntityTarget?): Boolean {
        val sub = join.alias?.let { entity?.subEntities?.get(it) } ?: return false
        val single = joinKeyComparison(join, sub) ?: return false
        val leftField = sub.columnToField[single.left.columnName]
        val rightField = entity?.columnToField?.get(single.right.columnName)
        return leftField != null && rightField != null
    }

    /**
     * 从 ON 条件里提取联表键等值列对（ColumnComparison）——滤除「副表逻辑删除类条件」
     * （如 MPJ 自动追加的 `bikeX.deleted = 0`，三参 lambda 形态会由 MPJ 自动补，不能当作多条件拦截）。
     * 取唯一键对；多键对/主表附加条件 → null（回落字符串形态）。
     */
    private fun joinKeyComparison(join: ReverseJoin, sub: EntityTarget): Condition.ColumnComparison? {
        val keyConditions = join.onConditions.filterNot { isSubLogicalDelete(it, sub, join.alias) }
        return keyConditions.singleOrNull() as? Condition.ColumnComparison
    }

    /** 常见逻辑删除列名（MPJ 联表自动追加的 `deleted = 0` 等 ON 过滤）——归一化（去下划线）后比对；
     * 仅命中这些名字才当作「副表逻辑删除条件」容忍丢弃——`type = 1`、`status = 1` 等真过滤条件不误吞（回落字符串形态）。 */
    private val LOGICAL_DELETE_COLUMNS =
        setOf("deleted", "is_deleted", "is_delete", "del_flag", "deleted_flag", "del", "is_del")
            .map { it.replace("_", "") }
            .toSet()

    /** 副表逻辑删除类条件：逻辑删除列 `= 字面量 0/1` 且该列为副实体真实列（deleted 等）。 */
    private fun isSubLogicalDelete(condition: Condition, sub: EntityTarget, joinAlias: String?): Boolean =
        condition is Condition.Simple &&
            condition.op == "=" &&
            condition.column.tableAlias == joinAlias &&
            sub.columnToField.containsKey(condition.column.columnName) &&
            condition.column.columnName.lowercase().replace("_", "") in LOGICAL_DELETE_COLUMNS &&
            (condition.value as? SqlValue.Literal)?.raw in setOf("0", "1")

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

    /**
     * 调用对象变量名（T-M42）：特殊调用值直出（`this`=service 内调用、`baseMapper`=ServiceImpl 的
     * baseMapper 字段——均非类名，保持原样），普通类名驼峰首字母小写（`BannerService` → `bannerService`）
     * ——生成变量调用形式，复制即用。
     */
    private fun callerVar(invocation: InvocationTarget): String = when (invocation.callerClass) {
        "this", "baseMapper" -> invocation.callerClass
        else -> invocation.callerClass.replaceFirstChar { it.lowercaseChar() }
    }

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
            // T-M42：serialVersionUID 系列 SET 列与条件列跳过（不生成该列 set/setSql）
            result.sets.filterNot { isIgnoredColumn(it.column) }.forEach { set ->
                append("\n    .")
                if (set.rawSql != null) {
                    append("setSql(\"").append(javaEscape(set.rawSql)).append("\")")
                } else {
                    append("set(").append(renderColumnRef(set.column, entity)).append(", ")
                        .append(set.value?.let { javaValue(it) } ?: "null")
                        .append(")")
                }
            }
            filterConditions(result.conditions).forEach { append("\n    .").append(renderCondition(it, entity)) }
            renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
            result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
        }
        val wrapperClass = wrapperClassFor(UPDATE_WRAPPER_CLASSES, entity, wrapperKind)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain, invocation) {
            // T-M44：update 缺失时回落 JoinMapper/JoinService 的 updateJoin(null, wrapper)（实体参数 null，同形）
            val m = invocation!!.prefer("update", "updateJoin")
            when {
                m == "updateJoin" -> "${callerVar(invocation)}.updateJoin(null, wrapper);"
                isMapper(invocation) -> "${callerVar(invocation)}.update(null, wrapper);"
                else -> "${callerVar(invocation)}.update(wrapper);"
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
            // DeleteJoinWrapper 是 MPJ 库类——调用类已选定且无 MPJ 能力时无法编译，明确报错
            if (invocation != null && !invocation.mpj) {
                throw SqlReverseException(
                    0,
                    SqlReverseParser.KEY_UNSUPPORTED,
                    "多表删除需要 MyBatis-Plus-Join（MPJ）依赖，但调用类 ${invocation.callerClass} 无 MPJ 能力",
                )
            }
            // 1.5.6 实证：DeleteJoinWrapper 无 (Class, Class...) 多类构造；删除目标经 delete(String...)（表名/别名）
            // 声明；join 链仅 lambda 形态（QueryJoin）——ON 非单等值列对或列映射缺失时字符串 join 编译不过，
            // 明确报错优于生成不可编译代码
            if (!result.joins.all { canLambdaJoin(it, entity) }) {
                throw SqlReverseException(
                    0,
                    SqlReverseParser.KEY_UNSUPPORTED,
                    "多表删除的 JOIN 无法映射为 lambda join（需单等值 ON 且主副实体字段齐全），无法生成 DeleteJoinWrapper",
                )
            }
            val chain = buildString {
                if (result.deleteTargets.isNotEmpty()) {
                    append("\n    .delete(")
                        .append(result.deleteTargets.joinToString(", ") { "\"$it\"" })
                        .append(")")
                }
                renderJoins(result, entity).forEach { append("\n    .").append(it) }
                filterConditions(result.conditions).forEach { append("\n    .").append(renderCondition(it, entity)) }
            }
            return appendWrapperAndInvocation(
                sb,
                "DeleteJoinWrapper",
                entity.className,
                wrapperKind,
                chain,
                invocation,
                invocationLine = { "${callerVar(invocation!!)}.deleteJoin(wrapper);" },
                headOverride = { "new DeleteJoinWrapper<>(${entity.className}.class)" },
            )
        }
        // 单表删除：现状不变
        val chain = buildString {
            filterConditions(result.conditions).forEach { append("\n    .").append(renderCondition(it, entity)) }
            renderOrderBy(result, entity).forEach { append("\n    .").append(it) }
            result.limit?.let { append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
        }
        val wrapperClass = wrapperClassFor(QUERY_WRAPPER_CLASSES, entity, wrapperKind)
        val entityName = entity?.className ?: entityClassName(result.tableName)
        return appendWrapperAndInvocation(sb, wrapperClass, entityName, wrapperKind, chain, invocation) {
            // T-M44：delete/remove 缺失时回落 deleteJoin(wrapper)（JoinMapper/JoinService 同形单参）
            val m = invocation!!
            if (isMapper(m)) {
                "${callerVar(m)}.${m.prefer("delete", "deleteJoin")}(wrapper);"
            } else {
                "${callerVar(m)}.${m.prefer("remove", "deleteJoin")}(wrapper);"
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
        // T-M42：serialVersionUID 系列插入列跳过（setter 链不生成该列；保留原列索引对齐行值——
        // 过滤后第 i 个保留列取值于原行第 colIndex 位；全部被过滤 → 生成空实体 new X() 保语句合法）
        val insertPairs = result.insertColumns.mapIndexedNotNull { index, column ->
            if (isIgnoredColumn(column)) null else index to column
        }

        // 单行实体 setter 链（列→字段：实体映射优先，缺失按列名驼峰推断）
        fun setterChain(row: List<SqlValue>): String {
            if (insertPairs.isEmpty()) return "new $entityName()"
            return buildString {
                insertPairs.forEachIndexed { i, (colIndex, column) ->
                    val field = entity?.columnToField?.get(column)
                        ?: column.split('_').joinToString("") { seg -> seg.replaceFirstChar { it.uppercase() } }
                        .replaceFirstChar { it.lowercaseChar() }
                    val value = row.getOrNull(colIndex)
                    append(if (i == 0) "new $entityName()" else "\n    ")
                    append(".set").append(field.replaceFirstChar { it.uppercase() }).append("(")
                        .append(value?.let { javaValue(it) } ?: "null")
                        .append(")")
                }
            }
        }

        if (rows.size <= 1) {
            // 单行：现状形态不变
            val chain = setterChain(rows.firstOrNull() ?: emptyList())
            val invocationLine = invocation?.let {
                "$entityName $entityVar = $chain;\n\n" + if (isMapper(invocation)) {
                    "${callerVar(invocation)}.insert($entityVar);"
                } else {
                    "${callerVar(invocation)}.save($entityVar);"
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
                sb.append("${callerVar(invocation)}.insert($entityVar${i + 1});")
                if (i < rows.lastIndex) sb.append("\n")
            }
        } else {
            val vars = rows.indices.joinToString(", ") { "$entityVar${it + 1}" }
            sb.append("${callerVar(invocation)}.saveBatch(Arrays.asList($vars));")
        }
        return sb.toString()
    }

    /**
     * SELECT 调用行（分页/普通二选一；调用类 → 驼峰首字母小写实例变量，复制即用）。
     *
     * 方法名表全部经 MPJ 1.5.6 jar javap 实证（用户本地版本；`listVo/pageVo/selectVoPage/
     * selectVoList` 均不存在——旧 1.2.x 名称，1.5.6 已移除）：
     *  - 普通 → `list(wrapper)`（IService）/ `selectList(wrapper)`（BaseMapper）；
     *  - 分页（SQL 含 LIMIT）→ `page(new Page<>(c, s), wrapper)` / `selectPage(...)`（MP 原生）；
     *  - VO 查询（MPJ，VO 类名 ≠ 实体名）→ `selectJoinList(Vo.class, wrapper)`（JoinMapper/JoinService）；
     *  - 分页 + VO → Service `selectJoinListPage(page, Vo.class, wrapper)` / Mapper `selectJoinPage(page, Vo.class, wrapper)`
     *    （参数顺序：page 第一、VO class 第二、wrapper 第三——1.5.6 实证）。
     *
     * current/size 由 LIMIT 字面量推断（offset/size+1、count），占位符/推断不可靠时回落 1/10 并注释提示替换。
     */
    private fun selectInvocation(
        result: SqlReverseResult,
        entity: EntityTarget?,
        invocation: InvocationTarget,
    ): String {
        val entityName = entity?.className ?: entityClassName(result.tableName)
        val voRequested = invocation.voClass?.takeIf { it != entityName }
        // VO 查询依赖 MPJ 能力（调用类继承链实证）；无 MPJ → 回落实体查询并注释提示
        val vo = voRequested?.takeIf { invocation.mpj }
        val voUnsupported = voRequested != null && vo == null
        val generic = vo ?: entityName
        val paged = result.limit != null
        // T-M44：方法选择按调用类真实方法集（prefer 回退链）——部分 MPJ 依赖的 MPJBaseMapper
        // 无 BaseMapper 的 selectPage/selectList 等普通方法，缺失时回落 JoinMapper/JoinService 的
        // selectJoin 族（join 方法需 Class 参数，args 按方法形态构建）；特殊值/类不可解析 → 普通方法
        val method = if (isMapper(invocation)) {
            when {
                paged && vo != null -> "selectJoinPage"
                paged -> invocation.prefer("selectPage", "selectJoinPage")
                vo != null -> "selectJoinList"
                else -> invocation.prefer("selectList", "selectJoinList")
            }
        } else {
            when {
                paged && vo != null -> "selectJoinListPage"
                paged -> invocation.prefer("page", "selectJoinListPage")
                vo != null -> "selectJoinList"
                else -> invocation.prefer("list", "selectJoinList")
            }
        }
        // 参数顺序（1.5.6 实证）：普通 `(wrapper)`；分页 `(page, wrapper)`；
        // VO/join 方法 `(page?, Class, wrapper)`——回退到 selectJoin 族时 Class 参数用 VO 或实体
        val pageArg = if (paged) renderPageArg(result.limit!!) else ""
        val args = buildString {
            if (paged) append(pageArg).append(", ")
            if (vo != null || method.startsWith("selectJoin")) append("${vo ?: entityName}.class, ")
            append("wrapper")
        }
        val prefix = if (paged) "Page<$generic> page = " else "List<$generic> list = "
        val call = "$prefix${callerVar(invocation)}.$method($args);"
        // 说明注释独立成行（T-M45：行尾注释 → 行注释——整行语义清晰，复制剥离注释时行结构稳定）
        val comments = buildString {
            if (voUnsupported) append("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询\n")
            if (vo != null) append("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖\n")
            if (paged) append("// 分页 current/size 已占位，请替换为实际参数\n")
        }
        return comments + call
    }

    /** 分页参数：`LIMIT count` → `new Page<>(1, count)`；`LIMIT offset, count` → offset/count+1；
     * 占位符（`LIMIT ?, ?`）→ `new Page<>(1, 10)`（调用行注释提示替换）。 */
    private fun renderPageArg(limit: cn.song.mybatisplus.visualizer.model.LimitClause): String {
        val size = (limit.count as? SqlValue.Literal)?.raw ?: "10"
        val sizeNum = size.toLongOrNull()
        val current = (limit.offset as? SqlValue.Literal)?.raw?.toLongOrNull()?.let { offset ->
            if (sizeNum != null && sizeNum > 0) (offset / sizeNum + 1).coerceAtLeast(1).toString() else "1"
        } ?: "1"
        return "new Page<>($current, $size)"
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
        // T-M50：无调用类（仅选 VO）→ 不生成调用行，仅输出 wrapper 表达式
        val hasCaller = invocation?.callerClass?.isNotEmpty() == true
        if (invocation == null || !hasCaller) {
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
        if (hasPlaceholder(result)) {
            sb.append("// 注意：SQL 含参数占位符 ?，无法恢复实际参数，已生成 \"?\" 占位，请替换为实际参数\n")
        }
        result.warnings.forEach { sb.append("// 注意：$it\n") }
    }

    /** 结果中是否存在 `?` 占位符（WHERE/SET/INSERT/LIMIT 任一位置）。 */
    private fun hasPlaceholder(result: SqlReverseResult): Boolean {
        fun scan(value: SqlValue?): Boolean = value is SqlValue.Placeholder

        fun scanConditions(conditions: List<Condition>): Boolean = conditions.any { c ->
            when (c) {
                is Condition.Simple -> scan(c.value)
                is Condition.In -> c.values.any { scan(it) }
                is Condition.Between -> scan(c.lo) || scan(c.hi)
                is Condition.Like -> scan(c.pattern)
                is Condition.Group -> scanConditions(c.children)
                else -> false
            }
        }

        return scanConditions(result.conditions) ||
            result.sets.any { scan(it.value) } ||
            result.insertValues.any { row -> row.any { scan(it) } } ||
            scan(result.limit?.offset) || scan(result.limit?.count)
    }

    /** 调用类是否为 Mapper（T-M42：`baseMapper` 恒为 Mapper 形态、`this` 恒为 Service 形态；
     * 否则类名以 Mapper 结尾判 Mapper，按 Service 处理并注释提示）。 */
    private fun isMapper(invocation: InvocationTarget): Boolean = when (invocation.callerClass) {
        "baseMapper" -> true
        "this" -> false
        else -> invocation.callerClass.endsWith("Mapper")
    }

    // ------------------------------------------------------------------
    // 列渲染（T-M36.2：lambda 模式列→属性）
    // ------------------------------------------------------------------

    /**
     * T-M42：serialVersionUID 系列列名（归一化：小写+去下划线）默认忽略——static final 序列化字段
     * 非业务列（无 getter/setter，MP 官方不映射），生成引用会编译失败；剥别名前缀与反引号后匹配，
     * 覆盖 serial_version_uid / serial_version_u_i_d / serialVersionUID / `serial_version_u_i_d` /
     * t.serial_version_u_i_d 等全部形态。
     */
    private fun isIgnoredColumn(expr: String): Boolean =
        expr.substringAfterLast('.').trim('`').lowercase().replace("_", "") == "serialversionuid"

    /**
     * T-M42：条件树过滤——命中 serialVersionUID 列的 Simple/In/Between/Like/Unary 条件跳过；
     * Group 递归过滤（子条件全被过滤的组整组跳过，避免生成空 nested/not lambda）。
     */
    private fun filterConditions(conditions: List<Condition>): List<Condition> =
        conditions.mapNotNull { condition ->
            when (condition) {
                is Condition.Group -> {
                    val children = filterConditions(condition.children)
                    if (children.isEmpty()) null else condition.copy(children = children)
                }
                is Condition.Simple -> condition.takeUnless { isIgnoredColumn(it.column.columnName) }
                is Condition.Unary -> condition.takeUnless { isIgnoredColumn(it.column.columnName) }
                is Condition.In -> condition.takeUnless { isIgnoredColumn(it.column.columnName) }
                is Condition.Between -> condition.takeUnless { isIgnoredColumn(it.column.columnName) }
                is Condition.Like -> condition.takeUnless { isIgnoredColumn(it.column.columnName) }
                else -> condition
            }
        }

    /**
     * 列归属解析（T-M37/T-M46）：返回 (实体类名, 字段名)；null = 未映射。
     * alias 非空时按表别名归属副实体（subEntities），否则主实体；主/对应副实体无该列时，
     * 若 alias 非某副实体键（主表别名或 null）则扫全部副实体——恰一个副实体有该列才归属
     * （如主表别名 `t.model` 实为副表 model 列）；同名歧义（多副实体都有）返回 null（回落字符串）。
     */
    private fun resolveColumn(columnName: String, entity: EntityTarget?, alias: String?): Pair<String, String>? {
        val target = if (alias != null) entity?.subEntities?.get(alias) ?: entity else entity
        val field = target?.columnToField?.get(columnName)
        if (field != null && target != null) return target.className to field
        if (alias == null || entity?.subEntities?.containsKey(alias) == false) {
            entity?.subEntities?.values?.filter { it.columnToField.containsKey(columnName) }?.singleOrNull()?.let {
                return it.className to it.columnToField[columnName]!!
            }
        }
        return null
    }

    /** 列引用渲染（T-M37 列归属）：lambda 模式且列有字段映射 → `Entity::getField`；
     * 未映射 → 字符串列名（多表带别名前缀 `"t1.city"`）。 */
    private fun renderColumnRef(columnName: String, entity: EntityTarget?, alias: String? = null): String {
        val resolved = resolveColumn(columnName, entity, alias)
        if (resolved != null) {
            return "${resolved.first}::get${resolved.second.replaceFirstChar { it.uppercase() }}"
        }
        val qualified = if (alias != null) "$alias.$columnName" else columnName
        return javaString(qualified)
    }

    /** SELECT 列列引用（T-M37 列归属）：原文拆分别名前缀后走列归属；反引号剥壳。 */
    private fun renderSelectRef(sel: SelectColumn, entity: EntityTarget?): String {
        val expr = sel.expr
        val parts = expr.split('.')
        val (alias, column) = if (parts.size > 1) {
            parts.dropLast(1).joinToString(".").trim('`') to parts.last().trim('`')
        } else {
            null to expr.trim('`')
        }
        return renderColumnRef(column, entity, alias)
    }

    /** SELECT 列整条调用（T-M46/T-M47/T-M48）：
     * - 字符串联接形态（MPJQueryWrapper，rawMode）：列必须是字符串——
     *   非别名 `.select("t.id")`，带 AS 别名 `.select("t.name AS productName")`
     *   （MPJQueryWrapper 无 selectAs(String, SFunction) 重载，不可用 lambda 别名）。
     * - lambda 联接形态（MPJLambdaWrapper）：
     *   非别名 `.select(Entity::getId)`，带 AS 别名且选中 VO → `.selectAs(Sub::getX, VO::getY)`
     *   对齐正向 selectAs 的 VO 字段；未选 VO → `.selectAs(Sub::getX, "alias")`。 */
    private fun renderSelectCall(sel: SelectColumn, entity: EntityTarget?, voClass: String?, rawMode: Boolean): String {
        if (rawMode) {
            // MPJQueryWrapper 原生形态：列取原始表达式字符串（保留反引号等 SQL 形态）
            val col = sel.expr
            return if (sel.alias != null) "select(${javaString("$col AS ${sel.alias}")})" else "select(${javaString(col)})"
        }
        val ref = renderSelectRef(sel, entity)
        return if (sel.alias != null) {
            val aliasRef = if (voClass != null) {
                "${voClass}::get${sel.alias.replaceFirstChar { it.uppercase() }}"
            } else {
                javaString(sel.alias)
            }
            "selectAs($ref, $aliasRef)"
        } else {
            "select($ref)"
        }
    }

    /** 未映射列（lambda 模式）：结果中引用但实体/副实体归属缺失的列名，按出现顺序去重。
     * 判定与渲染共用 [resolveColumn]（主表 + 对应副表 + 唯一副实体兜底）——避免「渲染已归属副实体却仍误报」。 */
    private fun unmappedColumns(result: SqlReverseResult, entity: EntityTarget): List<String> {
        val used = mutableListOf<String>()
        result.selectColumns?.let { used.addAll(it.map { c -> c.expr }) }
        fun collect(condition: Condition) {
            when (condition) {
                is Condition.Simple -> used.add(condition.column.qualifiedName)
                is Condition.Unary -> used.add(condition.column.qualifiedName)
                is Condition.In -> used.add(condition.column.qualifiedName)
                is Condition.Between -> used.add(condition.column.qualifiedName)
                is Condition.Like -> used.add(condition.column.qualifiedName)
                is Condition.Group -> condition.children.forEach { collect(it) }
                else -> Unit
            }
        }
        result.conditions.forEach { collect(it) }
        result.orderBy.forEach { used.add(it.column.qualifiedName) }
        // T-M42：被过滤的 serialVersionUID 系列列不计入「未映射回落」提示（实际是跳过而非回落字符串列名）；
        // 列名匹配前归一化（剥反引号/别名前缀）——selectColumns 原文可能带反引号（如 `` `signal` ``），
        // 不剥会导致映射成功却误报「未找到对应字段」（用户 T-M43 复现实证）
        return used.distinct()
            .filter { !isIgnoredColumn(it) }
            .filter { resolveColumn(normalizedColumnName(it), entity, columnAliasOf(it)) == null }
    }

    /** 列名归一化：剥别名前缀与反引号（`t1.city` → `city`、`` `signal` `` → `signal`）。 */
    private fun normalizedColumnName(expr: String): String = expr.substringAfterLast('.').trim('`')

    /** 引用串的表别名前缀（`t1.city` → `t1`；`` `t`.`x` `` → `t`；`city` → null）。 */
    private fun columnAliasOf(expr: String): String? {
        val parts = expr.split('.')
        return if (parts.size > 1) parts.dropLast(1).joinToString(".").trim('`') else null
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
        // T-M42：serialVersionUID 系列排序列跳过（含反引号形态；全部被过滤 → 不生成 orderBy 调用）
        val orderBy = result.orderBy.filterNot { isIgnoredColumn(it.column.columnName) }
        if (orderBy.isEmpty()) return emptyList()
        val calls = mutableListOf<String>()
        var group = mutableListOf<Pair<String, String?>>()
        var groupAsc = orderBy[0].asc
        orderBy.forEach { item ->
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
        val offset = limit.offset?.let { (it as? SqlValue.Literal)?.raw ?: "?" }
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
