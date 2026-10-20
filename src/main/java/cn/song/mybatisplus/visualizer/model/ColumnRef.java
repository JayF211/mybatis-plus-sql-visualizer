package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import java.util.List;
import java.util.Objects;

/**
 * 列引用：数据库列及其可选的表别名与 SQL 别名（F-01/F-11 selectAs 产物）。
 *
 * @param tableAlias 表别名（单表查询为 null；联表查询如 cd / cssm）
 * @param columnName 列名（如 share_scheme_id）
 * @param alias      SQL 别名（selectAs 时设置，如 AS device_id）
 * @param function   聚合函数（selectMax/selectCount 等，如 MAX / COUNT；null 表示普通列）
 * @param rawExpr    selectFunc 的完整表达式（如 COUNT(CASE WHEN t.status = 1 THEN 1 END)；非空时优先渲染）
 * @param entityClass 列来源实体类名（selectAs 第一参方法引用的接收者类名；用于图表列归属推断）
 * @param type        Java 类型全限定名（selectAs 第二参方法引用的返回类型；用于图表列类型显示）
 * @param rawParams   表达式内占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）。
 *                    {@code select("amount * " + x + " AS v")} 这类片段实参中的运行期段渲染成 {@code ?} 后，须在 SELECT
 *                    列列表的渲染点按序登记，否则面板按位置回填会整体错位。
 */
public final class ColumnRef {
    private final String tableAlias;
    private final String columnName;
    private final String alias;
    private final String function;
    private final String rawExpr;
    private final String entityClass;
    private final String type;
    private final List<String> rawParams;

    public ColumnRef(String tableAlias, String columnName, String alias, String function, String rawExpr,
                      String entityClass, String type, List<String> rawParams) {
        this.tableAlias = tableAlias;
        this.columnName = columnName;
        this.alias = alias;
        this.function = function;
        this.rawExpr = rawExpr;
        this.entityClass = entityClass;
        this.type = type;
        this.rawParams = rawParams == null ? List.of() : List.copyOf(rawParams);
    }

    public ColumnRef(String tableAlias, String columnName, String alias, String function, String rawExpr,
                      String entityClass, String type) {
        this(tableAlias, columnName, alias, function, rawExpr, entityClass, type, List.of());
    }

    public ColumnRef(String tableAlias, String columnName, String alias, String function, String rawExpr,
                      String entityClass) {
        this(tableAlias, columnName, alias, function, rawExpr, entityClass, null);
    }

    public ColumnRef(String tableAlias, String columnName, String alias, String function, String rawExpr) {
        this(tableAlias, columnName, alias, function, rawExpr, null, null);
    }

    public ColumnRef(String tableAlias, String columnName, String alias, String function) {
        this(tableAlias, columnName, alias, function, null, null, null);
    }

    public ColumnRef(String tableAlias, String columnName, String alias) {
        this(tableAlias, columnName, alias, null, null, null, null);
    }

    public ColumnRef(String tableAlias, String columnName) {
        this(tableAlias, columnName, null, null, null, null, null);
    }

    public ColumnRef(String columnName) {
        this(null, columnName, null, null, null, null, null);
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getAlias() {
        return alias;
    }

    public String getFunction() {
        return function;
    }

    public String getRawExpr() {
        return rawExpr;
    }

    public String getEntityClass() {
        return entityClass;
    }

    public String getType() {
        return type;
    }

    public List<String> getRawParams() {
        return rawParams;
    }

    /** 渲染为带表限定的列表达式，如 cssm.sort 或 sort。 */
    public String getQualifiedName() {
        return tableAlias != null ? tableAlias + "." + columnName : columnName;
    }

    /**
     * T-M23.5：带表限定的列表达式，列名命中本方言保留字 → 按方言符号引用（t.`table`）——
     * 别名前缀不包、列名本体按需包。供解析期 raw 拼接（QueryWrapperParser 等）与
     * SqlGenerator 渲染共用，与 quoteIfKeyword 同语义。
     */
    public String qualifiedName(Dialect dialect) {
        return (tableAlias != null ? tableAlias + "." : "") + dialect.quoteIfKeyword(columnName);
    }

    /** 渲染完整列表达式（含聚合函数与 AS 别名），如 MAX(t.power)、COUNT(t.id) AS applyCount。 */
    public String getRendered() {
        String expr = rawExpr != null ? rawExpr
                : (function != null ? function + "(" + getQualifiedName() + ")" : getQualifiedName());
        return alias != null ? expr + " AS " + alias : expr;
    }

    public ColumnRef copy(String tableAlias, String columnName, String alias, String function, String rawExpr,
                          String entityClass, String type, List<String> rawParams) {
        return new ColumnRef(tableAlias, columnName, alias, function, rawExpr, entityClass, type, rawParams);
    }

    public ColumnRef copy(String tableAlias, String columnName, String alias, String function, String rawExpr,
                          String entityClass, String type) {
        return new ColumnRef(tableAlias, columnName, alias, function, rawExpr, entityClass, type, rawParams);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColumnRef)) {
            return false;
        }
        ColumnRef that = (ColumnRef) o;
        return Objects.equals(tableAlias, that.tableAlias)
                && Objects.equals(columnName, that.columnName)
                && Objects.equals(alias, that.alias)
                && Objects.equals(function, that.function)
                && Objects.equals(rawExpr, that.rawExpr)
                && Objects.equals(entityClass, that.entityClass)
                && Objects.equals(type, that.type)
                && Objects.equals(rawParams, that.rawParams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableAlias, columnName, alias, function, rawExpr, entityClass, type, rawParams);
    }

    @Override
    public String toString() {
        return "ColumnRef(tableAlias=" + tableAlias + ", columnName=" + columnName + ", alias=" + alias
                + ", function=" + function + ", rawExpr=" + rawExpr + ", entityClass=" + entityClass + ", type=" + type
                + ", rawParams=" + rawParams + ")";
    }
}
