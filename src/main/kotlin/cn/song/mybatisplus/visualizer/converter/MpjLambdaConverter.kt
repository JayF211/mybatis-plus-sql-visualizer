package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UnionClause
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import cn.song.mybatisplus.visualizer.util.NamingStrategy

/**
 * MPJLambdaWrapper 转换器（F-11）：联表 SELECT（清单 P01~P20 的 MPJLambdaWrapper 形态）。
 *
 * 支持：
 *  - `selectAs(Entity::getX, VO::getY)` / `selectAs("alias.col", VO::getY)` → SELECT 列（T-M7-01）
 *  - `selectAll(Class)`、`distinct`、`selectMax/selectCount/selectFunc`（T-M7-02/03）
 *  - `selectCollection`（子查询 JOIN）、`selectAssociation`（T-M7-04）
 *  - `leftJoin/innerJoin/rightJoin`：三参 `(Class, 关联表字段, 主表字段)`、四参带别名、
 *    `on(col1, col2)`、lambda ON `on -> on.eq(...)`（T-M7-05）
 *  - 表别名构造 `new MPJLambdaWrapper<>(Class, "alias")` → 主表别名可配置（C4/P11）
 *  - `eq` 等条件 / `orderByAsc` 等结构 → 委托 [QueryWrapperParser]（主表别名前缀）
 *
 * 已知简化（R7 待 MPJ 官方输出对照）：
 *  - 关联表别名默认 = 实体类名首字母小写（四参显式别名优先）
 *  - selectCollection 的 ON 复用同子表 leftJoin 的 on 条件；嵌套 collection 外键按约定推断
 */
object MpjLambdaConverter : WrapperConverter {

    /** MPJ 主表别名默认值（表别名构造未指定时）。 */
    private const val MAIN_ALIAS_DEFAULT = "t"

    override val supportedTypes: Set<WrapperType> = setOf(WrapperType.MPJ_LAMBDA)

    override fun convert(chain: WrapperChain, table: TableInfo): SqlStatement {
        // 主表别名：构造别名 > setAlias("x") 方法（官方 API，M15 增补）> 默认 t
        val mainAlias = chain.constructorAlias
            ?: chain.calls.firstOrNull { it.methodName == SET_ALIAS }
                ?.args?.firstOrNull()?.asValue()?.let { (it as? SqlValue.Literal)?.unquoted }
            ?: MAIN_ALIAS_DEFAULT
        val selectAsCalls = chain.calls.filter { it.methodName == SELECT_AS || it.methodName == SELECT_AS_CLASS || it.methodName == SELECT_SUB }
        val joinCalls = chain.calls.filter { it.methodName in JOIN_METHODS }
        val collectionCalls = chain.calls.filter { it.methodName == SELECT_COLLECTION }
        val selectAllCalls = chain.calls.filter { it.methodName == SELECT_ALL }

        // 其余调用（eq/select/orderBy/distinct/selectMax 等）委托 Query 解析，列自动带主表别名。
        // selectAssociation 不产生额外 SQL（leftJoin 已体现关联，仅作结果映射标记），从解析链中剔除。
        val restCalls = chain.calls.filterNot {
            it.methodName == SELECT_AS || it.methodName == SELECT_AS_CLASS || it.methodName == SELECT_SUB
                || it.methodName in JOIN_METHODS || it.methodName in UNION_METHODS
                || it.methodName == SELECT_ALL || it.methodName == SELECT_COLLECTION
                || it.methodName == SELECT_ASSOCIATION || it.methodName == SET_ALIAS
        }
        val base = QueryWrapperParser(table, mainAlias)
            .parse(WrapperChain(chain.wrapperType, chain.entityClass, restCalls))

        // selectAll(Class[, prefix][, exclude...])：主表全列（带主表别名前缀）+ 聚合列（selectSum 等，委托 base）
        // + selectAs/selectAsClass/selectSub 列追加；exclude 排除字段（官方 API）、prefix 列前缀（官方 API）
        val selectAsColumns = selectAsCalls.flatMap { call ->
            when (call.methodName) {
                SELECT_AS_CLASS -> parseSelectAsClass(call, mainAlias)
                SELECT_SUB -> parseSelectSub(call)
                else -> listOfNotNull(parseSelectAs(call, mainAlias))
            }
        }
        val selectColumns = if (selectAllCalls.isNotEmpty()) {
            selectAllCalls.flatMap { parseSelectAll(it, mainAlias, table) } + base.selectColumns + selectAsColumns
        } else {
            base.selectColumns + selectAsColumns
        }
        // join + selectCollection 子查询 JOIN（复用同子表 leftJoin 的 ON）；按源码调用顺序合并。
        // T-M15-06：① 此前 join 全在 collection 前，与源码顺序不符；
        //           ② selectCollection 子查询已替代同子表普通 join（P06 两写并存），普通 join 不重复渲染
        val collectionChildClasses = collectionCalls.mapNotNull { call ->
            (call.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted
        }
        val joins = mutableListOf<JoinClause>()
        for (call in chain.calls) {
            when (call.methodName) {
                in JOIN_METHODS -> {
                    val join = parseJoin(call, mainAlias) ?: continue
                    if (collectionChildClasses.any { cls -> NamingStrategy.toSnakeCase(cls) == join.tableName }) continue
                    joins += join
                }
                SELECT_COLLECTION -> parseCollection(call, joinCalls, mainAlias)?.let { joins += it }
            }
        }

        // UNION / UNION ALL 子查询（官方 API，M15 增补）：渲染子查询后追加
        val unions = chain.calls
            .filter { it.methodName in UNION_METHODS }
            .mapNotNull { parseUnion(it) }

        return base.copy(
            tableAlias = mainAlias,
            selectColumns = selectColumns,
            joins = joins,
            unions = unions,
        )
    }

    /**
     * 解析 union / unionAll（官方 API）：`union(SubClass.class, w -> w.selectAll().eq(...))`
     * → `UNION (SELECT ... FROM sub_entity WHERE ...)`。子查询独立解析（不继承主表别名），compact 单行渲染。
     */
    private fun parseUnion(call: WrapperCall): UnionClause? {
        val subTable = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return null
        val nested = call.args.getOrNull(1) as? WrapperArg.NestedArg ?: return null
        val subStatement = QueryWrapperParser(subTable).parse(nested.chain)
        val keyword = if (call.methodName == "unionAll") "UNION ALL" else "UNION"
        return UnionClause(keyword, SqlGenerator.generate(subStatement, compact = true))
    }

    /**
     * 解析 selectSub（官方 API）：`selectSub(SubClass.class, w -> w.select(...).eq(...), alias)`
     * → `(SELECT ... FROM sub_entity WHERE ...) AS alias` 子查询列。
     */
    private fun parseSelectSub(call: WrapperCall): List<ColumnRef> {
        val subTable = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val nested = call.args.getOrNull(1) as? WrapperArg.NestedArg ?: return emptyList()
        val alias = when (val arg = call.args.getOrNull(2)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ColumnArg -> arg.column.columnName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        } ?: return emptyList()
        val subStatement = QueryWrapperParser(subTable).parse(nested.chain)
        val subSql = SqlGenerator.generate(subStatement, compact = true)
        return listOf(ColumnRef(columnName = subSql, rawExpr = "($subSql)", alias = alias))
    }

    /**
     * 解析 selectAs → 带别名的列（清单 P01/P02/P04/P11）。
     *
     * 支持三种实参形态：
     *  - `selectAs(Entity::getX, VO::getY)`：第二参方法引用 → 别名 = VO 属性名（getter 反解）
     *  - `selectAs(Entity::getX, "alias")`：第二参字符串 → 别名原样
     *  - `selectAs("alias.col", VO::getY)`：第一参字符串列（已含表别名限定）→ 不补主表前缀
     */
    private fun parseSelectAs(call: WrapperCall, mainAlias: String): ColumnRef? {
        val column = when (val arg = call.args.getOrNull(0)) {
            is WrapperArg.ColumnArg -> arg.column
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.let { ColumnRef(columnName = it.unquoted) }
            else -> null
        } ?: return null

        // 别名：第二参可为 VO::getY 方法引用（AliasArg 保留属性名）、lambda 列或字符串字面量
        val alias = when (val arg = call.args.getOrNull(1)) {
            is WrapperArg.AliasArg -> arg.propertyName
            is WrapperArg.ColumnArg -> arg.column.columnName
            is WrapperArg.ValueArg -> (arg.value as? SqlValue.Literal)?.unquoted
            else -> null
        } ?: return null

        // 第一参为已带表别名限定的字符串列（含 '.'）时原样渲染，否则补主表别名
        val tableAlias = if (call.args.getOrNull(0) is WrapperArg.ValueArg && column.columnName.contains('.')) {
            null
        } else {
            mainAlias
        }
        return ColumnRef(tableAlias = tableAlias, columnName = column.columnName, alias = alias)
    }

    /**
     * 解析 join → 联表子句（清单 P01/P05/P06/P11/P13/P17/P18，官方 API 全覆盖）。
     *
     * 支持六种 ON 形态：
     *  1. 三参 `leftJoin(Class, 关联表字段, 主表字段)` → ON alias.左 = mainAlias.右
     *  2. 四参带别名 `leftJoin(Class, "rel", 关联表字段, 主表字段)` → 别名显式指定
     *  3. `leftJoin(Class, on(col1, col2))` → ON mainAlias.col1 = alias.col2
     *  4. lambda ON `leftJoin(Class, on -> on.eq(...).eq(...))` → on 内多条件
     *  5. 字符串 SQL join `leftJoin("address t1 on t1.user_id = t.id"[, args])` → 原样透传（{i} 参数替换）
     *  6. 自定义关键字 `join("FULL JOIN", condition, "sql"[, args])` → 关键字 + 原样透传
     *
     * 关联表信息：优先 psi 层解析的 [WrapperArg.EntityRefArg]（F-13 注解），否则按类名 snake_case 兜底。
     */
    private fun parseJoin(call: WrapperCall, mainAlias: String): JoinClause? {
        val type = when (call.methodName) {
            "leftJoin" -> JoinType.LEFT
            "innerJoin" -> JoinType.INNER
            "rightJoin" -> JoinType.RIGHT
            "fullJoin" -> JoinType.FULL
            else -> JoinType.INNER // join(自定义关键字)
        }
        val args = call.args

        // 6. 自定义关键字 join：join("FULL JOIN", condition, "address t1 on ..."[, args])
        if (call.methodName == "join") {
            val keyword = (args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            val joinSql = (args.getOrNull(2).asValue() as? SqlValue.Literal)?.unquoted ?: return null
            return JoinClause(type, "", "", emptyList(), rawSql = renderJoinSql(joinSql, args.drop(3)), customKeyword = keyword)
        }

        // 5. 字符串 SQL join：leftJoin("address t1 on t1.user_id = t.id"[, args]) /
        //    leftJoin(condition, "sql"[, args])——首参为含 SQL 结构（空格/括号/{i} 占位）的字符串字面量；
        //    纯类名（CamelCase，无空格）走下方类名降级（converter 测试手工构造形态）
        val first = args.getOrNull(0)
        if (first is WrapperArg.ValueArg) {
            val literal = first.value as? SqlValue.Literal ?: return null
            val text = literal.unquoted
            if (!text.trimEnd().endsWith(".class") &&
                (text.contains(' ') || text.contains('(') || text.contains('{'))
            ) {
                val startIndex = if (args.size >= 2 && isFlagArgument(args[0])) 1 else 0
                val sql = (args.getOrNull(startIndex).asValue() as? SqlValue.Literal)?.unquoted ?: return null
                return JoinClause(type, "", "", emptyList(), rawSql = renderJoinSql(sql, args.drop(startIndex + 1)))
            }
        }

        val (tableInfo, className) = when (val arg = call.args.getOrNull(0)) {
            is WrapperArg.EntityRefArg -> arg.tableInfo to arg.tableInfo.entityClassName
            is WrapperArg.ValueArg -> {
                val cn = (arg.value as? SqlValue.Literal)?.raw?.removeSuffix(".class")?.trim() ?: return null
                null to cn
            }
            else -> return null
        }
        if (className.isEmpty()) return null

        val tableName = tableInfo?.tableName ?: NamingStrategy.toSnakeCase(className)
        var alias = className.replaceFirstChar { it.lowercaseChar() }
        val onConditions = mutableListOf<Condition>()

        val rest = call.args.drop(1)
        val firstRest = rest.getOrNull(0)
        when {
            // 4. lambda ON：leftJoin(Class, on -> on.eq(...).eq(...))
            firstRest is WrapperArg.NestedArg ->
                onConditions += parseOnLambda(firstRest.chain, alias, mainAlias)

            else -> {
                var index = 0
                // 2. 四参带别名：leftJoin(Class, "rel", 关联表字段, 主表字段)
                if (firstRest is WrapperArg.ValueArg) {
                    val aliasText = (firstRest.value as? SqlValue.Literal)?.unquoted
                    if (!aliasText.isNullOrEmpty()) {
                        alias = aliasText
                        index = 1
                    }
                }
                // 3. on(col1, col2)：left 主表列、right 关联表列
                if (rest.getOrNull(index) is WrapperArg.OnConditionArg) {
                    onConditions += rest.drop(index).mapNotNull { arg ->
                        (arg as? WrapperArg.OnConditionArg)?.let { oc ->
                            Condition.ColumnComparison(
                                left = oc.left.withTableAlias(mainAlias),
                                right = oc.right.withTableAlias(alias),
                            )
                        }
                    }
                } else {
                    // 1. 三参：leftJoin(Class, 关联表字段, 主表字段) → ON alias.左 = mainAlias.右
                    val left = rest.getOrNull(index) as? WrapperArg.ColumnArg
                    val right = rest.getOrNull(index + 1) as? WrapperArg.ColumnArg
                    if (left != null && right != null) {
                        onConditions += Condition.ColumnComparison(
                            left = left.column.withTableAlias(alias),
                            right = right.column.withTableAlias(mainAlias),
                        )
                    }
                }
            }
        }

        return JoinClause(type, tableName, alias, onConditions)
    }

    /** 字符串 join 的 `{i}` 绑定参数替换（官方示例：`leftJoin("(select ... where addr.id = {0})", 1)`）。 */
    private fun renderJoinSql(template: String, values: List<WrapperArg>): String {
        var sql = template
        for ((index, value) in values.withIndex()) {
            sql = sql.replace("{$index}", renderJoinValue(value.asValue() ?: continue))
        }
        return sql
    }

    /** join 绑定参数渲染：字面量原样（raw），运行时值渲染为 `?`。 */
    private fun renderJoinValue(value: SqlValue): String = when (value) {
        is SqlValue.Literal -> value.raw
        is SqlValue.Placeholder -> "?"
    }

    /** 首参是否为布尔条件前缀（true/false 字面量或运行时布尔表达式占位符）；列引用（ColumnArg）恒为列。 */
    private fun isFlagArgument(arg: WrapperArg): Boolean {
        if (arg is WrapperArg.ColumnArg) return false
        val value = arg.asValue()
        return when (value) {
            is SqlValue.Literal -> value.unquoted == "true" || value.unquoted == "false"
            is SqlValue.Placeholder -> true
            else -> false
        }
    }

    /**
     * 解析 leftJoin(Class, on -> on.eq(...)) 的 lambda ON（清单 P13）。
     *
     * on 内条件：
     *  - `eq(关联表字段, 主表字段)` → 列对列比较（alias.左 = mainAlias.右）
     *  - `eq(关联表字段, 值)` → 简单等值条件（alias.列 = 值）
     */
    private fun parseOnLambda(chain: WrapperChain, alias: String, mainAlias: String): List<Condition> =
        chain.calls.mapNotNull { call ->
            if (call.methodName != "eq" && call.methodName != "ne") return@mapNotNull null
            val left = call.args.getOrNull(0) as? WrapperArg.ColumnArg ?: return@mapNotNull null
            val right = call.args.getOrNull(1)
            when (right) {
                is WrapperArg.ColumnArg ->
                    Condition.ColumnComparison(
                        left = left.column.withTableAlias(alias),
                        right = right.column.withTableAlias(mainAlias),
                    )
                else -> {
                    val value = right?.asValue() ?: return@mapNotNull null
                    Condition.Simple(left.column.withTableAlias(alias), if (call.methodName == "ne") "!=" else "=", value)
                }
            }
        }

    private const val SELECT_AS = "selectAs"
    private const val SELECT_AS_CLASS = "selectAsClass"
    private const val SELECT_ALL = "selectAll"
    private const val SELECT_SUB = "selectSub"
    private const val SET_ALIAS = "setAlias"

    /** UNION 方法（union / unionAll）。 */
    private val UNION_METHODS = setOf("union", "unionAll")
    private const val SELECT_COLLECTION = "selectCollection"
    private const val SELECT_ASSOCIATION = "selectAssociation"

    /** 联表方法全集（leftJoin/innerJoin/rightJoin/fullJoin + join 自定义关键字，官方 API）。 */
    private val JOIN_METHODS = setOf("leftJoin", "innerJoin", "rightJoin", "fullJoin", "join")

    /**
     * 解析 selectAsClass（MPJ 官方 API）：`selectAsClass(源类, 目标类)` —— 按属性名取两表交集字段，
     * 生成 `主表列 AS 属性名` 列（目标类同名属性即映射列）。
     */
    private fun parseSelectAsClass(call: WrapperCall, mainAlias: String): List<ColumnRef> {
        val source = (call.args.getOrNull(0) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val target = (call.args.getOrNull(1) as? WrapperArg.EntityRefArg)?.tableInfo ?: return emptyList()
        val targetFields = target.columns.map { it.fieldName }.toSet()
        return source.columns
            .filter { it.fieldName in targetFields }
            .map { ColumnRef(tableAlias = mainAlias, columnName = it.columnName, alias = it.fieldName) }
    }

    /**
     * 解析 selectAll 调用（官方 API 全覆盖）：
     *  - `selectAll(Class)` → 主表全列
     *  - `selectAll(Class, "prefix")` → 列名加前缀
     *  - `selectAll(Class, Entity::getX, ...)` → 排除字段（exclude）
     *  - `selectAll(Class, "prefix", Entity::getX, ...)` → 前缀 + 排除
     */
    private fun parseSelectAll(call: WrapperCall, mainAlias: String, table: TableInfo): List<ColumnRef> {
        val args = call.args
        // 第二参为字符串字面量（非 .class）→ 列前缀
        val second = args.getOrNull(1)
        val prefix = (second.asValue() as? SqlValue.Literal)
            ?.takeIf { !it.unquoted.trimEnd().endsWith(".class") }
            ?.unquoted
        val excludeStart = if (prefix != null) 2 else 1
        val exclude = args.drop(excludeStart).mapNotNull { it.asColumn()?.columnName }.toSet()
        return table.allColumnNames
            .filter { it !in exclude }
            .map { ColumnRef(tableAlias = mainAlias, columnName = if (prefix != null) prefix + it else it) }
    }

    /**
     * 解析 selectCollection(子表类, VO::get集合[, map -> map.id(...).result(...)]) → LEFT JOIN 子查询（清单 P06/P07）。
     *
     * 形态（对齐 MPJ 一对多子查询语义）：
     * ```
     * LEFT JOIN (
     *   SELECT <子映射列> FROM <子表> <alias> [LEFT JOIN (孙表子查询) ...]
     * ) <alias> ON <同子表 leftJoin 的 ON 条件>
     * ```
     * - 子映射 `id/result` → 子查询 SELECT 列；嵌套 `collection` → 子查询内递归 LEFT JOIN（P07 三层关联）
     * - ON 条件复用同链中同子表的 leftJoin（P06 的既有写法），避免重复指定外键
     */
    private fun parseCollection(call: WrapperCall, joinCalls: List<WrapperCall>, mainAlias: String): JoinClause? {
        val childClass = (call.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return null
        val childTable = NamingStrategy.toSnakeCase(childClass)
        val alias = childClass.replaceFirstChar { it.lowercaseChar() }

        val subSelect = renderCollectionSubSelect(call, childTable, alias)

        // ON 条件：复用同链中同子表 leftJoin 的 ON（T-M15-06 修复——四种 join 形态全覆盖）：
        // 三参（ColumnArg 对）/ 四参带别名 / on(col1, col2) / lambda ON，统一经 parseJoin 提取，
        // 并交换左右使主表列在左（与 on(col1, col2) 形态一致；等值比较交换无副作用）。
        // 修复前仅匹配 on(col1, col2)（OnConditionArg），P06 主形态三参 leftJoin 下子查询无 ON（笛卡尔积）。
        val onConditions = joinCalls
            .filter { joinCall ->
                val first = joinCall.args.getOrNull(0)
                when (first) {
                    is WrapperArg.EntityRefArg -> first.tableInfo.entityClassName == childClass
                    else -> (first.asValue() as? SqlValue.Literal)?.unquoted == childClass
                }
            }
            .flatMap { joinCall ->
                parseJoin(joinCall, mainAlias)?.onConditions?.map { cond ->
                    (cond as? Condition.ColumnComparison)?.let { cc ->
                        Condition.ColumnComparison(left = cc.right, right = cc.left)
                    } ?: cond
                } ?: emptyList()
            }

        return JoinClause(JoinType.LEFT, "($subSelect)", alias, onConditions)
    }

    /**
     * 渲染 selectCollection 的子查询文本（递归支持嵌套 collection）。
     *
     * @param call        selectCollection 调用（或嵌套 collection 调用）
     * @param childTable  子表名
     * @param alias       子表别名
     */
    private fun renderCollectionSubSelect(call: WrapperCall, childTable: String, alias: String): String {
        val subColumns = mutableListOf<String>()
        val nestedJoins = mutableListOf<String>()
        val mapping = call.args.getOrNull(2) as? WrapperArg.NestedArg
        mapping?.chain?.calls?.forEach { c ->
            when (c.methodName) {
                // 子映射 id/result：子查询 SELECT 列
                "id", "result" -> c.args.firstOrNull().asColumn()?.let { subColumns += it.columnName }
                // 嵌套 collection（P07 三层关联）：子查询内递归 LEFT JOIN；外键按「孙表.父表名_id = 父表.id」约定（R7 待 MPJ 对照）
                "collection" -> {
                    val grandChild = (c.args.getOrNull(0).asValue() as? SqlValue.Literal)?.unquoted ?: return@forEach
                    val grandTable = NamingStrategy.toSnakeCase(grandChild)
                    val grandAlias = grandChild.replaceFirstChar { it.lowercaseChar() }
                    val grandSub = renderCollectionSubSelect(c, grandTable, grandAlias)
                    nestedJoins += "LEFT JOIN ($grandSub) $grandAlias ON $grandAlias.${alias}_id = $alias.id"
                }
            }
        }
        if (subColumns.isEmpty()) subColumns += "*"
        val select = "SELECT ${subColumns.joinToString(", ")} FROM $childTable $alias"
        return if (nestedJoins.isEmpty()) select else "$select ${nestedJoins.joinToString(" ")}"
    }
}
