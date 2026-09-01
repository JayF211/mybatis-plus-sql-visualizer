package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel;
import cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel;
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CriteriaQuery → SqlStatement 转换器（F-JPA-02）。
 *
 * 将 [CriteriaQueryModel] 中间表示转换为 [SelectStatement]，供
 * [cn.song.mybatisplus.visualizer.sql.SqlGenerator] 渲染为 SQL。
 *
 * CriteriaBuilder 方法 → Condition 映射：
 *  - cb.equal(a, b)              → Condition.Simple(col, "=", value)
 *  - cb.notEqual(a, b)           → Condition.Simple(col, "!=", value)
 *  - cb.greaterThan(a, b)        → Condition.Simple(col, ">", value)
 *  - cb.greaterThanOrEqualTo(a,b)→ Condition.Simple(col, ">=", value)
 *  - cb.lessThan(a, b)           → Condition.Simple(col, "<", value)
 *  - cb.lessThanOrEqualTo(a, b)  → Condition.Simple(col, "<=", value)
 *  - cb.between(a, lo, hi)       → Condition.Between(col, false, lo, hi)
 *  - cb.isNull(a)                → Condition.Unary(col, "IS NULL")
 *  - cb.isNotNull(a)             → Condition.Unary(col, "IS NOT NULL")
 *  - cb.like(a, pattern)         → Condition.Like(col, false, pattern, Anywhere)
 *  - cb.notLike(a, pattern)       → Condition.Like(col, true, pattern, Anywhere)
 *  - cb.in(a).value(b)           → Condition.In(col, false, listOf(b))
 *  - cb.and(p1, p2)              → Condition.Group(AND, listOf(p1, p2))
 *  - cb.or(p1, p2)              → Condition.Group(OR, listOf(p1, p2))
 *  - cb.not(p)                   → Condition.Group(AND, listOf(p), negated=true)
 *
 * 无状态单例，纯函数逻辑，可独立单测。
 */
public final class CriteriaQueryConverter {

    /** 单例实例（兼容 Kotlin object 调用形式）。 */
    public static final CriteriaQueryConverter INSTANCE = new CriteriaQueryConverter();

    private CriteriaQueryConverter() {
    }

    /**
     * 转换 CriteriaQueryModel 为 SqlStatement（overrideTable 取默认 null）。
     *
     * @param model CriteriaQueryLocator 输出的中间模型
     * @return SelectStatement；无法解析实体时返回全 Raw 降级的 SELECT
     */
    public static SqlStatement convert(@NotNull CriteriaQueryModel model) {
        return convert(model, null);
    }

    /**
     * 转换 CriteriaQueryModel 为 SqlStatement。
     *
     * @param model         CriteriaQueryLocator 输出的中间模型
     * @param overrideTable 可选的表信息覆盖（测试/外部传入预解析表信息用）；
     *                      null 时按 model.entityClass 或 model.entityClassName 解析
     * @return SelectStatement；无法解析实体时返回全 Raw 降级的 SELECT
     */
    public static SqlStatement convert(@NotNull CriteriaQueryModel model, @Nullable TableInfo overrideTable) {
        TableInfo tableInfo = overrideTable != null ? overrideTable : resolveTableInfo(model);
        List<Condition> conditions = convertPredicates(model.getPredicates(), tableInfo);
        List<ColumnRef> groupBy = convertGroupBy(model.getGroupBys(), tableInfo);
        Condition having = convertHaving(model.getHavings(), tableInfo);
        List<OrderByItem> orderBy = convertOrderBy(model.getOrderBys(), tableInfo);
        List<JoinClause> joins = convertJoins(model.getJoins(), tableInfo);
        List<ColumnRef> selectColumns = convertSelectColumns(model.getSelectColumns(), tableInfo);

        return new SelectStatement(
                tableInfo,
                selectColumns,
                null,
                joins,
                false,
                conditions,
                groupBy,
                having,
                orderBy,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null);
    }

    // ------------------------------------------------------------------
    // 表信息解析
    // ------------------------------------------------------------------

    /**
     * 解析实体表信息。
     *
     * 优先通过 entityClass PsiClass 走 JpaEntityResolver 解析（@Table/@Column 注解）；
     * 不可解析时按命名策略由类名推断表名。
     */
    private static TableInfo resolveTableInfo(@NotNull CriteriaQueryModel model) {
        PsiClass entityClass = model.getEntityClass();
        if (entityClass != null) {
            return new JpaEntityResolver().resolve(entityClass);
        }
        String className = model.getEntityClassName() != null
                ? model.getEntityClassName()
                : (model.getResultClassName() != null ? model.getResultClassName() : "UnknownEntity");
        return new TableInfo(
                className,
                JpaEntityResolver.inferDecoratedTableName(className),
                List.of(),
                null,
                null,
                null,
                List.of());
    }

    // ------------------------------------------------------------------
    // SELECT 列转换
    // ------------------------------------------------------------------

    /**
     * 转换 SELECT 列为 ColumnRef 列表。
     *
     * 规则（增强版 P1-5 聚合函数支持）：
     *  - 空列表 → 空（converter 层全列语义，生成器展开为 SELECT *）
     *  - 普通列 → 按字段名查找实体列映射，找不到时按命名策略兜底
     *  - 聚合列 → ColumnRef.function 设置为聚合函数名
     *    - COUNT(*) → function="COUNT", columnName="*"
     *    - SUM(field) → function="SUM", columnName=解析后列名
     */
    private static List<ColumnRef> convertSelectColumns(
            @NotNull List<SelectColumn> columns,
            @NotNull TableInfo tableInfo) {
        if (columns.isEmpty()) return List.of();
        return columns.stream().map(sel -> {
            // 聚合函数处理
            if (sel.getAggregate() != null) {
                AggregateInfo agg = sel.getAggregate();
                String columnName;
                if (agg.getFieldName() == null) {
                    columnName = "*"; // COUNT(*) 语义
                } else {
                    columnName = resolveColumnName(agg.getFieldName(), tableInfo);
                }
                return new ColumnRef(null, columnName, sel.getAlias(), agg.getFunction(), null, null, null);
            }

            // 普通列处理
            String fieldName = sel.getFieldName();
            if (fieldName == null) return new ColumnRef(null, "*", null, null, null, null, null);
            String columnName = resolveColumnName(fieldName, tableInfo);
            return new ColumnRef(null, columnName, sel.getAlias(), null, null, null, null);
        }).toList();
    }

    // ------------------------------------------------------------------
    // WHERE 条件转换
    // ------------------------------------------------------------------

    /**
     * 转换谓词列表为 Condition 列表。
     */
    private static List<Condition> convertPredicates(
            @NotNull List<PredicateModel> predicates,
            @NotNull TableInfo tableInfo) {
        return predicates.stream().map(p -> convertPredicate(p, tableInfo)).toList();
    }

    /**
     * 转换单个 PredicateModel 为 Condition。
     */
    private static Condition convertPredicate(@NotNull PredicateModel predicate, @NotNull TableInfo tableInfo) {
        if (predicate instanceof PredicateModel.Simple s) {
            ColumnRef column = resolveColumnRef(s.getFieldName(), tableInfo);
            return new Condition.Simple(column, s.getOp(), sqlValue(s.getValueExpression()), null);
        } else if (predicate instanceof PredicateModel.IsNull s) {
            ColumnRef column = resolveColumnRef(s.getFieldName(), tableInfo);
            String op = s.isNegated() ? "IS NOT NULL" : "IS NULL";
            return new Condition.Unary(column, op, null);
        } else if (predicate instanceof PredicateModel.Between s) {
            ColumnRef column = resolveColumnRef(s.getFieldName(), tableInfo);
            return new Condition.Between(
                    column,
                    false,
                    sqlValue(s.getLoExpression()),
                    sqlValue(s.getHiExpression()),
                    null);
        } else if (predicate instanceof PredicateModel.Like s) {
            ColumnRef column = resolveColumnRef(s.getFieldName(), tableInfo);
            return new Condition.Like(
                    column,
                    s.isNegated(),
                    sqlValue(s.getPatternExpression()),
                    LikePosition.ANYWHERE,
                    null);
        } else if (predicate instanceof PredicateModel.In s) {
            ColumnRef column = resolveColumnRef(s.getFieldName(), tableInfo);
            List<String> rawValues = s.getValueExpressions();
            // 处理占位符标记（无值时面板可输入多值）
            List<SqlValue> values;
            if (rawValues.equals(List.of("__IN_PLACEHOLDER__"))) {
                values = List.of(new SqlValue.Placeholder("statuses"));
            } else {
                values = rawValues.stream().map(CriteriaQueryConverter::sqlValue).toList();
            }
            return new Condition.In(column, s.isNegated(), values, null);
        } else if (predicate instanceof PredicateModel.Group s) {
            Connector connector = s.getConnector() == GroupConnector.AND ? Connector.AND : Connector.OR;
            List<Condition> children = s.getChildren().stream()
                    .map(p -> convertPredicate(p, tableInfo)).toList();
            return new Condition.Group(connector, children, null, s.isNegated());
        } else if (predicate instanceof PredicateModel.Raw s) {
            return new Condition.Raw(s.getExpressionText(), null);
        }
        throw new IllegalArgumentException("未知的 PredicateModel 类型: " + predicate);
    }

    // ------------------------------------------------------------------
    // GROUP BY 转换
    // ------------------------------------------------------------------

    /**
     * 转换 GroupByModel 列表为 ColumnRef 列表。
     */
    private static List<ColumnRef> convertGroupBy(
            @NotNull List<GroupByModel> groupBys,
            @NotNull TableInfo tableInfo) {
        return groupBys.stream()
                .map(g -> new ColumnRef(null, resolveColumnName(g.getFieldName(), tableInfo), null, null, null, null, null))
                .toList();
    }

    // ------------------------------------------------------------------
    // HAVING 转换
    // ------------------------------------------------------------------

    /**
     * 转换 HAVING 谓词列表为单个 Condition（AND 连接）。
     *
     * 规则：
     *  - 空列表 → null（无 HAVING）
     *  - 单条件 → 直接使用
     *  - 多条件 → AND 连接的 Group
     */
    @Nullable
    private static Condition convertHaving(
            @NotNull List<PredicateModel> havings,
            @NotNull TableInfo tableInfo) {
        if (havings.isEmpty()) return null;
        List<Condition> conditions = havings.stream().map(p -> convertPredicate(p, tableInfo)).toList();
        if (conditions.size() == 1) {
            return conditions.get(0);
        }
        return new Condition.Group(Connector.AND, conditions, null, false);
    }

    // ------------------------------------------------------------------
    // ORDER BY 转换
    // ------------------------------------------------------------------

    /**
     * 转换 OrderByModel 列表为 OrderByItem 列表。
     */
    private static List<OrderByItem> convertOrderBy(
            @NotNull List<OrderByModel> orderBys,
            @NotNull TableInfo tableInfo) {
        return orderBys.stream()
                .map(ob -> new OrderByItem(resolveColumnRef(ob.getFieldName(), tableInfo), ob.isAsc()))
                .toList();
    }

    // ------------------------------------------------------------------
    // JOIN 转换
    // ------------------------------------------------------------------

    /**
     * 转换 JoinModel 列表为 JoinClause 列表。
     *
     * 增强版（JOIN ON 条件推断 P1-6）：
     *  - 从 root.join("department") 的 fieldName 推断关联表名
     *  - 读取主实体列映射推断外键列
     *  - 生成 ON 条件: alias.id = root_fk_column
     *
     * @param tableInfo 主实体表信息（用于列映射和 JOIN ON 推断）
     */
    private static List<JoinClause> convertJoins(
            @NotNull List<JoinModel> joins,
            @NotNull TableInfo tableInfo) {
        return joins.stream().map(join -> {
            String entityClassName = join.getEntityClassName() != null ? join.getEntityClassName() : join.getFieldName();
            String tableName = JpaEntityResolver.inferDecoratedTableName(entityClassName);
            String fieldName = join.getFieldName();
            String alias = join.getAlias() != null
                    ? join.getAlias()
                    : fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);

            // JOIN ON 条件推断
            List<Condition> onConditions = inferJoinOnConditions(join, tableInfo, alias);

            return new JoinClause(join.getJoinType(), tableName, alias, onConditions, null, null, null, null);
        }).toList();
    }

    /**
     * 推断 CriteriaQuery JOIN 的 ON 条件。
     *
     * 启发式规则（JPA @JoinColumn 默认命名）：
     *  - root.join("department") → 从主实体 department 属性推断外键列
     *    - 查找主实体 TableInfo 中名为 "department" 的字段
     *    - 若无精确匹配，按命名策略生成：department → department_id
     *  - ON 条件: alias.id = root_fk_column
     */
    private static List<Condition> inferJoinOnConditions(
            @NotNull JoinModel join,
            @NotNull TableInfo tableInfo,
            @NotNull String alias) {
        String fieldName = join.getFieldName();

        // 策略 1：在主实体列映射中查找同名字段
        ColumnInfo matchedColumn = tableInfo.columnByField(fieldName);
        if (matchedColumn != null) {
            return List.of(
                    new Condition.ColumnComparison(
                            new ColumnRef(alias, "id", null, null, null, null, null),
                            new ColumnRef(null, matchedColumn.getColumnName(), null, null, null, null, null),
                            null));
        }

        // 策略 2：按命名策略推断外键列名
        String inferredFkColumn = NamingStrategy.INSTANCE.toSnakeCase(fieldName) + "_id";
        return List.of(
                new Condition.ColumnComparison(
                        new ColumnRef(alias, "id", null, null, null, null, null),
                        new ColumnRef(null, inferredFkColumn, null, null, null, null, null),
                        null));
    }

    // ------------------------------------------------------------------
    // 列名解析
    // ------------------------------------------------------------------

    /**
     * 解析实体字段名 → ColumnRef。
     *
     * 优先从 TableInfo 的列映射查找（按 fieldName）；找不到时按驼峰转下划线兜底。
     */
    private static ColumnRef resolveColumnRef(@NotNull String fieldName, @NotNull TableInfo tableInfo) {
        return new ColumnRef(null, resolveColumnName(fieldName, tableInfo), null, null, null, null, null);
    }

    /**
     * 解析实体字段名 → 数据库列名。
     */
    private static String resolveColumnName(@NotNull String fieldName, @NotNull TableInfo tableInfo) {
        ColumnInfo matched = tableInfo.columnByField(fieldName);
        return matched != null ? matched.getColumnName() : NamingStrategy.INSTANCE.toSnakeCase(fieldName);
    }

    // ------------------------------------------------------------------
    // 值表达式求值
    // ------------------------------------------------------------------

    /**
     * 把 PSI 表达式文本转为 SqlValue。
     *
     * 简单启发式：
     *  - 字符串字面量（单/双引号包裹）→ Literal（去引号，渲染时重新包裹）
     *  - 数字字面量 → Literal
     *  - null → Literal("NULL")
     *  - true/false → Literal
     *  - 其他 → Placeholder（运行时值）
     */
    private static SqlValue sqlValue(@NotNull String expression) {
        String trimmed = expression.trim();

        // null 字面量
        if (trimmed.equals("null")) return new SqlValue.Literal("NULL", "NULL");

        // 布尔
        if (trimmed.equals("true") || trimmed.equals("false")) return new SqlValue.Literal(trimmed, trimmed);

        // 字符串字面量（单引号或双引号，且不含 + 拼接）
        // 注意：含 + 的表达式（如 "%" + username + "%）是运行时拼接，应视为占位符
        if (!trimmed.contains("+")
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            String unquoted = trimmed.substring(1, trimmed.length() - 1);
            return new SqlValue.Literal(
                    "'" + unquoted.replace("'", "''") + "'",
                    unquoted);
        }

        // 数字（且不含 + 拼接）
        if (!trimmed.contains("+") && trimmed.matches("^-?\\d+(\\.\\d+)?[LlFfDd]?$")) {
            // 去除类型后缀 L/F/D
            String numeric = trimmed;
            while (numeric.length() > 0 && "LlFfDd".indexOf(numeric.charAt(numeric.length() - 1)) >= 0) {
                numeric = numeric.substring(0, numeric.length() - 1);
            }
            return new SqlValue.Literal(numeric, numeric);
        }

        // 运行时值 → 占位符（含变量引用、字符串拼接、方法调用等）
        return new SqlValue.Placeholder(trimmed);
    }
}
