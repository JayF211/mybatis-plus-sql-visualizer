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
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.DM_RESERVED;
    }
}
