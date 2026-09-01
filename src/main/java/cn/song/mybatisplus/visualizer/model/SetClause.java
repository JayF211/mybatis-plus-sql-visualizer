package cn.song.mybatisplus.visualizer.model;

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

    public SetClause(ColumnRef column, SqlValue value, String rawSql) {
        this.column = column;
        this.value = value;
        this.rawSql = rawSql;
    }

    public SetClause(ColumnRef column, SqlValue value) {
        this(column, value, null);
    }

    public SetClause(ColumnRef column) {
        this(column, null, null);
    }

    public SetClause(String rawSql) {
        this(null, null, rawSql);
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

    /** 是否为原始 SQL 形式（setSql）。 */
    public boolean isRaw() {
        return rawSql != null;
    }

    public SetClause copy(ColumnRef column, SqlValue value, String rawSql) {
        return new SetClause(column, value, rawSql);
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
                && Objects.equals(rawSql, that.rawSql);
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, value, rawSql);
    }

    @Override
    public String toString() {
        return "SetClause(column=" + column + ", value=" + value + ", rawSql=" + rawSql + ")";
    }
}
