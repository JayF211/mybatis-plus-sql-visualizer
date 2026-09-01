package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
public final class DerivedQueryConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final DerivedQueryConverter INSTANCE = new DerivedQueryConverter();

    private DerivedQueryConverter() {
    }

    /**
     * 转换 PartTree 为 SqlStatement。
     *
     * @param partTree  PartTreeParser 输出的派生查询解析结果
     * @param tableInfo 实体表信息（列映射）
     * @return SELECT 或 DELETE 语句模型
     */
    public static SqlStatement convert(@NotNull PartTree partTree, @NotNull TableInfo tableInfo) {
        PartTreeConverter converter = new PartTreeConverter(tableInfo);
        return converter.convert(partTree);
    }

    /**
     * 内部转换器实现（携带表信息上下文）。
     */
    private static final class PartTreeConverter {

        private final TableInfo tableInfo;

        private PartTreeConverter(@NotNull TableInfo tableInfo) {
            this.tableInfo = tableInfo;
        }

        SqlStatement convert(@NotNull PartTree partTree) {
            // 步骤 1：收集所有嵌套属性路径，构建 JOIN
            List<JoinClause> joins = buildJoins(partTree);

            // 步骤 2：转换条件（嵌套属性列归副表）
            List<Condition> conditions = convertPredicates(partTree.getPredicates(), joins);

            // 步骤 3：转换 ORDER BY（嵌套属性列归副表）
            List<OrderByItem> orderBy = convertOrderBy(partTree.getOrderBy(), joins);

            LimitClause limitClause = partTree.getSubject().getLimit() != null
                    ? new LimitClause(null, partTree.getSubject().getLimit().longValue())
                    : null;

            return switch (partTree.getSubject().getOperation()) {
                case Operation.DELETE -> new DeleteStatement(tableInfo, conditions, List.of(), null, List.of());

                case Operation.COUNT -> new SelectStatement(
                        tableInfo,
                        List.of(new ColumnRef(null, "*", null, "COUNT", null, null, null)),
                        null,
                        joins,
                        partTree.getSubject().isDistinct(),
                        conditions,
                        List.of(),
                        null,
                        orderBy,
                        limitClause,
                        null, null, null, List.of(), null, null);

                case Operation.EXISTS -> new SelectStatement(
                        tableInfo,
                        List.of(new ColumnRef(null, "1", null, null, null, null, null)),
                        null,
                        joins,
                        partTree.getSubject().isDistinct(),
                        conditions,
                        List.of(),
                        null,
                        orderBy,
                        limitClause,
                        null, null, null, List.of(), null, null);

                case Operation.FIND -> new SelectStatement(
                        tableInfo,
                        List.of(),
                        null,
                        joins,
                        partTree.getSubject().isDistinct(),
                        conditions,
                        List.of(),
                        null,
                        orderBy,
                        limitClause,
                        null, null, null, List.of(), null, null);
            };
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
        private List<JoinClause> buildJoins(@NotNull PartTree partTree) {
            Map<String, JoinClause> joinMap = new LinkedHashMap<>();
            Set<String> usedAliases = new LinkedHashSet<>();

            for (PredicatePart predicate : partTree.getPredicates()) {
                for (cn.song.mybatisplus.visualizer.converter.jpa.Condition condition : predicate.getConditions()) {
                    NestedPropertyPath nested = condition.getNestedPath();
                    if (nested == null) continue;
                    String associationFieldName = nested.getAssociationPath();
                    if (associationFieldName == null) continue;

                    AssociationInfo association = tableInfo.associationByField(associationFieldName);
                    if (association == null) continue;
                    if (joinMap.containsKey(association.getFieldName())) continue;

                    // 别名冲突检测
                    String alias = association.getTargetAlias();
                    int suffix = 1;
                    while (usedAliases.contains(alias)) {
                        alias = association.getTargetAlias() + suffix;
                        suffix++;
                    }
                    usedAliases.add(alias);

                    joinMap.put(association.getFieldName(), new JoinClause(
                            JoinType.LEFT,
                            association.getTargetTable(),
                            alias,
                            List.of(new Condition.ColumnComparison(
                                    new ColumnRef(alias, "id", null, null, null, null, null),
                                    new ColumnRef(null, association.getJoinColumn(), null, null, null, null, null))),
                            null, null, null, null));
                }
            }
            return new ArrayList<>(joinMap.values());
        }

        /**
         * 转换谓词列表（PredicatePart 列表）为条件树。
         *
         * 规则：
         *  - 多个 PredicatePart 之间按 OR 连接（Spring Data JPA 语义）
         *  - 单个 PredicatePart 内的 conditions 直接平铺
         *  - 多个 PredicatePart 时，每个 Part 包装为 AND-Group，外层用 OR-Group 连接
         */
        private List<Condition> convertPredicates(
                @NotNull List<PredicatePart> predicates,
                @NotNull List<JoinClause> joins) {
            if (predicates.isEmpty()) return List.of();

            // 单个 PredicatePart：直接返回条件列表（不加 Group 包装）
            if (predicates.size() == 1) {
                List<Condition> conditions = predicates.get(0).getConditions().stream()
                        .map(c -> convertConditionToModel(c, joins)).toList();
                return conditions;
            }

            // 多个 PredicatePart：每个 Part 包装为 AND-Group，外层用 OR-Group 连接
            List<Condition> groups = predicates.stream().map(predicatePart -> {
                List<Condition> conditions = predicatePart.getConditions().stream()
                        .map(c -> convertConditionToModel(c, joins)).toList();
                if (conditions.isEmpty()) return null;
                if (conditions.size() == 1) return conditions.get(0);
                return new Condition.Group(Connector.AND, conditions, null, false);
            }).filter(c -> c != null).toList();
            if (groups.isEmpty()) return List.of();
            if (groups.size() == 1) return List.of(groups.get(0));

            return List.of(new Condition.Group(Connector.OR, groups, null, false));
        }

        /**
         * 转换 OrderByPart 列表为 OrderByItem 列表。
         */
        private List<OrderByItem> convertOrderBy(
                @NotNull List<OrderByPart> orderByParts,
                @NotNull List<JoinClause> joins) {
            return orderByParts.stream().map(orderByPart -> {
                ColumnRef column = resolveColumn(orderByPart.getPropertyPath(), joins);
                return new OrderByItem(column, orderByPart.getDirection() == SortDirection.ASC);
            }).toList();
        }

        /**
         * 转换单个 PartTreeParser 的 Condition 为 SqlStatement 模型的 Condition。
         */
        private Condition convertConditionToModel(
                @NotNull cn.song.mybatisplus.visualizer.converter.jpa.Condition condition,
                @NotNull List<JoinClause> joins) {
            SqlValue placeholder = new SqlValue.Placeholder(condition.getPropertyPath());

            // 检测嵌套属性路径（含点号分隔）
            if (isNestedProperty(condition.getPropertyPath())) {
                return new Condition.Raw("关联查询: " + condition.getPropertyPath());
            }

            // 处理嵌套属性（驼峰形式，如 "addressCity"）
            NestedPropertyPath nested = condition.getNestedPath();
            String associationPath = nested != null ? nested.getAssociationPath() : null;
            if (nested != null && associationPath != null) {
                AssociationInfo association = tableInfo.associationByField(associationPath);
                if (association != null) {
                    JoinClause joinClause = joins.stream()
                            .filter(j -> j.getTableName().equals(association.getTargetTable()))
                            .findFirst().orElse(null);
                    if (joinClause != null) {
                        ColumnRef column = resolveNestedColumn(nested.getRemainingPath(), joinClause.getAlias());
                        return buildCondition(column, condition.getType(), condition.ignoreCase(), placeholder);
                    }
                }
                // 关联未找到 → 降级为普通列解析
            }

            ColumnRef column = resolveColumn(condition.getPropertyPath(), joins);
            return buildCondition(column, condition.getType(), condition.ignoreCase(), placeholder);
        }

        /**
         * 根据 PartType 构建对应的条件模型。
         */
        private Condition buildCondition(
                @NotNull ColumnRef column,
                @NotNull PartType type,
                boolean ignoreCase,
                @NotNull SqlValue placeholder) {
            switch (type) {
                case SIMPLE_PROPERTY:
                    return new Condition.Simple(column, "=", placeholder, null);
                case NEGATING_SIMPLE_PROPERTY:
                    return new Condition.Simple(column, "!=", placeholder, null);
                case LIKE:
                    return new Condition.Like(column, false, placeholder, LikePosition.ANYWHERE, null);
                case NOT_LIKE:
                    return new Condition.Like(column, true, placeholder, LikePosition.ANYWHERE, null);
                case STARTING_WITH:
                    return new Condition.Like(column, false, placeholder, LikePosition.RIGHT, null);
                case ENDING_WITH:
                    return new Condition.Like(column, false, placeholder, LikePosition.LEFT, null);
                case CONTAINING:
                    return new Condition.Like(column, false, placeholder, LikePosition.ANYWHERE, null);
                case NOT_CONTAINING:
                    return new Condition.Like(column, true, placeholder, LikePosition.ANYWHERE, null);
                case BETWEEN:
                    return new Condition.Between(column, false, placeholder, placeholder, null);
                case IS_NULL:
                    return new Condition.Unary(column, "IS NULL", null);
                case IS_NOT_NULL:
                    return new Condition.Unary(column, "IS NOT NULL", null);
                case LESS_THAN:
                    return new Condition.Simple(column, "<", placeholder, null);
                case LESS_THAN_EQUAL:
                    return new Condition.Simple(column, "<=", placeholder, null);
                case GREATER_THAN:
                    return new Condition.Simple(column, ">", placeholder, null);
                case GREATER_THAN_EQUAL:
                    return new Condition.Simple(column, ">=", placeholder, null);
                case IN:
                    return new Condition.In(column, false, List.of(placeholder), null);
                case NOT_IN:
                    return new Condition.In(column, true, List.of(placeholder), null);
                case TRUE:
                    return new Condition.Simple(column, "=", new SqlValue.Literal("true", "true"), null);
                case FALSE:
                    return new Condition.Simple(column, "=", new SqlValue.Literal("false", "false"), null);
                case BEFORE:
                    return new Condition.Simple(column, "<", placeholder, null);
                case AFTER:
                    return new Condition.Simple(column, ">", placeholder, null);
                case EXISTS:
                    return new Condition.Unary(column, "IS NOT NULL", null);
                case REGEX:
                    return new Condition.Raw("REGEXP(?, ?)");
                case NEAR:
                    return new Condition.Raw("空间查询不支持");
                case WITHIN:
                    return new Condition.Raw("空间查询不支持");
                case IS_EMPTY:
                    return new Condition.Raw("EMPTY 检查不支持");
                case IS_NOT_EMPTY:
                    return new Condition.Raw("EMPTY 检查不支持");
            }
            throw new IllegalArgumentException("未知的 PartType: " + type);
        }

        /**
         * 判断是否为嵌套属性路径。
         *
         * 嵌套属性示例：`address.city`（含点号分隔符）。
         */
        private boolean isNestedProperty(@NotNull String propertyPath) {
            return propertyPath.contains(".");
        }

        /**
         * 解析属性路径为列引用。
         *
         * 规则：
         *  - 先从 TableInfo 的列映射按实体字段名查找（驼峰形式）
         *  - 找不到则使用驼峰转下划线作为列名
         */
        private ColumnRef resolveColumn(@NotNull String propertyPath, @NotNull List<JoinClause> joins) {
            // 从 TableInfo 按实体字段名查找
            ColumnInfo matched = tableInfo.columnByField(propertyPath);
            if (matched != null) {
                return new ColumnRef(null, matched.getColumnName(), null, null, null, null, null);
            }
            // 驼峰转下划线兜底
            return new ColumnRef(null, NamingStrategy.INSTANCE.toSnakeCase(propertyPath), null, null, null, null, null);
        }

        /**
         * 解析嵌套属性列引用。
         *
         * 例如：remainingPath="City", alias="address"
         *       输出：ColumnRef(tableAlias="address", columnName="city")
         */
        private ColumnRef resolveNestedColumn(@NotNull String remainingPath, @NotNull String alias) {
            String columnName = NamingStrategy.INSTANCE.toSnakeCase(remainingPath);
            return new ColumnRef(alias, columnName, null, null, null, null, null);
        }
    }
}
