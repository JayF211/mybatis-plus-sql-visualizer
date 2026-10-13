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
    public Set<Character> getIdentifierQuotes() {
        return Set.of('"');
    }

    /**
     * O-42 的「含小写字母即加引号」规则自 O-51 起由接口按族实现（{@link Dialect#quoteIfNeeded}），
     * 本类<b>不再覆写</b>该方法——只声明族归属，判据落在 {@link IdentifierFolding#UPPER} 分支。
     *
     * <p>真库依据（Oracle Free 23 / FREEPDB1，DBX MCP 实测）：</p>
     * <ul>
     *   <li>{@code SELECT COUNT(*) FROM dbx_test.issue_new} → {@code ORA-00942: table or view
     *       "DBX_TEST"."ISSUE_NEW" does not exist}（裸写折叠成大写，命不中以引号创建的小写表）；</li>
     *   <li>{@code SELECT COUNT(*) FROM dbx_test."issue_new"} → 成功（100 行）；</li>
     *   <li>{@code SELECT COUNT(*) FROM dual} → 1，{@code FROM "dual"} → {@code ORA-00942}
     *       （裸写折叠为 DUAL）。</li>
     * </ul>
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.UPPER;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.ORACLE_RESERVED;
    }
}
