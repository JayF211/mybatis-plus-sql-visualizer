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

    /**
     * O-51：金仓与 PG 同折叠方向（裸标识符折叠为小写）→ LOWER 族。
     *
     * <p>真机依据（KingbaseES V8，DBX MCP 只读实测，2026-09-22）：{@code SELECT COUNT(*) FROM
     * INFORMATION_SCHEMA.TABLES} → 492 行（裸写折为小写命中）；{@code FROM information_schema."TABLES"}
     * → {@code kb: relation "information_schema.TABLES" does not exist}。此前为「按 PG 兼容推断」，
     * 本轮实测证实（计划 §八 待验证前提 2 已可升级为已证实）。</p>
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.KINGBASE_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns, caseSource);
    }
}
