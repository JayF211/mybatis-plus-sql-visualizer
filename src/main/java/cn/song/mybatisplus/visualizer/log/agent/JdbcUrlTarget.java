package cn.song.mybatisplus.visualizer.log.agent;

/** R32 弱形态：JDBC URL 解析出的连接事实（全部可标注字段；凭据永不入本模型）。 */
public record JdbcUrlTarget(
    /** 库名（url path 段 / SQL Server databaseName= 参数 / Oracle service_name 或 SID）。 */
    String dbName,
    /** 主机（脱敏：只标注 host 本身；内联凭据段解析期剔除）。 */
    String host,
    /** 端口（全数字才解析；缺省 null）。 */
    String port
) {
}
