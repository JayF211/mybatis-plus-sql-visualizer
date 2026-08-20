package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.Condition as ModelCondition
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.util.NamingStrategy

/**
 * 派生查询 → SqlStatement 转换器（F-14，JPA 派生查询预览）。
 *
 * 将 [PartTreeParser] 输出的 [PartTree] 模型转换为 [SqlStatement]（[SelectStatement] 或
 * [DeleteStatement]），供 [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 渲染为 SQL。
 *
 * 已知限制：
 *  - 嵌套属性路径（如 `address.city`，点号分隔）不支持 JOIN，生成 `Condition.Raw` 降级提示
 *  - 空间查询（NEAR / WITHIN）不支持，降级为 `Condition.Raw` 提示
 *  - IS_EMPTY / IS_NOT_EMPTY 不支持，降级为 `Condition.Raw` 提示
 *
 * 支持特性：
 *  - 驼峰嵌套属性路径（如 `addressCity`）自动展开为 JOIN（需 TableInfo 含关联元数据）
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
object DerivedQueryConverter {

    /**
     * 转换 PartTree 为 SqlStatement。
     *
     * @param partTree  PartTreeParser 输出的派生查询解析结果
     * @param tableInfo 实体表信息（列映射）
     * @return SELECT 或 DELETE 语句模型
     */
    fun convert(partTree: PartTree, tableInfo: TableInfo): SqlStatement {
        val converter = PartTreeConverter(tableInfo)
        return converter.convert(partTree)
    }

    /**
     * 内部转换器实现（携带表信息上下文）。
     */
    private class PartTreeConverter(private val tableInfo: TableInfo) {

        fun convert(partTree: PartTree): SqlStatement {
            // 步骤 1：收集所有嵌套属性路径，构建 JOIN
            val joins = buildJoins(partTree)

            // 步骤 2：转换条件（嵌套属性列归副表）
            val conditions = convertPredicates(partTree.predicates, joins)

            // 步骤 3：转换 ORDER BY（嵌套属性列归副表）
            val orderBy = convertOrderBy(partTree.orderBy, joins)

            return when (partTree.subject.operation) {
                Operation.DELETE -> DeleteStatement(
                    table = tableInfo,
                    conditions = conditions,
                )

                Operation.COUNT -> SelectStatement(
                    table = tableInfo,
                    selectColumns = listOf(
                        ColumnRef(function = "COUNT", columnName = "*"),
                    ),
                    distinct = partTree.subject.distinct,
                    conditions = conditions,
                    joins = joins,
                    orderBy = orderBy,
                    limit = partTree.subject.limit?.let { LimitClause(count = it.toLong()) },
                )

                Operation.EXISTS -> SelectStatement(
                    table = tableInfo,
                    selectColumns = listOf(
                        ColumnRef(columnName = "1"),
                    ),
                    distinct = partTree.subject.distinct,
                    conditions = conditions,
                    joins = joins,
                    orderBy = orderBy,
                    limit = partTree.subject.limit?.let { LimitClause(count = it.toLong()) },
                )

                Operation.FIND -> SelectStatement(
                    table = tableInfo,
                    distinct = partTree.subject.distinct,
                    conditions = conditions,
                    joins = joins,
                    orderBy = orderBy,
                    limit = partTree.subject.limit?.let { LimitClause(count = it.toLong()) },
                )
            }
        }

        /**
         * 从所有条件中收集嵌套属性路径，构建 JOIN 子句。
         *
         * 规则：
         * - 遍历所有条件的 nestedPath
         * - associationPath 在 TableInfo.associations 中存在 → 构建 JoinClause
         * - 同名关联只构建一次 JOIN（去重）
         * - 别名冲突时递增后缀（address → address1, address2...）
         */
        private fun buildJoins(partTree: PartTree): List<JoinClause> {
            val joinMap = mutableMapOf<String, JoinClause>()
            val usedAliases = mutableSetOf<String>()

            for (predicate in partTree.predicates) {
                for (condition in predicate.conditions) {
                    val nested = condition.nestedPath ?: continue
                    val associationFieldName = nested.associationPath ?: continue

                    val association = tableInfo.associationByField(associationFieldName) ?: continue
                    if (joinMap.containsKey(association.fieldName)) continue

                    // 别名冲突检测
                    var alias = association.targetAlias
                    var suffix = 1
                    while (alias in usedAliases) {
                        alias = association.targetAlias + suffix
                        suffix++
                    }
                    usedAliases.add(alias)

                    joinMap[association.fieldName] = JoinClause(
                        type = JoinType.LEFT,
                        tableName = association.targetTable,
                        alias = alias,
                        onConditions = listOf(
                            ModelCondition.ColumnComparison(
                                left = ColumnRef(tableAlias = alias, columnName = "id"),
                                right = ColumnRef(columnName = association.joinColumn),
                            )
                        ),
                    )
                }
            }
            return joinMap.values.toList()
        }

        /**
         * 转换谓词列表（PredicatePart 列表）为条件树。
         *
         * 规则：
         *  - 多个 PredicatePart 之间按 OR 连接（Spring Data JPA 语义）
         *  - 单个 PredicatePart 内的 conditions 直接平铺，由 SqlGenerator.renderConditionList 处理多行 AND 缩进
         *  - 多个 PredicatePart 时，每个 Part 包装为 AND-Group，外层用 OR-Group 连接
         */
        private fun convertPredicates(
            predicates: List<PredicatePart>,
            joins: List<JoinClause>,
        ): List<ModelCondition> {
            if (predicates.isEmpty()) return emptyList()

            // 单个 PredicatePart：直接返回条件列表（不加 Group 包装，让 SqlGenerator 处理多行 AND 缩进）
            if (predicates.size == 1) {
                val conditions = predicates[0].conditions.map { convertConditionToModel(it, joins) }
                return conditions
            }

            // 多个 PredicatePart：每个 Part 包装为 AND-Group，外层用 OR-Group 连接
            val groups = predicates.mapNotNull { predicatePart ->
                val conditions = predicatePart.conditions.map { convertConditionToModel(it, joins) }
                when {
                    conditions.isEmpty() -> null
                    conditions.size == 1 -> conditions[0]
                    else -> ModelCondition.Group(Connector.AND, conditions)
                }
            }
            if (groups.isEmpty()) return emptyList()
            if (groups.size == 1) return listOf(groups[0])

            return listOf(ModelCondition.Group(Connector.OR, groups))
        }

        /**
         * 转换 OrderByPart 列表为 OrderByItem 列表。
         */
        private fun convertOrderBy(
            orderByParts: List<OrderByPart>,
            joins: List<JoinClause>,
        ): List<OrderByItem> {
            return orderByParts.map { orderByPart ->
                val column = resolveColumn(orderByPart.propertyPath, joins)
                OrderByItem(
                    column = column,
                    asc = orderByPart.direction == SortDirection.ASC,
                )
            }
        }

        /**
         * 转换单个 PartTreeParser 的 Condition 为 SqlStatement 模型的 Condition。
         */
        private fun convertConditionToModel(condition: Condition, joins: List<JoinClause>): ModelCondition {
            val placeholder = SqlValue.Placeholder(condition.propertyPath)

            // 检测嵌套属性路径（含点号分隔）
            if (isNestedProperty(condition.propertyPath)) {
                return ModelCondition.Raw("关联查询: ${condition.propertyPath}")
            }

            // 处理嵌套属性（驼峰形式，如 "addressCity"）
            // 仅当 associationPath 在 TableInfo.associations 中确认存在时才走 JOIN，
            // 否则降级为普通列解析（避免误判驼峰属性名如 "lastName" 为嵌套关联）
            val nested = condition.nestedPath
            val associationPath = nested?.associationPath
            if (nested != null && associationPath != null) {
                val association = tableInfo.associationByField(associationPath)
                if (association != null) {
                    val joinClause = joins.firstOrNull { it.tableName == association.targetTable }
                    if (joinClause != null) {
                        val column = resolveNestedColumn(nested.remainingPath, joinClause.alias)
                        return buildCondition(column, condition.type, condition.ignoreCase, placeholder)
                    }
                }
                // 关联未找到 → 降级为普通列解析
            }

            val column = resolveColumn(condition.propertyPath, joins)

            return buildCondition(column, condition.type, condition.ignoreCase, placeholder)
        }

        /**
         * 根据 PartType 构建对应的条件模型。
         */
        private fun buildCondition(
            column: ColumnRef,
            type: PartType,
            ignoreCase: Boolean,
            placeholder: SqlValue,
        ): ModelCondition = when (type) {
            PartType.SIMPLE_PROPERTY ->
                ModelCondition.Simple(column, "=", placeholder)

            PartType.NEGATING_SIMPLE_PROPERTY ->
                ModelCondition.Simple(column, "!=", placeholder)

            PartType.LIKE ->
                ModelCondition.Like(column, negated = false, placeholder, LikePosition.ANYWHERE)

            PartType.NOT_LIKE ->
                ModelCondition.Like(column, negated = true, placeholder, LikePosition.ANYWHERE)

            PartType.STARTING_WITH ->
                ModelCondition.Like(column, negated = false, placeholder, LikePosition.RIGHT)

            PartType.ENDING_WITH ->
                ModelCondition.Like(column, negated = false, placeholder, LikePosition.LEFT)

            PartType.CONTAINING ->
                ModelCondition.Like(column, negated = false, placeholder, LikePosition.ANYWHERE)

            PartType.NOT_CONTAINING ->
                ModelCondition.Like(column, negated = true, placeholder, LikePosition.ANYWHERE)

            PartType.BETWEEN ->
                ModelCondition.Between(column, negated = false, placeholder, placeholder)

            PartType.IS_NULL ->
                ModelCondition.Unary(column, "IS NULL")

            PartType.IS_NOT_NULL ->
                ModelCondition.Unary(column, "IS NOT NULL")

            PartType.LESS_THAN ->
                ModelCondition.Simple(column, "<", placeholder)

            PartType.LESS_THAN_EQUAL ->
                ModelCondition.Simple(column, "<=", placeholder)

            PartType.GREATER_THAN ->
                ModelCondition.Simple(column, ">", placeholder)

            PartType.GREATER_THAN_EQUAL ->
                ModelCondition.Simple(column, ">=", placeholder)

            PartType.IN ->
                ModelCondition.In(column, negated = false, listOf(placeholder))

            PartType.NOT_IN ->
                ModelCondition.In(column, negated = true, listOf(placeholder))

            PartType.TRUE ->
                ModelCondition.Simple(column, "=", SqlValue.Literal("true"))

            PartType.FALSE ->
                ModelCondition.Simple(column, "=", SqlValue.Literal("false"))

            PartType.BEFORE ->
                ModelCondition.Simple(column, "<", placeholder)

            PartType.AFTER ->
                ModelCondition.Simple(column, ">", placeholder)

            PartType.EXISTS ->
                ModelCondition.Unary(column, "IS NOT NULL")

            PartType.REGEX ->
                ModelCondition.Raw("REGEXP(?, ?)")

            PartType.NEAR ->
                ModelCondition.Raw("空间查询不支持")

            PartType.WITHIN ->
                ModelCondition.Raw("空间查询不支持")

            PartType.IS_EMPTY ->
                ModelCondition.Raw("EMPTY 检查不支持")

            PartType.IS_NOT_EMPTY ->
                ModelCondition.Raw("EMPTY 检查不支持")
        }

        /**
         * 判断是否为嵌套属性路径。
         *
         * 嵌套属性示例：`address.city`（含点号分隔符）。
         */
        private fun isNestedProperty(propertyPath: String): Boolean {
            return propertyPath.contains('.')
        }

        /**
         * 解析属性路径为列引用。
         *
         * 规则：
         *  - 先从 TableInfo 的列映射按实体字段名查找（驼峰形式）
         *  - 找不到则使用驼峰转下划线作为列名
         *
         * 注意：PartTreeParser 输出的 propertyPath 是驼峰形式（如 "lastName"）。
         */
        private fun resolveColumn(propertyPath: String, joins: List<JoinClause> = emptyList()): ColumnRef {
            // 从 TableInfo 按实体字段名查找
            val matched = tableInfo.columnByField(propertyPath)
            return if (matched != null) {
                ColumnRef(columnName = matched.columnName)
            } else {
                // 驼峰转下划线兜底
                ColumnRef(columnName = NamingStrategy.toSnakeCase(propertyPath))
            }
        }

        /**
         * 解析嵌套属性列引用。
         *
         * 例如：remainingPath="City", alias="address"
         *       输出：ColumnRef(tableAlias="address", columnName="city")
         */
        private fun resolveNestedColumn(remainingPath: String, alias: String): ColumnRef {
            val columnName = NamingStrategy.toSnakeCase(remainingPath)
            return ColumnRef(tableAlias = alias, columnName = columnName)
        }
    }
}
