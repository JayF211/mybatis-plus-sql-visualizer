package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * PostgreSQL 方言：{@code LIMIT count OFFSET offset}。
 */
public final class PostgreSQLDialect implements Dialect {

    public static final PostgreSQLDialect INSTANCE = new PostgreSQLDialect();

    private PostgreSQLDialect() {}

    @Override
    public String getName() {
        return "PostgreSQL";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderPgStyleLimit(limit);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.PG_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns);
    }

    @Override
    public String renderExplainStatement(String sql) {
        return "EXPLAIN (FORMAT JSON, VERBOSE) " + sql;
    }
}
