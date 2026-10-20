package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * MariaDB 方言：同 MySQL 的 {@code LIMIT [offset,] count}。标识符引用反引号。
 */
public final class MariaDbDialect implements Dialect {

    public static final MariaDbDialect INSTANCE = new MariaDbDialect();

    private MariaDbDialect() {}

    @Override
    public String getName() {
        return "MariaDB";
    }

    /** R36：MariaDB 同 MySQL 官方 `START TRANSACTION`（证据三档见 Dialect#getTransactionBegin）。 */
    @Override
    public String getTransactionBegin() {
        return "START TRANSACTION";
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
    public Set<Character> getIdentifierQuotes() {
        return Set.of('`');
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.MARIA_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
