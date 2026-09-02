package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 方言：{@code LIMIT {count} OFFSET {offset}}。
 * upsert 原生形态为整语句 {@code MERGE INTO t (cols) KEY (conflict) VALUES (...)}。
 */
public final class H2Dialect implements Dialect {

    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {}

    @Override
    public String getName() {
        return "H2";
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
        return SqlKeywords.H2_RESERVED;
    }

    @Override
    public String renderUpsertStatement(String tableName, List<String> columns, String conflictKey) {
        String cols = columns.stream().map(this::quoteIfKeyword).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(it -> "?").collect(Collectors.joining(", "));
        return "MERGE INTO " + quoteIfKeyword(tableName) + " (" + cols + ") KEY (" +
                quoteIfKeyword(conflictKey) + ") VALUES (" + placeholders + ")";
    }
}
