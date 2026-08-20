package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.DeleteStatement
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
 *  - 嵌套属性路径（如 `address.city`）暂不支持 JOIN，生成 `Condition.Raw` 降级提示
 *  - 空间查询（NEAR / WITHIN）不支持，降级为 `Condition.Raw` 提示
 *  - IS_EMPTY / IS_NOT_EMPTY 不支持，降级为 `Condition.Raw` 提示
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
            val conditions = convertPredicates(partTree.predicates)
            val orderBy = convertOrderBy(partTree.orderBy)

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
                    orderBy = orderBy,
                    limit = partTree.subject.limit?.let { LimitClause(count = it.toLong()) },
                )

                Operation.FIND -> SelectStatement(
                    table = tableInfo,
                    distinct = partTree.subject.distinct,
                    conditions = conditions,
                    orderBy = orderBy,
                    limit = partTree.subject.limit?.let { LimitClause(count = it.toLong()) },
                )
            }
        }

        /**
         * 转换谓词列表（PredicatePart 列表）为条件树。
         *
         * 规则：
         *  - 多个 PredicatePart 之间按 OR 连接（Spring Data JPA 语义）
         *  - 单个 PredicatePart 内的 conditions 直接平铺，由 SqlGenerator.renderConditionList 处理多行 AND 缩进
         *  - 多个 PredicatePart 时，每个 Part 包装为 AND-Group，外层用 OR-Group 连接
         */
        private fun convertPredicates(predicates: List<PredicatePart>): List<cn.song.mybatisplus.visualizer.model.Condition> {
            if (predicates.isEmpty()) return emptyList()

            // 单个 PredicatePart：直接返回条件列表（不加 Group 包装，让 SqlGenerator 处理多行 AND 缩进）
            if (predicates.size == 1) {
                val conditions = predicates[0].conditions.map { convertConditionToModel(it) }
                return conditions
            }

            // 多个 PredicatePart：每个 Part 包装为 AND-Group，外层用 OR-Group 连接
            val groups = predicates.mapNotNull { predicatePart ->
                val conditions = predicatePart.conditions.map { convertConditionToModel(it) }
                when {
                    conditions.isEmpty() -> null
                    conditions.size == 1 -> conditions[0]
                    else -> cn.song.mybatisplus.visualizer.model.Condition.Group(Connector.AND, conditions)
                }
            }
            if (groups.isEmpty()) return emptyList()
            if (groups.size == 1) return listOf(groups[0])

            return listOf(cn.song.mybatisplus.visualizer.model.Condition.Group(Connector.OR, groups))
        }

        /**
         * 转换 OrderByPart 列表为 OrderByItem 列表。
         */
        private fun convertOrderBy(orderByParts: List<OrderByPart>): List<OrderByItem> {
            return orderByParts.map { orderByPart ->
                val column = resolveColumn(orderByPart.propertyPath)
                OrderByItem(
                    column = column,
                    asc = orderByPart.direction == SortDirection.ASC,
                )
            }
        }

        /**
         * 转换单个 PartTreeParser 的 Condition 为 SqlStatement 模型的 Condition。
         */
        private fun convertConditionToModel(condition: Condition): cn.song.mybatisplus.visualizer.model.Condition {
            val placeholder = SqlValue.Placeholder(condition.propertyPath)

            // 检测嵌套属性路径（含点号分隔）
            if (isNestedProperty(condition.propertyPath)) {
                return cn.song.mybatisplus.visualizer.model.Condition.Raw("关联查询: ${condition.propertyPath}")
            }

            val column = resolveColumn(condition.propertyPath)

            return when (condition.type) {
                PartType.SIMPLE_PROPERTY ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "=", placeholder)

                PartType.NEGATING_SIMPLE_PROPERTY ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "!=", placeholder)

                PartType.LIKE ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = false, placeholder, LikePosition.ANYWHERE)

                PartType.NOT_LIKE ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = true, placeholder, LikePosition.ANYWHERE)

                PartType.STARTING_WITH ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = false, placeholder, LikePosition.RIGHT)

                PartType.ENDING_WITH ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = false, placeholder, LikePosition.LEFT)

                PartType.CONTAINING ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = false, placeholder, LikePosition.ANYWHERE)

                PartType.NOT_CONTAINING ->
                    cn.song.mybatisplus.visualizer.model.Condition.Like(column, negated = true, placeholder, LikePosition.ANYWHERE)

                PartType.BETWEEN ->
                    cn.song.mybatisplus.visualizer.model.Condition.Between(column, negated = false, placeholder, placeholder)

                PartType.IS_NULL ->
                    cn.song.mybatisplus.visualizer.model.Condition.Unary(column, "IS NULL")

                PartType.IS_NOT_NULL ->
                    cn.song.mybatisplus.visualizer.model.Condition.Unary(column, "IS NOT NULL")

                PartType.LESS_THAN ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "<", placeholder)

                PartType.LESS_THAN_EQUAL ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "<=", placeholder)

                PartType.GREATER_THAN ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, ">", placeholder)

                PartType.GREATER_THAN_EQUAL ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, ">=", placeholder)

                PartType.IN ->
                    cn.song.mybatisplus.visualizer.model.Condition.In(column, negated = false, listOf(placeholder))

                PartType.NOT_IN ->
                    cn.song.mybatisplus.visualizer.model.Condition.In(column, negated = true, listOf(placeholder))

                PartType.TRUE ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "=", SqlValue.Literal("true"))

                PartType.FALSE ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "=", SqlValue.Literal("false"))

                PartType.BEFORE ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, "<", placeholder)

                PartType.AFTER ->
                    cn.song.mybatisplus.visualizer.model.Condition.Simple(column, ">", placeholder)

                PartType.EXISTS ->
                    cn.song.mybatisplus.visualizer.model.Condition.Unary(column, "IS NOT NULL")

                PartType.REGEX ->
                    cn.song.mybatisplus.visualizer.model.Condition.Raw("REGEXP(?, ?)")

                PartType.NEAR ->
                    cn.song.mybatisplus.visualizer.model.Condition.Raw("空间查询不支持")

                PartType.WITHIN ->
                    cn.song.mybatisplus.visualizer.model.Condition.Raw("空间查询不支持")

                PartType.IS_EMPTY ->
                    cn.song.mybatisplus.visualizer.model.Condition.Raw("EMPTY 检查不支持")

                PartType.IS_NOT_EMPTY ->
                    cn.song.mybatisplus.visualizer.model.Condition.Raw("EMPTY 检查不支持")
            }
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
        private fun resolveColumn(propertyPath: String): ColumnRef {
            // 从 TableInfo 按实体字段名查找
            val matched = tableInfo.columnByField(propertyPath)
            return if (matched != null) {
                ColumnRef(columnName = matched.columnName)
            } else {
                // 驼峰转下划线兜底
                ColumnRef(columnName = NamingStrategy.toSnakeCase(propertyPath))
            }
        }
    }
}
