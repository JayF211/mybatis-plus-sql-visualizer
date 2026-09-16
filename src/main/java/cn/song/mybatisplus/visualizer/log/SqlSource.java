package cn.song.mybatisplus.visualizer.log;

/**
 * 日志条目来源（区分不同 ORM 框架的捕获结果）。
 */
public enum SqlSource {
    /** MyBatis-Plus 控制台日志（Preparing: / Parameters: 格式）。 */
    MYBATIS,
    /** Hibernate/JPA 控制台日志（Hibernate: / binding parameter 格式）。 */
    HIBERNATE,
    /** Spring JdbcTemplate 控制台日志（Executing prepared SQL statement [SQL] / Setting SQL statement parameter value: ... 格式）。 */
    JDBC,
    /** agent 字节码通道（M56 增强捕获）：应用 JVM 内 JDBC 执行点 hook 直取的结构化事件。 */
    AGENT,
    /** 未知来源（兼容旧版本条目）。 */
    UNKNOWN,
}
