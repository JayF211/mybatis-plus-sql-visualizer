package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * UNION 子查询子句（MPJ 官方 API）：union(Class, w -> ...) → UNION (子查询)。
 *
 * @param keyword UNION / UNION ALL
 * @param sql     子查询 SQL（compact 单行渲染）
 * @param params  {@code sql} 中占位符 {@code ?} 的来源（按出现顺序，T-UpdateSetSqlConcat）：子语句在渲染点按序登记
 */
public final class UnionClause {
    private final String keyword;
    private final String sql;
    private final List<String> params;

    public UnionClause(String keyword, String sql, List<String> params) {
        this.keyword = keyword;
        this.sql = sql;
        this.params = params == null ? List.of() : List.copyOf(params);
    }

    public UnionClause(String keyword, String sql) {
        this(keyword, sql, List.of());
    }

    public String getKeyword() {
        return keyword;
    }

    public String getSql() {
        return sql;
    }

    public List<String> getParams() {
        return params;
    }

    public UnionClause copy(String keyword, String sql, List<String> params) {
        return new UnionClause(keyword, sql, params);
    }

    public UnionClause copy(String keyword, String sql) {
        return new UnionClause(keyword, sql, params);
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
        return Objects.equals(keyword, that.keyword) && Objects.equals(sql, that.sql)
                && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyword, sql, params);
    }

    @Override
    public String toString() {
        return "UnionClause(keyword=" + keyword + ", sql=" + sql + ", params=" + params + ")";
    }
}
