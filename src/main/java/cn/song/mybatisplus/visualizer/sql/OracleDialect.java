package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.Set;

/**
 * Oracle 方言（12c+）：{@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}。
 * UPSERT 无原生简洁形态（仅 MERGE INTO）→ null 降级纯 INSERT。
 */
public final class OracleDialect implements Dialect {

    public static final OracleDialect INSTANCE = new OracleDialect();

    private OracleDialect() {}

    @Override
    public String getName() {
        return "Oracle";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        if (limit.getOffset() != null) {
            return "OFFSET " + Dialect.renderLimitValue(limit.getOffset()) + " ROWS FETCH NEXT " +
                    Dialect.renderLimitValue(limit.getCount()) + " ROWS ONLY";
        }
        return "FETCH FIRST " + Dialect.renderLimitValue(limit.getCount()) + " ROWS ONLY";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.ORACLE_RESERVED;
    }
}
