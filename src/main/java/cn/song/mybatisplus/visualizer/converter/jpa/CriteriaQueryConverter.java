package cn.song.mybatisplus.visualizer.converter.jpa;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.JoinClause;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SelectStatement;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.psi.jpa.AggregateInfo;
import cn.song.mybatisplus.visualizer.psi.jpa.CaseWhen;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaOperation;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryModel;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaSetClause;
import cn.song.mybatisplus.visualizer.psi.jpa.ExpressionModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.GroupConnector;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JoinModel;
import cn.song.mybatisplus.visualizer.psi.jpa.OrderByModel;
import cn.song.mybatisplus.visualizer.psi.jpa.PredicateModel;
import cn.song.mybatisplus.visualizer.psi.jpa.SelectColumn;
import cn.song.mybatisplus.visualizer.psi.jpa.SubqueryModel;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.psi.PsiClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CriteriaQuery → SqlStatement 转换器（F-JPA-02，T-J4/T-J6/T-J7/T-J17）。
 *
 * 将 CriteriaQueryModel 中间表示转换为 SelectStatement / UpdateStatement / DeleteStatement，
 * 供 cn.song.mybatisplus.visualizer.sql.SqlGenerator 渲染为 SQL。
 *
 * CriteriaBuilder 方法 → Condition 映射：
 *  - cb.equal(a, b)              → Condition.Simple(col, "=", value)
 *  - cb.notEqual(a, b)           → Condition.Simple(col, "!=", value)
 *  - cb.greaterThan/gt(a, b)     → Condition.Simple(col, ">", value)
 *  - cb.greaterThanOrEqualTo/ge  → Condition.Simple(col, ">=", value)
 *  - cb.lessThan/lt(a, b)        → Condition.Simple(col, "<", value)
 *  - cb.lessThanOrEqualTo/le     → Condition.Simple(col, "<=", value)
 *  - cb.between(a, lo, hi)       → Condition.Between(col, false, lo, hi)
 *  - cb.isNull(a)                → Condition.Unary(col, "IS NULL")
 *  - cb.isNotNull(a)             → Condition.Unary(col, "IS NOT NULL")
 *  - cb.like(a, pattern)         → Condition.Like(col, false, pattern, NONE；Criteria 模式自带 %)
 *  - cb.like(a, p, escape)       → Condition.Raw("col LIKE p ESCAPE e")（Condition 无 escape 字段）
 *  - cb.notLike(a, pattern)      → Condition.Like(col, true, pattern, NONE)
 *  - cb.in(a).value(b)           → Condition.In(col, false, listOf(b))
 *  - cb.isTrue/isFalse(a)        → Condition.Simple(col, "=", TRUE/FALSE)
 *  - cb.conjunction/disjunction  → Condition.Raw("1 = 1" / "1 = 0")
 *  - cb.exists(sq)               → Condition.Raw("EXISTS (SELECT ...)")
 *  - path.get("x").in(sq)        → Condition.Raw("x IN (SELECT ...)")
 *  - cb.and(p1, p2)              → Condition.Group(AND, listOf(p1, p2))
 *  - cb.or(p1, p2)               → Condition.Group(OR, listOf(p1, p2))
 *  - cb.not(p)                   → Condition.Group(AND, listOf(p), negated=true)
 *  - 函数表达式左值（LOWER/ABS/CASE/算术等）→ ExpressionComparison → Condition.Simple 列位放表达式文本
 *
 * UPDATE / DELETE（T-J6）：CriteriaUpdate.set(...) → UpdateStatement.sets；
 * CriteriaDelete → DeleteStatement。
 *
 * 无状态单例，纯函数逻辑，可独立单测；不支持的能力以 Raw/注释降级，绝不抛异常。
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
     * @return 按 model.operation 返回 Select / Update / Delete 语句模型；
     *         无法解析实体时返回全 Raw 降级的 SELECT
     */
    public static SqlStatement convert(@NotNull CriteriaQueryModel model, @Nullable TableInfo overrideTable) {
        TableInfo tableInfo = overrideTable != null ? overrideTable : resolveTableInfo(model);
        switch (model.getOperation()) {
            case UPDATE:
                return convertUpdate(model, tableInfo);
            case DELETE:
                return new DeleteStatement(
                        tableInfo,
                        convertPredicates(model.getPredicates(), tableInfo));
            case SELECT:
                return convertSelect(model, tableInfo);
            default:
                throw new IllegalArgumentException("未知的 CriteriaOperation: " + model.getOperation());
        }
    }

    private static SelectStatement convertSelect(@NotNull CriteriaQueryModel model, @NotNull TableInfo tableInfo) {
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
                model.isDistinct(),
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

    /** CriteriaUpdate → UpdateStatement（T-J6）：set 子句转 SetClause，where 谓词转 conditions。 */
    private static UpdateStatement convertUpdate(@NotNull CriteriaQueryModel model, @NotNull TableInfo tableInfo) {
        List<Condition> conditions = convertPredicates(model.getPredicates(), tableInfo);
        List<SetClause> sets = new ArrayList<>();
        for (CriteriaSetClause clause : model.getSetClauses()) {
            sets.add(new SetClause(
                    new ColumnRef(null, resolveColumnName(clause.getFieldName(), tableInfo)),
                    sqlValue(clause.getValueExpression() != null ? clause.getValueExpression() : "null")));
        }
        return new UpdateStatement(tableInfo, sets, conditions);
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

    /** 子查询实体表信息（仅类名可推断，列映射为空，按命名策略兜底）。 */
    private static TableInfo resolveSubTableInfo(@NotNull SubqueryModel sub) {
        String className = sub.getEntityClassName();
        if (className == null) {
            return new TableInfo("UnknownEntity", "unknown_entity", List.of(), null, null, null, List.of());
        }
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
     * 规则：
     *  - 空列表 → 空（converter 层全列语义，生成器展开为 SELECT *）
     *  - 普通列 → 按字段名查找实体列映射，找不到时按命名策略兜底
     *  - 聚合列 → ColumnRef.function 设置为聚合函数名：
     *    - COUNT(*) → function="COUNT", columnName="*"
     *    - SUM(field) → function="SUM", columnName=解析后列名
     *    - countDistinct → rawExpr="COUNT(DISTINCT col)"（T-J17）
     *    - sumAsLong/sumAsDouble → rawExpr="SUM(CAST(col AS BIGINT/DOUBLE))"（T-J17）
     *    - 聚合内层复杂表达式 → rawExpr="AVG(COALESCE(age, 0))" 等（T-J17）
     *  - 表达式列（CASE/COALESCE/CONCAT/算术等）→ rawExpr（T-J17）
     */
    private static List<ColumnRef> convertSelectColumns(
            @NotNull List<SelectColumn> columns,
            @NotNull TableInfo tableInfo) {
        if (columns.isEmpty()) return List.of();
        return columns.stream().map(sel -> {
            // 聚合函数处理
            if (sel.getAggregate() != null) {
                AggregateInfo agg = sel.getAggregate();
                String compositeExpr;
                if (agg.getCastType() != null) {
                    String col = agg.getFieldName() != null ? resolveColumnName(agg.getFieldName(), tableInfo) : "*";
                    compositeExpr = agg.getFunction() + "(CAST(" + col + " AS " + agg.getCastType() + "))";
                } else if (agg.isDistinct()) {
                    String col = agg.getFieldName() != null ? resolveColumnName(agg.getFieldName(), tableInfo) : "*";
                    compositeExpr = agg.getFunction() + "(DISTINCT " + col + ")";
                } else if (agg.getExpression() != null) {
                    compositeExpr = agg.getFunction() + "(" + renderExpression(agg.getExpression(), tableInfo) + ")";
                } else {
                    compositeExpr = null;
                }
                if (compositeExpr != null) {
                    return new ColumnRef(null, compositeExpr, sel.getAlias(), null, compositeExpr);
                }
                String columnName;
                if (agg.getFieldName() == null) {
                    columnName = "*"; // COUNT(*) 语义
                } else {
                    columnName = resolveColumnName(agg.getFieldName(), tableInfo);
                }
                return new ColumnRef(null, columnName, sel.getAlias(), agg.getFunction(), null, null, null);
            }

            // 复杂表达式列（CASE / COALESCE / CONCAT / (a + b) 等，T-J17）
            if (sel.getExpression() != null) {
                String exprText = renderExpression(sel.getExpression(), tableInfo);
                return new ColumnRef(null, exprText, sel.getAlias(), null, exprText);
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
            String op = s.isNegated() ? "NOT LIKE" : "LIKE";
            if (s.getEscapeChar() != null) {
                // escapeChar 重载：Condition 模型无 escape 字段，整段 Raw（T-J17）
                return new Condition.Raw(
                        column.getQualifiedName() + " " + op + " " + valueToText(s.getPatternExpression())
                                + " ESCAPE " + valueToText(s.getEscapeChar()));
            }
            // Criteria 的 LIKE 模式自带通配符，位置语义为 NONE（不二次包裹 %）
            return new Condition.Like(
                    column,
                    s.isNegated(),
                    sqlValue(s.getPatternExpression()),
                    LikePosition.NONE,
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
        } else if (predicate instanceof PredicateModel.ExpressionComparison s) {
            // 表达式比较（T-J17）：LOWER(x) = 'a' / ABS(x) > 1 / LOWER(x) IS NULL 等
            return convertExpressionComparison(s, tableInfo);
        } else if (predicate instanceof PredicateModel.ExpressionBetween s) {
            // 表达式 BETWEEN（T-J17）：Condition 无对应节点，整段 Raw
            return new Condition.Raw(
                    renderExpression(s.getLeft(), tableInfo) + " BETWEEN "
                            + expressionToText(s.getLo(), tableInfo) + " AND " + expressionToText(s.getHi(), tableInfo));
        } else if (predicate instanceof PredicateModel.BooleanConstant s) {
            // 布尔常量（T-J17）：conjunction=恒真 / disjunction=恒假
            return new Condition.Raw(s.isValue() ? "1 = 1" : "1 = 0");
        } else if (predicate instanceof PredicateModel.ColumnEquals s) {
            // 列对列比较（Join.on / root 与 join 列互比，T-J7）
            return new Condition.ColumnComparison(
                    new ColumnRef(null, resolveColumnName(s.getLeftFieldName(), tableInfo)),
                    new ColumnRef(null, resolveColumnName(s.getRightFieldName(), tableInfo)),
                    null);
        } else if (predicate instanceof PredicateModel.Exists s) {
            // EXISTS 子查询（T-J17/J13）：有界渲染，降级注释
            String subSql = renderSubQuery(s.getSubQuery());
            if (subSql != null) {
                return new Condition.Raw((s.isNegated() ? "NOT " : "") + "EXISTS " + subSql);
            }
            return new Condition.Raw(degradedSubqueryComment(s.getSubQuery()));
        } else if (predicate instanceof PredicateModel.InSubQuery s) {
            // 列 IN 子查询（T-J17/J13）
            String subSql = renderSubQuery(s.getSubQuery());
            if (subSql != null) {
                String col = resolveColumnName(s.getFieldName(), tableInfo);
                String op = s.isNegated() ? "NOT IN" : "IN";
                return new Condition.Raw(col + " " + op + " " + subSql);
            }
            return new Condition.Raw(degradedSubqueryComment(s.getSubQuery()));
        } else if (predicate instanceof PredicateModel.Raw s) {
            return new Condition.Raw(s.getExpressionText(), null);
        }
        throw new IllegalArgumentException("未知的 PredicateModel 类型: " + predicate);
    }

    /** 表达式比较 → Condition（IS NULL 走 Unary；值右值走 Simple；其余整段 Raw）。 */
    private static Condition convertExpressionComparison(
            @NotNull PredicateModel.ExpressionComparison predicate,
            @NotNull TableInfo tableInfo) {
        String leftSql = renderExpression(predicate.getLeft(), tableInfo);
        switch (predicate.getOp()) {
            case "IS NULL":
            case "IS NOT NULL":
                return new Condition.Unary(new ColumnRef(null, leftSql), predicate.getOp(), null);
            case "LIKE":
            case "NOT LIKE":
                return new Condition.Simple(
                        new ColumnRef(null, leftSql),
                        predicate.getOp(),
                        exprToSqlValue(predicate.getRight()),
                        null);
            default: {
                ExpressionModel right = predicate.getRight();
                if (right instanceof ExpressionModel.Value v) {
                    return new Condition.Simple(
                            new ColumnRef(null, leftSql),
                            predicate.getOp(),
                            sqlValue(v.getExpressionText()),
                            null);
                }
                // 右值也是表达式（列/函数/CASE）→ Condition 无对应节点，整段 Raw
                return new Condition.Raw(leftSql + " " + predicate.getOp() + " " + expressionToText(right, tableInfo));
            }
        }
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
     * 规则（T-J7）：
     *  - 显式 ON（Join.on(...)）非空时优先渲染，跳过外键推断；
     *    列别名归属按 get 调用接收者变量判定（join 变量 → join 别名，root/未知 → 主表）
     *  - 无显式 ON → 既有外键推断：alias.id = root_fk_column
     *  - fetch 调用与 join 同形渲染（EAGER 抓取合并入主查询的静态近似）
     */
    private static List<JoinClause> convertJoins(
            @NotNull List<JoinModel> joins,
            @NotNull TableInfo tableInfo) {
        List<JoinClause> result = new ArrayList<>();
        for (JoinModel join : joins) {
            String entityClassName = join.getEntityClassName() != null ? join.getEntityClassName() : join.getFieldName();
            String tableName = JpaEntityResolver.inferDecoratedTableName(entityClassName);
            // 别名优先级：显式别名 > join 返回值变量名 > 关联属性名首字母小写
            String fieldName = join.getFieldName();
            String alias = join.getAlias() != null
                    ? join.getAlias()
                    : (join.getVariableName() != null
                            ? join.getVariableName()
                            : fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1));

            List<Condition> onConditions;
            if (!join.getOnPredicates().isEmpty()) {
                onConditions = new ArrayList<>();
                for (PredicateModel on : join.getOnPredicates()) {
                    onConditions.add(convertOnPredicate(on, tableInfo, join, alias));
                }
            } else {
                // JOIN ON 条件推断
                onConditions = inferJoinOnConditions(join, tableInfo, alias);
            }

            result.add(new JoinClause(join.getJoinType(), tableName, alias, onConditions, null, null, null, null));
        }
        return result;
    }

    /** 显式 ON 谓词转换：ColumnEquals 按变量归属补表别名，其余谓词走通用转换。 */
    private static Condition convertOnPredicate(
            @NotNull PredicateModel predicate,
            @NotNull TableInfo tableInfo,
            @NotNull JoinModel join,
            @NotNull String joinAlias) {
        if (predicate instanceof PredicateModel.ColumnEquals s) {
            return new Condition.ColumnComparison(
                    new ColumnRef(
                            aliasForVariable(s.getLeftVariable(), join, joinAlias),
                            resolveColumnName(s.getLeftFieldName(), tableInfo)),
                    new ColumnRef(
                            aliasForVariable(s.getRightVariable(), join, joinAlias),
                            resolveColumnName(s.getRightFieldName(), tableInfo)),
                    null);
        }
        return convertPredicate(predicate, tableInfo);
    }

    /** get 接收者变量 → 表别名：join 变量 → join 别名；root/未知 → 主表（无别名）。 */
    @Nullable
    private static String aliasForVariable(@Nullable String variable, @NotNull JoinModel join, @NotNull String joinAlias) {
        if (variable == null) return null;
        if (variable.equals(join.getVariableName())) return joinAlias;
        return null;
    }

    /**
     * 推断 CriteriaQuery JOIN 的 ON 条件（显式 ON 缺省时的回退）。
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
    // 子查询渲染（T-J17/J13 有界实现）
    // ------------------------------------------------------------------

    /**
     * 渲染子查询为 `(SELECT col FROM t WHERE ...)` 文本。
     *
     * 有界：degradedReason 非空（correlate / 多层嵌套）或谓词无法全部静态渲染时返回 null，
     * 由调用方降级注释。占位符以 `?` 文本呈现（不参与面板参数收集，属已知边界）。
     */
    @Nullable
    private static String renderSubQuery(@NotNull SubqueryModel sub) {
        if (sub.getDegradedReason() != null) return null;
        TableInfo subTable = resolveSubTableInfo(sub);
        String selectColumn = sub.getSelectFieldName() != null
                ? resolveColumnName(sub.getSelectFieldName(), subTable)
                : "*";
        List<String> whereParts = new ArrayList<>();
        for (PredicateModel p : sub.getPredicates()) {
            whereParts.add(predicateToSqlText(p, subTable));
        }
        // 有界：谓词无法全部渲染（嵌套子查询等）→ 整体降级
        if (whereParts.stream().anyMatch(Objects::isNull)) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("(SELECT ").append(selectColumn).append(" FROM ").append(subTable.getTableName());
        if (!whereParts.isEmpty()) {
            sb.append(" WHERE ").append(whereParts.stream().filter(Objects::nonNull).collect(Collectors.joining(" AND ")));
        }
        sb.append(")");
        return sb.toString();
    }

    /** 不可静态渲染子查询的注释降级文本。 */
    private static String degradedSubqueryComment(@NotNull SubqueryModel sub) {
        String reason = sub.getDegradedReason() != null ? "：" + sub.getDegradedReason() : "";
        return "/* 子查询暂不支持静态解析" + reason + " */";
    }

    /**
     * 谓词 → SQL 文本（子查询 WHERE 渲染用）。
     * EXISTS / IN 子查询节点在此返回 null（子查询内不再嵌套子查询——有界边界）。
     */
    @Nullable
    private static String predicateToSqlText(@NotNull PredicateModel predicate, @NotNull TableInfo tableInfo) {
        if (predicate instanceof PredicateModel.Simple s) {
            return resolveColumnName(s.getFieldName(), tableInfo)
                    + " " + s.getOp() + " " + valueToText(s.getValueExpression());
        } else if (predicate instanceof PredicateModel.IsNull s) {
            return resolveColumnName(s.getFieldName(), tableInfo)
                    + (s.isNegated() ? " IS NOT NULL" : " IS NULL");
        } else if (predicate instanceof PredicateModel.Between s) {
            return resolveColumnName(s.getFieldName(), tableInfo) + " BETWEEN "
                    + valueToText(s.getLoExpression()) + " AND " + valueToText(s.getHiExpression());
        } else if (predicate instanceof PredicateModel.Like s) {
            String op = s.isNegated() ? "NOT LIKE" : "LIKE";
            String escape = s.getEscapeChar() != null ? " ESCAPE " + valueToText(s.getEscapeChar()) : "";
            return resolveColumnName(s.getFieldName(), tableInfo) + " " + op + " "
                    + valueToText(s.getPatternExpression()) + escape;
        } else if (predicate instanceof PredicateModel.In s) {
            String op = s.isNegated() ? "NOT IN" : "IN";
            String values = s.getValueExpressions().stream()
                    .map(CriteriaQueryConverter::valueToText)
                    .collect(Collectors.joining(", "));
            return resolveColumnName(s.getFieldName(), tableInfo) + " " + op + " (" + values + ")";
        } else if (predicate instanceof PredicateModel.ColumnEquals s) {
            return resolveColumnName(s.getLeftFieldName(), tableInfo) + " " + s.getOp() + " "
                    + resolveColumnName(s.getRightFieldName(), tableInfo);
        } else if (predicate instanceof PredicateModel.BooleanConstant s) {
            return s.isValue() ? "1 = 1" : "1 = 0";
        } else if (predicate instanceof PredicateModel.ExpressionComparison s) {
            String left = renderExpression(s.getLeft(), tableInfo);
            if ("IS NULL".equals(s.getOp()) || "IS NOT NULL".equals(s.getOp())) {
                return left + " " + s.getOp();
            }
            return left + " " + s.getOp() + " " + expressionToText(s.getRight(), tableInfo);
        } else if (predicate instanceof PredicateModel.ExpressionBetween s) {
            return renderExpression(s.getLeft(), tableInfo) + " BETWEEN "
                    + expressionToText(s.getLo(), tableInfo) + " AND " + expressionToText(s.getHi(), tableInfo);
        } else if (predicate instanceof PredicateModel.Group s) {
            List<String> children = new ArrayList<>();
            for (PredicateModel child : s.getChildren()) {
                children.add(predicateToSqlText(child, tableInfo));
            }
            if (children.stream().anyMatch(Objects::isNull)) return null;
            String joined = children.stream().filter(Objects::nonNull)
                    .collect(Collectors.joining(" " + s.getConnector().name() + " "));
            if (joined.isEmpty()) return null;
            return s.isNegated() ? "NOT (" + joined + ")" : "(" + joined + ")";
        } else if (predicate instanceof PredicateModel.Raw s) {
            return s.getExpressionText();
        }
        // 子查询内嵌子查询（Exists / InSubQuery）：有界边界，返回 null 令外层整体降级
        return null;
    }

    // ------------------------------------------------------------------
    // 表达式渲染（T-J17）
    // ------------------------------------------------------------------

    /**
     * ExpressionModel → SQL 文本。
     *
     * 函数标记映射：
     *  - ADD/SUB/MUL/DIV → (a + b) 等括号中缀；MOD → MOD(a, b)；NEG → (-a)
     *  - CAST → CAST(a AS 类型)（modifier 携带目标类型）
     *  - TRIM → TRIM([规格 FROM] a)（modifier 携带 Trimspec）
     *  - COUNT → COUNT(a)，无参时 COUNT(*)
     *  - NOT → NOT (a)
     *  - 其余原样 NAME(a, b, ...)
     */
    private static String renderExpression(@NotNull ExpressionModel expr, @NotNull TableInfo tableInfo) {
        if (expr instanceof ExpressionModel.Column c) {
            return resolveColumnName(c.getFieldName(), tableInfo);
        } else if (expr instanceof ExpressionModel.Value v) {
            return valueToText(v.getExpressionText());
        } else if (expr instanceof ExpressionModel.Raw r) {
            return r.getExpressionText();
        } else if (expr instanceof ExpressionModel.Function f) {
            List<String> args = f.getArgs().stream()
                    .map(a -> renderExpression(a, tableInfo))
                    .toList();
            switch (f.getFunction()) {
                case "ADD":
                    return "(" + argOrEmpty(args, 0) + " + " + argOrEmpty(args, 1) + ")";
                case "SUB":
                    return "(" + argOrEmpty(args, 0) + " - " + argOrEmpty(args, 1) + ")";
                case "MUL":
                    return "(" + argOrEmpty(args, 0) + " * " + argOrEmpty(args, 1) + ")";
                case "DIV":
                    return "(" + argOrEmpty(args, 0) + " / " + argOrEmpty(args, 1) + ")";
                case "MOD":
                    return "MOD(" + argOrEmpty(args, 0) + ", " + argOrEmpty(args, 1) + ")";
                case "NEG":
                    return "(-" + argOrEmpty(args, 0) + ")";
                case "NOT":
                    return "NOT (" + String.join(", ", args) + ")";
                case "CAST":
                    return "CAST(" + argOrEmpty(args, 0) + " AS "
                            + (f.getModifier() != null ? f.getModifier() : "VARCHAR") + ")";
                case "TRIM":
                    return f.getModifier() != null
                            ? "TRIM(" + f.getModifier() + " FROM " + argOrEmpty(args, 0) + ")"
                            : "TRIM(" + argOrEmpty(args, 0) + ")";
                case "COUNT": {
                    String inner = String.join(", ", args);
                    return "COUNT(" + (inner.isEmpty() ? "*" : inner) + ")";
                }
                default:
                    return f.getFunction() + "(" + String.join(", ", args) + ")";
            }
        } else if (expr instanceof ExpressionModel.Case c) {
            String caseSql = renderCase(c, tableInfo);
            return caseSql != null ? caseSql : "/* CASE 表达式暂不支持静态渲染 */";
        }
        throw new IllegalArgumentException("未知的 ExpressionModel 类型: " + expr);
    }

    /** Kotlin getOrElse 缺省语义：下标越界返回空串。 */
    private static String argOrEmpty(List<String> args, int index) {
        return index < args.size() ? args.get(index) : "";
    }

    /** CASE 表达式渲染；条件/结果无法渲染时返回 null（调用方降级注释）。 */
    @Nullable
    private static String renderCase(@NotNull ExpressionModel.Case expr, @NotNull TableInfo tableInfo) {
        String base = expr.getSimpleExpr() != null ? renderExpression(expr.getSimpleExpr(), tableInfo) : null;
        List<String> parts = new ArrayList<>();
        for (CaseWhen caseWhen : expr.getWhens()) {
            String conditionSql;
            if (base != null) {
                conditionSql = caseWhen.getCondition() != null
                        ? renderExpression(caseWhen.getCondition(), tableInfo)
                        : null;
            } else {
                conditionSql = caseWhen.getPredicate() != null
                        ? predicateToSqlText(caseWhen.getPredicate(), tableInfo)
                        : null;
            }
            if (conditionSql == null) return null;
            String resultSql = caseWhen.getResult() != null ? renderExpression(caseWhen.getResult(), tableInfo) : null;
            if (resultSql == null) return null;
            parts.add("WHEN " + conditionSql + " THEN " + resultSql);
        }
        if (parts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("CASE ");
        if (base != null) sb.append(base).append(" ");
        sb.append(String.join(" ", parts));
        if (expr.getOtherwise() != null) {
            sb.append(" ELSE ").append(renderExpression(expr.getOtherwise(), tableInfo));
        }
        sb.append(" END");
        return sb.toString();
    }

    /** 值源码文本 → SQL 文本（字面量原样含引号；变量/占位符 → `?`）。 */
    private static String valueToText(@NotNull String expression) {
        SqlValue value = sqlValue(expression);
        if (value instanceof SqlValue.Literal lit) return lit.getRaw();
        return "?";
    }

    /** 表达式 → SQL 文本（Value 走值求值，其余走表达式渲染）。 */
    private static String expressionToText(@NotNull ExpressionModel expr, @NotNull TableInfo tableInfo) {
        if (expr instanceof ExpressionModel.Value v) return valueToText(v.getExpressionText());
        return renderExpression(expr, tableInfo);
    }

    /** 表达式右值 → SqlValue（Simple/Like 条件的参数面板语义保留）。 */
    private static SqlValue exprToSqlValue(@NotNull ExpressionModel expr) {
        if (expr instanceof ExpressionModel.Value v) return sqlValue(v.getExpressionText());
        return new SqlValue.Placeholder("expression");
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
     *  - 数字字面量 → Literal（去 L/F/D 类型后缀）
     *  - null → Literal("NULL")
     *  - true/false/TRUE/FALSE → Literal（统一大写布尔字面量）
     *  - 其他 → Placeholder（运行时值）
     */
    private static SqlValue sqlValue(@NotNull String expression) {
        String trimmed = expression.trim();

        // null 字面量
        if (trimmed.equals("null")) return new SqlValue.Literal("NULL", "NULL");

        // 布尔（isTrue/isFalse 产物为 TRUE/FALSE，统一大写）
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return new SqlValue.Literal(trimmed.toUpperCase(), trimmed.toUpperCase());
        }

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
        if (!trimmed.contains("+") && CQC_NUMERIC_LITERAL.matcher(trimmed).matches()) {
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

    // T-Perf-23：函数体内 Regex(...) 提为常量（热路径免重复编译 Pattern）
    private static final Pattern CQC_NUMERIC_LITERAL = Pattern.compile("^-?\\d+(\\.\\d+)?[LlFfDd]?$");
}
