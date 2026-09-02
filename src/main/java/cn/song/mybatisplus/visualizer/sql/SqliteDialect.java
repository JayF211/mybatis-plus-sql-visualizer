package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * SQLite 方言：同 H2 的 {@code LIMIT {count} OFFSET {offset}}。标识符引用双引号 {@code "x"}。
 */
public final class SqliteDialect implements Dialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {}

    @Override
    public String getName() {
        return "SQLite";
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
        return SqlKeywords.SQLITE_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns);
    }
}
