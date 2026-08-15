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
     */
    data class EntityTarget(
        val className: String,
        val columnToField: Map<String, String>,
    )

    /** 生成完整代码片段（字符串列名 QueryWrapper 形态）。 */
    fun generate(result: SqlReverseResult): String = generate(result, null)

    /**
     * 生成完整代码片段。
     *
     * @param entity 非 null → LambdaQueryWrapper 形态（列→字段映射）；null → 字符串列名形态
     */
    fun generate(result: SqlReverseResult, entity: EntityTarget?): String {
        val sb = StringBuilder()
        if (entity == null) {
            sb.append("// SQL → MyBatis-Plus QueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）\n")
            val entityName = entityClassName(result.tableName)
            sb.append("// 实体类名 $entityName 由表名 ${result.tableName} 推断，请按实际类名调整\n")
        } else {
            sb.append("// SQL → MyBatis-Plus LambdaQueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）\n")
            sb.append("// 实体类 ${entity.className} 由表名 ${result.tableName} 匹配\n")
            val unmapped = unmappedColumns(result, entity)
            if (unmapped.isNotEmpty()) {
                sb.append("// 注意：以下列未在实体中找到对应字段，已回落字符串列名：${unmapped.joinToString(", ")}\n")
            }
        }
        result.warnings.forEach { sb.append("// 注意：$it\n") }

        val wrapperName = if (entity == null) "QueryWrapper" else "LambdaQueryWrapper"
        val entityName = entity?.className ?: entityClassName(result.tableName)
        sb.append("new $wrapperName<$entityName>()")
        if (result.selectColumns != null && result.selectColumns.isNotEmpty()) {
            sb.append("\n    .select(")
                .append(result.selectColumns.joinToString(", ") { renderColumnRef(it, entity) })
                .append(")")
        }
        result.conditions.forEach { sb.append("\n    .").append(renderCondition(it, entity)) }
        renderOrderBy(result, entity).forEach { sb.append("\n    .").append(it) }
        result.limit?.let { sb.append("\n    .last(\"").append(javaEscape(renderLimit(it))).append("\")") }
        sb.append(";")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 列渲染（T-M36.2：lambda 模式列→属性）
    // ------------------------------------------------------------------

    /**
     * 列引用渲染：lambda 模式且列有字段映射 → `Entity::getField`；
     * 否则回落字符串列名 `"col_name"`。
     */
    private fun renderColumnRef(columnName: String, entity: EntityTarget?): String {
        val field = entity?.columnToField?.get(columnName) ?: return javaString(columnName)
        return "${entity.className}::get${field.replaceFirstChar { it.uppercase() }}"
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
            "${opMethod(condition.op)}(${renderColumnRef(condition.column.columnName, entity)}, ${javaValue(condition.value)})"

        is Condition.Unary -> when (condition.op) {
            "IS NULL" -> "isNull(${renderColumnRef(condition.column.columnName, entity)})"
            else -> "isNotNull(${renderColumnRef(condition.column.columnName, entity)})"
        }

        is Condition.In -> {
            val method = if (condition.negated) "notIn" else "in"
            "$method(${renderColumnRef(condition.column.columnName, entity)}, ${condition.values.joinToString(", ") { javaValue(it) }})"
        }

        is Condition.Between -> {
            val method = if (condition.negated) "notBetween" else "between"
            "$method(${renderColumnRef(condition.column.columnName, entity)}, ${javaValue(condition.lo)}, ${javaValue(condition.hi)})"
        }

        is Condition.Like -> {
            val method = when (condition.position) {
                LikePosition.ANYWHERE -> if (condition.negated) "notLike" else "like"
                LikePosition.LEFT -> if (condition.negated) "notLikeLeft" else "likeLeft"
                LikePosition.RIGHT -> if (condition.negated) "notLikeRight" else "likeRight"
                LikePosition.NONE -> if (condition.negated) "notLike" else "like"
            }
            "$method(${renderColumnRef(condition.column.columnName, entity)}, ${javaValue(condition.pattern)})"
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
        var group = mutableListOf<String>()
        var groupAsc = result.orderBy[0].asc
        result.orderBy.forEach { item ->
            if (item.asc != groupAsc && group.isNotEmpty()) {
                calls.add(orderByCall(groupAsc, group, entity))
                group = mutableListOf()
                groupAsc = item.asc
            }
            group.add(item.column.columnName)
        }
        if (group.isNotEmpty()) calls.add(orderByCall(groupAsc, group, entity))
        return calls
    }

    private fun orderByCall(asc: Boolean, columns: List<String>, entity: EntityTarget?): String {
        val method = if (asc) "orderByAsc" else "orderByDesc"
        return "$method(${columns.joinToString(", ") { renderColumnRef(it, entity) }})"
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
