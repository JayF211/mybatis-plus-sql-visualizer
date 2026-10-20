package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
import kotlin.Pair;

/**
 * SELECT 语句模型。
 *
 * @param table         目标表信息
 * @param selectColumns SELECT 列；为空表示全部列（SELECT * 语义，生成器展开为全列名）
 * @param tableAlias    主表别名（单表查询为 null；MPJ 联表默认 t）
 * @param joins         联表子句列表（MPJ）
 * @param distinct      是否 SELECT DISTINCT（清单 P05）
 * @param conditions    WHERE 条件列表（顶层以 AND 连接）
 * @param groupBy       GROUP BY 列
 * @param having        HAVING 条件（单个）
 * @param orderBy       ORDER BY 项列表
 * @param limit         分页子句（null 表示无 LIMIT）
 * @param rawFirst      SQL 最前片段（first("...") 官方 API，M15 增补）；非 null 时置于 SELECT 之前
 * @param rawAround     around 片段（around(firstSql, lastSql) 的 lastSql 段，MPJ 1.4.11+，T-M21-09）；
 *                      非 null 时渲染于 ORDER BY 之后、LIMIT 之前（MP 内部 LAST 段位置）
 * @param rawFirstParams  {@code rawFirst} 中占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）
 * @param rawAroundParams {@code rawAround} 中占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）
 * @param comment       SQL 注释（comment("...") 官方 API，M15 增补）；非 null 时追加到语句末尾
 * @param unions        UNION / UNION ALL 子查询（MPJ 官方 API，M15 增补）；按调用顺序追加到主语句之后
 * @param fromSubQuery  from 子查询主表（from(w -> ...)，MPJ 官方 API，T-M21-07）
 * @param fillClauses   fill 附加查询子句列表（MPJ 1.5.1+，T-MpjFill）：按调用顺序
 */
public final class SelectStatement extends SqlStatement {
    private final TableInfo table;
    private final List<ColumnRef> selectColumns;
    private final String tableAlias;
    private final List<JoinClause> joins;
    private final boolean distinct;
    private final List<Condition> conditions;
    private final List<ColumnRef> groupBy;
    private final Condition having;
    private final List<OrderByItem> orderBy;
    private final LimitClause limit;
    private final String rawFirst;
    private final String rawAround;
    private final List<String> rawFirstParams;
    private final List<String> rawAroundParams;
    private final String comment;
    private final List<UnionClause> unions;
    private final Pair<String, String> fromSubQuery;
    private final List<FillClause> fillClauses;

    public SelectStatement(TableInfo table, List<ColumnRef> selectColumns, String tableAlias, List<JoinClause> joins,
                           boolean distinct, List<Condition> conditions, List<ColumnRef> groupBy, Condition having,
                           List<OrderByItem> orderBy, LimitClause limit, String rawFirst, String rawAround,
                           List<String> rawFirstParams, List<String> rawAroundParams,
                           String comment, List<UnionClause> unions, Pair<String, String> fromSubQuery,
                           List<FillClause> fillClauses) {
        this.table = table;
        this.selectColumns = selectColumns;
        this.tableAlias = tableAlias;
        this.joins = joins;
        this.distinct = distinct;
        this.conditions = conditions;
        this.groupBy = groupBy;
        this.having = having;
        this.orderBy = orderBy;
        this.limit = limit;
        this.rawFirst = rawFirst;
        this.rawAround = rawAround;
        this.rawFirstParams = rawFirstParams == null ? List.of() : List.copyOf(rawFirstParams);
        this.rawAroundParams = rawAroundParams == null ? List.of() : List.copyOf(rawAroundParams);
        this.comment = comment;
        this.unions = unions;
        this.fromSubQuery = fromSubQuery;
        this.fillClauses = fillClauses;
    }

    public SelectStatement(TableInfo table, List<ColumnRef> selectColumns, String tableAlias, List<JoinClause> joins,
                           boolean distinct, List<Condition> conditions, List<ColumnRef> groupBy, Condition having,
                           List<OrderByItem> orderBy, LimitClause limit, String rawFirst, String rawAround,
                           String comment, List<UnionClause> unions, Pair<String, String> fromSubQuery,
                           List<FillClause> fillClauses) {
        this(table, selectColumns, tableAlias, joins, distinct, conditions, groupBy, having, orderBy, limit,
                rawFirst, rawAround, List.of(), List.of(), comment, unions, fromSubQuery, fillClauses);
    }

    public SelectStatement(TableInfo table) {
        this(table, List.of(), null, List.of(), false, List.of(), List.of(), null, List.of(), null, null, null, null,
                List.of(), null, null);
    }

    public SelectStatement(TableInfo table,
                           LimitClause limit) {
        this(table, List.of(), null, List.of(), false, List.of(), List.of(), null, List.of(), limit, null, null, null,
                List.of(), null, null);
    }

    public SelectStatement(TableInfo table,
                           List<JoinClause> joins,
                           List<Condition> conditions) {
        this(table, List.of(), null, joins, false, conditions, List.of(), null, List.of(), null, null, null, null,
                List.of(), null, null);
    }

    public SelectStatement(TableInfo table,
                           List<ColumnRef> selectColumns,
                           String tableAlias,
                           List<Condition> conditions) {
        this(table, selectColumns, tableAlias, List.of(), false, conditions, List.of(), null, List.of(), null, null,
                null, null, List.of(), null, null);
    }

    @Override
    public TableInfo getTable() {
        return table;
    }

    public List<ColumnRef> getSelectColumns() {
        return selectColumns;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public List<JoinClause> getJoins() {
        return joins;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public boolean getDistinct() {
        return distinct;
    }

    @Override
    public List<Condition> getConditions() {
        return conditions;
    }

    public List<ColumnRef> getGroupBy() {
        return groupBy;
    }

    public Condition getHaving() {
        return having;
    }

    public List<OrderByItem> getOrderBy() {
        return orderBy;
    }

    public LimitClause getLimit() {
        return limit;
    }

    public String getRawFirst() {
        return rawFirst;
    }

    public String getRawAround() {
        return rawAround;
    }

    public List<String> getRawFirstParams() {
        return rawFirstParams;
    }

    public List<String> getRawAroundParams() {
        return rawAroundParams;
    }

    public String getComment() {
        return comment;
    }

    public List<UnionClause> getUnions() {
        return unions;
    }

    public Pair<String, String> getFromSubQuery() {
        return fromSubQuery;
    }

    public List<FillClause> getFillClauses() {
        return fillClauses;
    }

    public SelectStatement copy(TableInfo table, List<ColumnRef> selectColumns, String tableAlias, List<JoinClause> joins,
                                boolean distinct, List<Condition> conditions, List<ColumnRef> groupBy, Condition having,
                                List<OrderByItem> orderBy, LimitClause limit, String rawFirst, String rawAround,
                                List<String> rawFirstParams, List<String> rawAroundParams,
                                String comment, List<UnionClause> unions, Pair<String, String> fromSubQuery,
                                List<FillClause> fillClauses) {
        return new SelectStatement(table, selectColumns, tableAlias, joins, distinct, conditions, groupBy, having,
                orderBy, limit, rawFirst, rawAround, rawFirstParams, rawAroundParams, comment, unions, fromSubQuery,
                fillClauses);
    }

    public SelectStatement copy(TableInfo table, List<ColumnRef> selectColumns, String tableAlias, List<JoinClause> joins,
                                boolean distinct, List<Condition> conditions, List<ColumnRef> groupBy, Condition having,
                                List<OrderByItem> orderBy, LimitClause limit, String rawFirst, String rawAround,
                                String comment, List<UnionClause> unions, Pair<String, String> fromSubQuery,
                                List<FillClause> fillClauses) {
        return new SelectStatement(table, selectColumns, tableAlias, joins, distinct, conditions, groupBy, having,
                orderBy, limit, rawFirst, rawAround, rawFirstParams, rawAroundParams, comment, unions, fromSubQuery,
                fillClauses);
    }

    public SelectStatement copy(List<Condition> conditions) {
        return new SelectStatement(table, selectColumns, tableAlias, joins, distinct, conditions, groupBy, having,
                orderBy, limit, rawFirst, rawAround, rawFirstParams, rawAroundParams, comment, unions, fromSubQuery,
                fillClauses);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SelectStatement)) {
            return false;
        }
        SelectStatement that = (SelectStatement) o;
        return distinct == that.distinct
                && Objects.equals(table, that.table)
                && Objects.equals(selectColumns, that.selectColumns)
                && Objects.equals(tableAlias, that.tableAlias)
                && Objects.equals(joins, that.joins)
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(groupBy, that.groupBy)
                && Objects.equals(having, that.having)
                && Objects.equals(orderBy, that.orderBy)
                && Objects.equals(limit, that.limit)
                && Objects.equals(rawFirst, that.rawFirst)
                && Objects.equals(rawAround, that.rawAround)
                && Objects.equals(rawFirstParams, that.rawFirstParams)
                && Objects.equals(rawAroundParams, that.rawAroundParams)
                && Objects.equals(comment, that.comment)
                && Objects.equals(unions, that.unions)
                && Objects.equals(fromSubQuery, that.fromSubQuery)
                && Objects.equals(fillClauses, that.fillClauses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, selectColumns, tableAlias, joins, distinct, conditions, groupBy, having, orderBy,
                limit, rawFirst, rawAround, rawFirstParams, rawAroundParams, comment, unions, fromSubQuery, fillClauses);
    }

    @Override
    public String toString() {
        return "SelectStatement(table=" + table + ", selectColumns=" + selectColumns + ", tableAlias=" + tableAlias
                + ", joins=" + joins + ", distinct=" + distinct + ", conditions=" + conditions + ", groupBy=" + groupBy
                + ", having=" + having + ", orderBy=" + orderBy + ", limit=" + limit + ", rawFirst=" + rawFirst
                + ", rawAround=" + rawAround + ", rawFirstParams=" + rawFirstParams + ", rawAroundParams="
                + rawAroundParams + ", comment=" + comment + ", unions=" + unions
                + ", fromSubQuery=" + fromSubQuery + ", fillClauses=" + fillClauses + ")";
    }
}
