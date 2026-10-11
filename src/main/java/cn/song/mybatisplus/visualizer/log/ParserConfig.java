package cn.song.mybatisplus.visualizer.log;

/**
 * 解析器配置（桥创建 parser 用；纯数据）。
 */
public record ParserConfig(
        // MyBatis 解析器配置
        String sqlPrefix,
        String paramPrefix,
        boolean mixedPrefix,
        int maxParseLength,
        // Hibernate 解析器配置
        String hibernateSqlPrefix,
        String hibernateParamPrefix,
        boolean hibernateEnabled,
        // JdbcTemplate 解析器配置（M47）
        boolean jdbcEnabled,
        // ShardingSphere 解析器配置（R10 T-ShardingSphereCompat；D3=A 默认开）
        boolean shardingEnabled
) {
    /** MyBatis 专用构造（Hibernate 取默认值）。 */
    public ParserConfig(String sqlPrefix, String paramPrefix, boolean mixedPrefix, int maxParseLength) {
        this(sqlPrefix, paramPrefix, mixedPrefix, maxParseLength,
                HibernateLogParser.DEFAULT_SQL_PREFIX,
                HibernateLogParser.DEFAULT_PARAM_PREFIX,
                true,
                true,
                true);
    }
}
