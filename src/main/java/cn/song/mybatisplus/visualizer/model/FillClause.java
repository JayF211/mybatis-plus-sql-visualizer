package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * fill 附加查询子句（MPJ 1.5.1+，T-MpjFill）。
 *
 * MPJ fill(field, tagField) 语义：主查询执行后收集结果集中关联键 → 对关联表发起 IN 附加查询 →
 * 内存回填到主结果集合。静态预览无法获取主查询结果集，IN 列表统一渲染 IN (?) 占位符。
 *
 * @param childTable    关联表 TableInfo（psi 解析或类名推断）
 * @param childAlias    关联表别名（实体类名首字母小写）
 * @param selectColumns 关联表 SELECT 列列表（默认全列）
 * @param tagField      关联表 IN 条件列（关联键字段名，列名）
 * @param limit         附加查询条数限制（null 表示不限制）
 * @param condition     条件控制（null 表示无条件，true 执行/false 跳过）
 * @param data          指定数据源对象（null 表示从主查询结果收集）
 */
public final class FillClause {
    private final TableInfo childTable;
    private final String childAlias;
    private final List<ColumnRef> selectColumns;
    private final String tagField;
    private final Integer limit;
    private final Boolean condition;
    private final SqlValue data;

    public FillClause(TableInfo childTable, String childAlias, List<ColumnRef> selectColumns, String tagField,
                      Integer limit, Boolean condition, SqlValue data) {
        this.childTable = childTable;
        this.childAlias = childAlias;
        this.selectColumns = selectColumns;
        this.tagField = tagField;
        this.limit = limit;
        this.condition = condition;
        this.data = data;
    }

    public FillClause(TableInfo childTable, String childAlias,
                      List<ColumnRef> selectColumns,
                      String tagField) {
        this(childTable, childAlias, selectColumns, tagField, null, null, null);
    }

    public TableInfo getChildTable() {
        return childTable;
    }

    public String getChildAlias() {
        return childAlias;
    }

    public List<ColumnRef> getSelectColumns() {
        return selectColumns;
    }

    public String getTagField() {
        return tagField;
    }

    public Integer getLimit() {
        return limit;
    }

    public Boolean getCondition() {
        return condition;
    }

    public SqlValue getData() {
        return data;
    }

    public FillClause copy(TableInfo childTable, String childAlias, List<ColumnRef> selectColumns, String tagField,
                           Integer limit, Boolean condition, SqlValue data) {
        return new FillClause(childTable, childAlias, selectColumns, tagField, limit, condition, data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FillClause)) {
            return false;
        }
        FillClause that = (FillClause) o;
        return Objects.equals(childTable, that.childTable)
                && Objects.equals(childAlias, that.childAlias)
                && Objects.equals(selectColumns, that.selectColumns)
                && Objects.equals(tagField, that.tagField)
                && Objects.equals(limit, that.limit)
                && Objects.equals(condition, that.condition)
                && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(childTable, childAlias, selectColumns, tagField, limit, condition, data);
    }

    @Override
    public String toString() {
        return "FillClause(childTable=" + childTable + ", childAlias=" + childAlias + ", selectColumns=" + selectColumns
                + ", tagField=" + tagField + ", limit=" + limit + ", condition=" + condition + ", data=" + data + ")";
    }
}
