package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import java.util.Set;

/**
 * SQL Server 方言（2012+）：{@code OFFSET {offset|0} ROWS FETCH NEXT {count} ROWS ONLY}。
 * 标识符引用方括号 {@code [x]}。
 */
public final class SqlServerDialect implements Dialect {

    public static final SqlServerDialect INSTANCE = new SqlServerDialect();

    private SqlServerDialect() {}

    @Override
    public String getName() {
        return "SQL Server";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        SqlValue offset = limit.getOffset() != null ? limit.getOffset() : new SqlValue.Literal("0");
        return "OFFSET " + Dialect.renderLimitValue(offset) + " ROWS FETCH NEXT " +
                Dialect.renderLimitValue(limit.getCount()) + " ROWS ONLY";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier + "]";
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.SQLSERVER_RESERVED;
    }
}
