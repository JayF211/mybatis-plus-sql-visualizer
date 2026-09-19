// T-SyncK2JMaster309 自 execute/ExecutionErrorInfo.kt 移植
package cn.song.mybatisplus.visualizer.execute;

/**
 * T-ErrorLocate（N-18）：执行链错误的富信息载体——旧 {@code onError: (String) -> Unit} 通道只投递
 * {@code cause.message}（SQLState/errorCode/驱动位置信息全部丢弃），本类在反射链 catch 点并行携带
 * 结构化信息，消费方（SqlResultPanel 错误定位动作 / MCP 工具）按需取用。
 *
 * <p>DBX sql_error_position.rs 对齐：把驱动错误位置结构化是排错体验的最大单点提升；
 * {@code positionChar}/{@code lineInSql} 提取自错误消息文本（PG {@code Position: N}、MySQL {@code at line N}），
 * 不动协议层。marker 单一性门控与「基建语句不带位置」防误指细节见 {@link ErrorPositionExtractor}。</p>
 *
 * @param message        错误消息全文
 * @param sqlState       JDBC SQLState（null = 未知）
 * @param errorCode      vendor 错误码（null = 未知或 0）
 * @param positionChar   驱动报错字符位置（1-based，指被执行 SQL 文本；null = 消息未携带或多个 marker 放弃归属）
 * @param lineInSql      驱动报错行号（1-based，指被执行 SQL 文本；语义同上）
 * @param positionSource 位置信号来源标签（"pg-position" / "line" / "mysql-at-line"），留证与测试断言用
 */
public record ExecutionErrorInfo(
        @org.jetbrains.annotations.NotNull String message,
        @org.jetbrains.annotations.Nullable String sqlState,
        @org.jetbrains.annotations.Nullable Integer errorCode,
        @org.jetbrains.annotations.Nullable Integer positionChar,
        @org.jetbrains.annotations.Nullable Integer lineInSql,
        @org.jetbrains.annotations.Nullable String positionSource) {

    /** 仅 message、其余字段缺省（Kotlin 默认参数 {@code = null} 的等价构造形态）。 */
    public ExecutionErrorInfo(@org.jetbrains.annotations.NotNull String message) {
        this(message, null, null, null, null, null);
    }
}
