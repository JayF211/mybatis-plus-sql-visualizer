package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.Set;

/**
 * DM（达梦）方言：同 MySQL 的 {@code LIMIT [offset,] count}。标识符引用双引号 {@code "x"}。
 * upsert 无原生简洁形态（DM 官方仅支持 MERGE INTO）→ 默认 null 降级纯 INSERT。
 */
public final class DmDialect implements Dialect {

    public static final DmDialect INSTANCE = new DmDialect();

    private DmDialect() {}

    @Override
    public String getName() {
        return "DM（达梦）";
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
        return "\"" + identifier + "\"";
    }

    /**
     * <b>DM8 需要 identity 包装（2026-09-23 真机实测新增，原默认「不包装」是缺陷）</b>（O-52）。
     *
     * <p>该默认值此前是<b>无证据的保守选择</b>（O-52 计划 §五-3 明写「DBX 有 DM 实现，但我方无 DM identity
     * 真机证据 → 默认不给 DM 造包装，不照抄；若 §五-3 证伪为需要，另立增量」）。本轮按该条真机复核，
     * 结论是<b>证伪为需要</b>：DM8 对显式给自增列赋值的拒绝与 SQL Server 同形——
     * {@code dm.jdbc.driver.DMException: 仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值}
     * （vendorCode -2723，库 {@code DBX_TEST} 表 {@code T_AUTOINC_7658}，自增列 {@code ID}）。
     * 故 DM 与 SQL Server 一样走 {@link Dialect#identityInsertWrapper}（两库语法逐字相同，实测通过）。</p>
     *
     * <p><b>真机读数</b>：同一 INSERT 不加包装 → 报上述异常；加 {@code SET IDENTITY_INSERT … ON/OFF}
     * 成对包装 → {@code SET ON} 无影响行、INSERT <b>影响 1 行</b>、{@code SET OFF} 无影响行（探针行随后按 ID
     * 删除并复验为 0 行，未留残留）；对照：<b>不指定</b>自增列的 INSERT 无需包装即成功（即包装只在自增列
     * 被勾回时需要）。</p>
     */
    @Override
    public String renderInsertWrapper(String tableName, String sql, IdentifierCaseSource caseSource) {
        return Dialect.identityInsertWrapper(this, tableName, sql, caseSource);
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.DM_RESERVED;
    }
}
