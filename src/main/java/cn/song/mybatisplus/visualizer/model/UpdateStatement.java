package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * UPDATE 语句模型（F-09，LambdaUpdateWrapper 转换产物）。
 *
 * @param table       目标表信息
 * @param sets        SET 赋值列表
 * @param conditions  WHERE 条件（为空表示全表更新，UI 层需警示）
 * @param rawTail     语句尾部原生透传（U05，T-M15-04）：更新 wrapper 上 last("AND (... >= 0)") 等
 *                    非 limit 形态，原样追加到 SQL 末尾（防超发兜底条件）
 * @param tableAlias  主表别名（T-M21-08：UpdateJoinWrapper 联表更新渲染 UPDATE 表名 别名；单表更新为 null）
 * @param joins       联表子句（T-M21-08：UpdateJoinWrapper 渲染于 FROM 之后、SET 之前）
 * @param unparsedSets 解析失败的 SET 调用原文（T-UpdateSetSqlConcat）：{@code setSql(sqlVar)} 这类
 *              纯运行期片段静态不可还原，只能省略；此处留痕供 UI 警示，
 *              避免静默产出「无 SET」的非法 SQL
 * @param rawTailParams {@code rawTail} 中占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）
 */
public final class UpdateStatement extends SqlStatement {
    private final TableInfo table;
    private final List<SetClause> sets;
    private final List<Condition> conditions;
    private final String rawTail;
    private final String tableAlias;
    private final List<JoinClause> joins;
    private final List<String> unparsedSets;
    private final List<String> rawTailParams;

    public UpdateStatement(TableInfo table, List<SetClause> sets, List<Condition> conditions, String rawTail,
                           String tableAlias, List<JoinClause> joins, List<String> unparsedSets,
                           List<String> rawTailParams) {
        this.table = table;
        this.sets = sets;
        this.conditions = conditions;
        this.rawTail = rawTail;
        this.tableAlias = tableAlias;
        this.joins = joins;
        this.unparsedSets = unparsedSets == null ? List.of() : List.copyOf(unparsedSets);
        this.rawTailParams = rawTailParams == null ? List.of() : List.copyOf(rawTailParams);
    }

    public UpdateStatement(TableInfo table, List<SetClause> sets, List<Condition> conditions, String rawTail,
                           String tableAlias, List<JoinClause> joins) {
        this(table, sets, conditions, rawTail, tableAlias, joins, List.of(), List.of());
    }

    public UpdateStatement(TableInfo table) {
        this(table, List.of(), List.of(), null, null, List.of());
    }

    public UpdateStatement(TableInfo table,
                           List<SetClause> sets) {
        this(table, sets, List.of(), null, null, List.of());
    }

    public UpdateStatement(TableInfo table,
                           List<SetClause> sets,
                           List<Condition> conditions) {
        this(table, sets, conditions, null, null, List.of());
    }

    @Override
    public TableInfo getTable() {
        return table;
    }

    public List<SetClause> getSets() {
        return sets;
    }

    @Override
    public List<Condition> getConditions() {
        return conditions;
    }

    public String getRawTail() {
        return rawTail;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public List<JoinClause> getJoins() {
        return joins;
    }

    public List<String> getUnparsedSets() {
        return unparsedSets;
    }

    public List<String> getRawTailParams() {
        return rawTailParams;
    }

    public UpdateStatement copy(TableInfo table, List<SetClause> sets, List<Condition> conditions, String rawTail,
                                String tableAlias, List<JoinClause> joins, List<String> unparsedSets,
                                List<String> rawTailParams) {
        return new UpdateStatement(table, sets, conditions, rawTail, tableAlias, joins, unparsedSets, rawTailParams);
    }

    public UpdateStatement copy(TableInfo table, List<SetClause> sets, List<Condition> conditions, String rawTail,
                                String tableAlias, List<JoinClause> joins) {
        return new UpdateStatement(table, sets, conditions, rawTail, tableAlias, joins, unparsedSets, rawTailParams);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UpdateStatement)) {
            return false;
        }
        UpdateStatement that = (UpdateStatement) o;
        return Objects.equals(table, that.table)
                && Objects.equals(sets, that.sets)
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(rawTail, that.rawTail)
                && Objects.equals(tableAlias, that.tableAlias)
                && Objects.equals(joins, that.joins)
                && Objects.equals(unparsedSets, that.unparsedSets)
                && Objects.equals(rawTailParams, that.rawTailParams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, sets, conditions, rawTail, tableAlias, joins, unparsedSets, rawTailParams);
    }

    @Override
    public String toString() {
        return "UpdateStatement(table=" + table + ", sets=" + sets + ", conditions=" + conditions + ", rawTail="
                + rawTail + ", tableAlias=" + tableAlias + ", joins=" + joins + ", unparsedSets=" + unparsedSets
                + ", rawTailParams=" + rawTailParams + ")";
    }
}
