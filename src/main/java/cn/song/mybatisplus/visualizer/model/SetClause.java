package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;
/**
 * SET 赋值子句。
 *
 * 两种形式（互斥）：
 *  - column + value：set(Entity::getX, v) → SET col = v
 *  - rawSql：setSql("col = expr") → 原样渲染
 */
public final class SetClause {
    private final ColumnRef column;
    private final SqlValue value;
    private final String rawSql;
    private final List<String> rawParams;

    public SetClause(ColumnRef column, SqlValue value, String rawSql, List<String> rawParams) {
        this.column = column;
        this.value = value;
        this.rawSql = rawSql;
        this.rawParams = rawParams == null ? List.of() : List.copyOf(rawParams);
    }

    public SetClause(ColumnRef column, SqlValue value, String rawSql) {
        this(column, value, rawSql, List.of());
    }

    public SetClause(ColumnRef column, SqlValue value) {
        this(column, value, null, List.of());
    }

    public SetClause(ColumnRef column) {
        this(column, null, null, List.of());
    }

    public SetClause(String rawSql) {
        this(null, null, rawSql, List.of());
    }

    public ColumnRef getColumn() {
        return column;
    }

    public SqlValue getValue() {
        return value;
    }

    public String getRawSql() {
        return rawSql;
    }

    /** {@code rawSql} 中占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）：渲染时按序登记进参数表。 */
    public List<String> getRawParams() {
        return rawParams;
    }

    /** 是否为原始 SQL 形式（setSql）。 */
    public boolean isRaw() {
        return rawSql != null;
    }

    public SetClause copy(ColumnRef column, SqlValue value, String rawSql, List<String> rawParams) {
        return new SetClause(column, value, rawSql, rawParams);
    }

    public SetClause copy(ColumnRef column, SqlValue value, String rawSql) {
        return new SetClause(column, value, rawSql, rawParams);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SetClause)) {
            return false;
        }
        SetClause that = (SetClause) o;
        return Objects.equals(column, that.column) && Objects.equals(value, that.value)
                && Objects.equals(rawSql, that.rawSql) && Objects.equals(rawParams, that.rawParams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, value, rawSql, rawParams);
    }

    @Override
    public String toString() {
        return "SetClause(column=" + column + ", value=" + value + ", rawSql=" + rawSql + ", rawParams=" + rawParams
                + ")";
    }
}
