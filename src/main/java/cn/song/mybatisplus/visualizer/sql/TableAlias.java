package cn.song.mybatisplus.visualizer.sql;

import java.util.Objects;

/**
 * FROM/JOIN 表清单中的一张表（T-M27-01）。
 *
 * @param table 表名（原始文本：{@code tableName} / {@code db.t} 点分 / {@code `t`} 反引号形态）
 * @param alias 表别名；无别名时为 null（如 {@code FROM t}、{@code FROM t, u} 的 {@code u} 等）
 */
public final class TableAlias {

    private final String table;
    private final String alias;

    public TableAlias(String table, String alias) {
        this.table = table;
        this.alias = alias;
    }

    public String getTable() {
        return table;
    }

    public String getAlias() {
        return alias;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableAlias)) return false;
        TableAlias that = (TableAlias) o;
        return Objects.equals(table, that.table) && Objects.equals(alias, that.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, alias);
    }

    @Override
    public String toString() {
        return "TableAlias(table=" + table + ", alias=" + alias + ")";
    }
}
