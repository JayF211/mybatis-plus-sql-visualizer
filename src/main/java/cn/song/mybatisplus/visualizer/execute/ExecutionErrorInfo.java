// T-SyncK2JMaster309 自 execute/ExecutionErrorInfo.kt 移植
// T-SyncK2JMaster184 c07b8e3 增量：T-ELU-04 executedSqlLength 组件 + T-Round10FU lineInSql/positionSource
// KDoc 订正。master 侧 ErrorPositionExtractor 与本 record 同一 .kt 文件；Java 侧 public 顶层类
// 须独立文件（且 mcp 包按 FQCN 引用），故保留 ErrorPositionExtractor.java——JVM 类布局与
// master 编译产物一致（Kotlin 同文件双顶层声明本就编译为两个独立 class）。
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
 * @param message           错误消息全文
 * @param sqlState          JDBC SQLState（null = 未知）
 * @param errorCode         vendor 错误码（null = 未知或 0）
 * @param positionChar      驱动报错字符位置（1-based，指被执行 SQL 文本；null = 消息未携带或多个 marker 放弃归属）
 * @param lineInSql         驱动报错行号（1-based，指被执行 SQL 文本；语义同上）。
 *                          T-Round10FU（独立复核 ㉕）：消费方是 ui.SqlResultPanel.showErrorRich 的三态 tooltip
 *                          （{@code positionChar} 缺、本字段在时用带行号的文案）——改前本字段在生产树
 *                          <b>零消费方</b>（只填不读），MySQL 语法错误这类「只有行号没有字符位置」的报错因此落到
 *                          「语句起始行」兜底文案，驱动报的行号被丢弃
 * @param positionSource    位置信号来源标签（"pg-position" / "line" / "mysql-at-line" / "oracle-position"），留证与测试断言用
 * @param executedSqlLength T-ErrorLocateUsability T-ELU-04（O-49）：被执行 SQL 的长度（调用方传入时的钳制上界）。
 *                          <b>两态结构化</b>：驱动报错位置指向的是<b>被执行 SQL 文本</b>（动态 SQL 拼接、参数注入后行号必然
 *                          错位），与 mapper 原文行不一一对应——v1 只承诺「语句起始行」跳转，不引入位置换算。
 *                          本字段把「这个位置属于哪一态」变成数据：非 null 即证明调用方确实把被执行文本长度传了进来
 *                          （越界钳制的生效前提），消费方（tooltip / MCP 文案）据此如实说明，测试也据此断言接线
 */
public record ExecutionErrorInfo(
        @org.jetbrains.annotations.NotNull String message,
        @org.jetbrains.annotations.Nullable String sqlState,
        @org.jetbrains.annotations.Nullable Integer errorCode,
        @org.jetbrains.annotations.Nullable Integer positionChar,
        @org.jetbrains.annotations.Nullable Integer lineInSql,
        @org.jetbrains.annotations.Nullable String positionSource,
        @org.jetbrains.annotations.Nullable Integer executedSqlLength) {

    /** 仅 message、其余字段缺省（Kotlin 默认参数 {@code = null} 的等价构造形态）。 */
    public ExecutionErrorInfo(@org.jetbrains.annotations.NotNull String message) {
        this(message, null, null, null, null, null, null);
    }
}
