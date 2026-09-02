package cn.song.mybatisplus.visualizer.sql;

import java.util.List;

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
public final class DialectRegistry {

    /** 单例。 */
    public static final DialectRegistry INSTANCE = new DialectRegistry();

    private static final List<Dialect> DIALECTS = List.of(
            MySqlDialect.INSTANCE,
            PostgreSQLDialect.INSTANCE,
            OracleDialect.INSTANCE,
            SqlServerDialect.INSTANCE,
            H2Dialect.INSTANCE,
            SqliteDialect.INSTANCE,
            MariaDbDialect.INSTANCE,
            DmDialect.INSTANCE,
            KingBaseDialect.INSTANCE);

    private DialectRegistry() {}

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    public List<Dialect> getDialects() {
        return DIALECTS;
    }

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 {@link Dialect#getName()}）
     * @return 匹配方言；未知名称返回 {@link MySqlDialect}
     */
    public Dialect byName(String name) {
        if (name != null) {
            for (Dialect d : DIALECTS) {
                if (d.getName().equals(name)) return d;
            }
        }
        return MySqlDialect.INSTANCE;
    }
}
