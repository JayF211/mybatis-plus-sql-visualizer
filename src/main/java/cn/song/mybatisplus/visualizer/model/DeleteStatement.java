package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * DELETE 语句模型（F-10，LambdaDeleteWrapper 转换产物）。
 *
 * @param table          目标表信息
 * @param conditions     WHERE 条件（为空表示全表删除，UI 层需红色警示）
 * @param deleteTargets  删除目标表别名列表（T-M21-08：DeleteJoinWrapper 的 delete(Class...)/delete(String...)/
 *                       deleteAll() 控制删除哪些表，如 listOf("t", "t1") → DELETE t, t1 FROM ...；空列表走既有
 *                       DELETE FROM 表名 形态，单表删除不受影响）
 * @param tableAlias     主表别名（T-M21-08：DeleteJoinWrapper 渲染 DELETE t FROM 表名 t，FROM 后带主表别名；
 *                       单表删除为 null）
 * @param joins          联表子句（T-M21-08：DeleteJoinWrapper 渲染于 FROM 之后、WHERE 之前）
 * @param limit          尾段（O-8 缺陷 C，T-StrWrapperDeleteWhere）：删除 wrapper 上 `last("LIMIT n")` 的
 *                       parseLimit 产物——MP 语义 MySQL `DELETE ... LIMIT n`；渲染与否由方言
 *                       （{@link cn.song.mybatisplus.visualizer.sql.Dialect#supportsDeleteLimit}）决定，
 *                       不支持的方言静默省略（与分页方言策略一致）
 */
public final class DeleteStatement extends SqlStatement {
    private final TableInfo table;
    private final List<Condition> conditions;
    private final List<String> deleteTargets;
    private final String tableAlias;
    private final List<JoinClause> joins;
    private final LimitClause limit;

    public DeleteStatement(TableInfo table, List<Condition> conditions, List<String> deleteTargets, String tableAlias,
                           List<JoinClause> joins) {
        this(table, conditions, deleteTargets, tableAlias, joins, null);
    }

    public DeleteStatement(TableInfo table, List<Condition> conditions, List<String> deleteTargets, String tableAlias,
                           List<JoinClause> joins, LimitClause limit) {
        this.table = table;
        this.conditions = conditions;
        this.deleteTargets = deleteTargets;
        this.tableAlias = tableAlias;
        this.joins = joins;
        this.limit = limit;
    }

    public DeleteStatement(TableInfo table) {
        this(table, List.of(), List.of(), null, List.of(), null);
    }

    public DeleteStatement(TableInfo table,
                           List<Condition> conditions) {
        this(table, conditions, List.of(), null, List.of(), null);
    }

    @Override
    public TableInfo getTable() {
        return table;
    }

    @Override
    public List<Condition> getConditions() {
        return conditions;
    }

    public List<String> getDeleteTargets() {
        return deleteTargets;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public List<JoinClause> getJoins() {
        return joins;
    }

    public LimitClause getLimit() {
        return limit;
    }

    public DeleteStatement copy(TableInfo table, List<Condition> conditions, List<String> deleteTargets,
                                String tableAlias, List<JoinClause> joins) {
        return new DeleteStatement(table, conditions, deleteTargets, tableAlias, joins, limit);
    }

    public DeleteStatement copy(TableInfo table, List<Condition> conditions, List<String> deleteTargets,
                                String tableAlias, List<JoinClause> joins, LimitClause limit) {
        return new DeleteStatement(table, conditions, deleteTargets, tableAlias, joins, limit);
    }

    public DeleteStatement copy(List<Condition> conditions) {
        return new DeleteStatement(table, conditions, deleteTargets, tableAlias, joins, limit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeleteStatement)) {
            return false;
        }
        DeleteStatement that = (DeleteStatement) o;
        return Objects.equals(table, that.table)
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(deleteTargets, that.deleteTargets)
                && Objects.equals(tableAlias, that.tableAlias)
                && Objects.equals(joins, that.joins)
                && Objects.equals(limit, that.limit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, conditions, deleteTargets, tableAlias, joins, limit);
    }

    @Override
    public String toString() {
        return "DeleteStatement(table=" + table + ", conditions=" + conditions + ", deleteTargets=" + deleteTargets
                + ", tableAlias=" + tableAlias + ", joins=" + joins + ", limit=" + limit + ")";
    }
}
