package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * KingBase（人大金仓）方言：同 PostgreSQL 的 {@code LIMIT {count} OFFSET {offset}}。
 * upsert 同 PG 的 {@code ON CONFLICT} 形态。
 */
public final class KingBaseDialect implements Dialect {

    public static final KingBaseDialect INSTANCE = new KingBaseDialect();

    private KingBaseDialect() {}

    @Override
    public String getName() {
        return "KingBase（人大金仓）";
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
        return SqlKeywords.KINGBASE_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns);
    }
}
