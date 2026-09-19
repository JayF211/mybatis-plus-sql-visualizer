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
    /**
     * MCP 工具通道（T-McpAuditTrail / T-MAT-03）：MCP 执行类工具在真实数据源上跑过的只读语句。
     *
     * <p>当前唯一产出点 = {@code sqlv_explain}（十个 MCP 工具里唯一有执行面的一个）——条目文本恒为
     * 调用方传入的<b>原始 selectSql</b>，绝不落 {@code EXPLAIN <select>} 包装句（T-MAT-02：包装句首 token
     * 为 {@code EXPLAIN}，会被 {@link LogStatementClassifier} 判 {@code OTHER} → 列表无类型染色）。
     * 因此本枚举值同时充当「该条来自 EXPLAIN 包装执行」的独立标记。
     *
     * <p>与 {@link #AGENT} 的分工：agent 是应用进程内 JDBC hook（被观测应用自己执行的 SQL），
     * 本值是本插件 MCP 工具主动执行的语句——两者都不来自控制台日志解析。
     */
    MCP,
    /** 未知来源（兼容旧版本条目）。 */
    UNKNOWN,
}
