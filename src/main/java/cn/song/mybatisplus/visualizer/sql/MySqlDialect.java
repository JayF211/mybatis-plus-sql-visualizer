package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * MySQL 方言：{@code LIMIT [offset,] count}。
 */
public final class MySqlDialect implements Dialect {

    public static final MySqlDialect INSTANCE = new MySqlDialect();

    private MySqlDialect() {}

    @Override
    public String getName() {
        return "MySQL";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderMySqlStyleLimit(limit);
    }

    @Override
    public boolean getSupportsDeleteLimit() {
        return true;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.MYSQL_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns);
    }

    @Override
    public String renderExplainStatement(String sql) {
        return "EXPLAIN " + sql;
    }
}
