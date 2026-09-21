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

    /**
     * O-42：Oracle 裸标识符<b>折叠为大写</b>，故目录来源（字典真实名字）按「含小写字母即加引号」判定。
     *
     * <p>真库（Oracle Free 23 / FREEPDB1，DBX MCP）实测依据：</p>
     * <ul>
     *   <li>{@code SELECT COUNT(*) FROM dbx_test.issue_new} → {@code ORA-00942: table or view
     *       "DBX_TEST"."ISSUE_NEW" does not exist}（裸写折叠成大写，命不中以引号创建的小写表）；</li>
     *   <li>{@code SELECT COUNT(*) FROM dbx_test."issue_new"} → 成功（100 行）。</li>
     * </ul>
     *
     * <p>判据「名字非全大写 → 必包」；不满足时落回 {@link #quoteIfKeyword}，<b>保留字命中即包的既有行为
     * 不变</b>（{@code number}/{@code level} 等仍包）。实体来源（默认）不进入本分支——
     * {@code NamingStrategy.toSnakeCase} 的小写派生名继续裸写。</p>
     */
    @Override
    public String quoteIfNeeded(String identifier, IdentifierCaseSource caseSource) {
        if (caseSource == IdentifierCaseSource.CATALOG && containsLowerCase(identifier)) {
            return quoteIdentifier(identifier);
        }
        return quoteIfKeyword(identifier);
    }

    /** 是否含小写字母（master 的 {@code identifier.any { it.isLowerCase() }}）。 */
    private static boolean containsLowerCase(String identifier) {
        for (int i = 0; i < identifier.length(); i++) {
            if (Character.isLowerCase(identifier.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.ORACLE_RESERVED;
    }
}
