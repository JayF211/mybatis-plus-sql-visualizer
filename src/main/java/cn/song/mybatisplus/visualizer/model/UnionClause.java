package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * UNION 子查询子句（MPJ 官方 API）：union(Class, w -> ...) → UNION (子查询)。
 *
 * @param keyword UNION / UNION ALL
 * @param sql     子查询 SQL（compact 单行渲染）
 */
public final class UnionClause {
    private final String keyword;
    private final String sql;

    public UnionClause(String keyword, String sql) {
        this.keyword = keyword;
        this.sql = sql;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getSql() {
        return sql;
    }

    public UnionClause copy(String keyword, String sql) {
        return new UnionClause(keyword, sql);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UnionClause)) {
            return false;
        }
        UnionClause that = (UnionClause) o;
        return Objects.equals(keyword, that.keyword) && Objects.equals(sql, that.sql);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyword, sql);
    }

    @Override
    public String toString() {
        return "UnionClause(keyword=" + keyword + ", sql=" + sql + ")";
    }
}
