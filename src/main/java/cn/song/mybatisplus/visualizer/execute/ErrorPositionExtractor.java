// T-SyncK2JMaster309 自 execute/ExecutionErrorInfo.kt（ErrorPositionExtractor 对象）移植
// T-SyncK2JMaster184 c07b8e3 增量：T-ELU-02 Oracle 位置形态（`error occur at position: N`）+
// T-Round10FU 驱动 toString 形态（`, Position : N`）+ 0-based→N+1 归一化 + maskOraclePositions 遮蔽 +
// buildInfo 落 executedSqlLength。master 的 Kotlin 源把本类与 ExecutionErrorInfo 写在同一 .kt 文件；
// Java 侧 public 顶层类须独立文件（且 mcp 包按 FQCN 引用本类），故保留独立 .java——JVM 类布局
// 与 master 编译产物一致（Kotlin 同文件双顶层声明本就编译为两个独立 class）。
package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误位置提取器（纯函数，零平台依赖）。
 *
 * <p>两条 DBX 防误指规则照抄：</p>
 * <ul>
 *  <li><b>marker 单一性门控</b>（sql_error_position.rs:113）：消息里恰好一个位置信号才归属，
 *      多个并存时放弃（宁缺毋滥——归属错了比没有更危险）；</li>
 *  <li><b>基建语句不带位置</b>（postgres.rs:1501）：连接串错误等非 SQL 错误天然不含位置标记，
 *      提取不出即无位置（诚实降级），绝不猜测。</li>
 * </ul>
 *
 * <p>越界钳制：调用方传入长度上界时，位置钳到 [1, sqlMaxLength]；钳后越界（&lt;=0）返回 null。</p>
 *
 * <p><b>口径订正（T-Round10 独立复核 major⑧，2026-09-22 实测）</b>：IDE 链三处投递点传的是<b>整篇控制台提交文本</b>的长度
 * （{@code DataSourceSelector} 的 {@code executedSql.length()}），而驱动报的位置是<b>语句内</b>位置 → 上界恒 ≥ 位置，
 * 故该钳制在 IDE 链上<b>实际不咬合</b>（是兜底护栏，不是有效约束）。要让它真正咬合需传「被执行语句文本」的长度，
 * 而三处 catch 点只持有整篇文本、拿不到语句边界（{@code SqlStatementSplitter} 能切分但无法判定哪一条是失败的那条），
 * 故本处<b>如实登记为粗上界</b>、不改行为。MCP 链传的是被提交的 EXPLAIN 包装句长度，那一处上界是准的。</p>
 */
public final class ErrorPositionExtractor {

    public static final ErrorPositionExtractor INSTANCE = new ErrorPositionExtractor();
    private ErrorPositionExtractor() {}

    /**
     * 位置信号形态：PG pgjdbc 把 server 错误的 Position 拼进 message（`…  Position: 42`）。
     *
     * <p>正则保持逐字不变（{@code \bPosition:} 会顺带命中 Oracle 形态里的 {@code position: N}，见
     * {@link #ORACLE_POSITION} 的遮蔽说明——这里<b>不动正则</b>是为了任何不含 Oracle 形态的消息行为零变化）。</p>
     */
    private static final Pattern PG_POSITION = Pattern.compile("(?i)\\bPosition:\\s*(\\d+)");

    /** 行号形态：MySQL 语法错误（`… near '…' at line 1`）与 PG 旧形态（`At line N`）。 */
    private static final Pattern AT_LINE = Pattern.compile("(?i)\\bat line (\\d+)");

    /** 行号形态：`Line: N`（部分驱动/方言的详细错误附行号冒号形态）。 */
    private static final Pattern LINE_COLON = Pattern.compile("(?i)\\bline:\\s*(\\d+)");

    /**
     * T-ELU-02：Oracle 位置形态——{@code error occur at position: N}（真库实测原文，见下）。
     *
     * <p><b>真库实测</b>（Oracle Free 23 / FREEPDB1，DBX 通道 2026-09-22，只读）：</p>
     * <ul>
     *  <li>{@code SELECT * FROM dbx_test.no_such_table_xyz} → {@code ORA-00942: table or view
     *      "DBX_TEST"."NO_SUCH_TABLE_XYZ" does not exist\n error occur at position: 23}</li>
     *  <li>{@code SELECT FROM} → {@code ORA-00936: missing expression\n error occur at position: 7}</li>
     *  <li>{@code FROM dbx_test.issue_new} → {@code ORA-00900: invalid SQL statement\n error occur at position: 0}</li>
     * </ul>
     *
     * <p><b>取值语义（实测三例一致）</b>：N 是<b>出错记号起点减一</b>（{@code FROM …} 的记号在 0 位 → N=0；
     * {@code SELECT FROM} 的 {@code FROM} 在 1-based 第 8 位 → N=7；表名在 1-based 第 15 位 → N=14），
     * 即 0-based 偏移。本提取器的契约是 <b>1-based 字符位置</b>（与 PG {@code Position:} 同口径），
     * 故 <b>N+1</b> 后归属——不做换算就报出去会让用户看到的位置恒差一位。</p>
     *
     * <p><b>为何要先遮蔽</b>（{@link #maskOraclePositions}）：{@code PG_POSITION} 的 {@code \bPosition:} 会顺带命中
     * 本形态里的 {@code position: N}（实测：当前实现正是如此，Oracle 位置一直被提取但<b>标签错标成
     * pg-position 且按 1-based 直接报出</b>）。两个 marker 并存会让单一性门控判成「多信号」而
     * <b>放弃归属</b>——这正是「补了 Oracle marker 反而丢位置」的陷阱，故 Oracle 段先从文本里抹掉。</p>
     */
    private static final Pattern ORACLE_POSITION = Pattern.compile("(?i)\\berror occur at position:\\s*(\\d+)");

    /**
     * T-Round10FU（独立复核 ㉕）：Oracle <b>JDBC 驱动自身</b>的位置形态——{@code , Position : N}
     * （<b>冒号前有空格</b>，故上面两条 Oracle/PG 正则都不命中：实测该文本改前 marker 数为 <b>0</b>）。
     *
     * <p><b>形态来源（jar 字节码实证，非臆造）</b>：{@code oracle.jdbc.OracleDatabaseException.toString()}
     * 的字符串拼接模板——ojdbc11 {@code 23.3.0.23.09} 的 {@code oracle/jdbc/OracleDatabaseException.class}
     * 常量池 {@code #60} = {@code Error : , Position : , SQL =  , Original SQL = , Error Message = }，
     * 故产出形如
     * {@code Error : 942, Position : 23, SQL = SELECT … , Original SQL = … , Error Message = ORA-00942: …}。
     * 取值来自驱动内部同一处服务端位置字段（{@code T4CTTIoer11} 的 {@code errorPosition:S}，
     * 构造点 {@code T4CTTIoer11.class} 偏移 416 调 {@code OracleDatabaseException.<init>(IILjava/lang/String;…)}；
     * 该字段 {@code < 0} 时驱动自身即判为「无位置」——本正则的 {@code \d+} 天然不接受负号，同口径）。</p>
     *
     * <p><b>取值语义</b>：与 {@link #ORACLE_POSITION} 同源字段，故同样按 <b>0-based → N+1</b> 归一化（口径见上）。
     * 标签刻意与 {@link #ORACLE_POSITION} 同为 {@code "oracle-position"}：两者是同一服务端字段的两种客户端渲染，
     * 一条消息里若两种形态并存，{@code extractPosition} 的 {@code distinctBy(source to value)} 会把<b>同一读数</b>
     * 归并成一个信号（不误判成多信号而丢位置）；读数不一致时仍按单一性门控放弃归属。</p>
     *
     * <p><b>未决（如实登记）</b>：该形态在驱动里是 {@code SQLException} 的 <b>cause</b>（{@code T4CTTIoer11} 走
     * {@code SQLException.initCause(OracleDatabaseException)}），即 {@code getMessage()} 不含位置、位置只在
     * {@code toString()} 里；我方链投递的是 {@code cause.message ?: cause.toString()}（{@code DataSourceSelector} 三处
     * catch），故<b>在我方链上是否真会走到该文本未实测</b>——与 {@link #ORACLE_POSITION} 属同类未决项。
     * 保留为兜底规则：改前实测该形态 marker 数 0，本规则<b>零误命中面</b>（不产生把无位置变成有位置的虚报）。</p>
     */
    private static final Pattern ORACLE_DRIVER_POSITION = Pattern.compile("(?i),\\s*position\\s*:\\s*(\\d+)");

    /** 单个位置信号：种类标签 + 1-based 值。 */
    private record Marker(String source, int value, boolean isCharPosition) {}

    /**
     * 从错误消息提取位置；无信号 / 多 marker / 越界返回 null（调用方按无位置降级，不猜测）。
     *
     * @param message      错误消息全文
     * @param sqlMaxLength 长度上界（用于越界钳制）；null = 不钳制。IDE 链传整篇控制台文本长度（粗上界，见类 KDoc 的口径订正）
     * @return Triple(位置值, 是否字符位置, 来源标签)；无位置 null
     */
    @Nullable
    public static kotlin.Triple<Integer, Boolean, String> extractPosition(
            @org.jetbrains.annotations.NotNull String message,
            @Nullable Integer sqlMaxLength) {
        List<Marker> markers = collectMarkers(message);
        // marker 单一性门控：恰好一个才归属（DBX take_message_position 同款）
        List<Marker> distinct = new ArrayList<>();
        for (Marker m : markers) {
            boolean dup = false;
            for (Marker d : distinct) {
                if (d.source().equals(m.source()) && d.value() == m.value()) { dup = true; break; }
            }
            if (!dup) distinct.add(m);
        }
        if (distinct.size() != 1) return null;
        Marker m = distinct.get(0);
        int value = m.value();
        if (value < 1) return null;
        if (sqlMaxLength != null) {
            int max = sqlMaxLength;
            if (max < 1) return null;
            value = Math.min(value, max);
        }
        return new kotlin.Triple<>(value, m.isCharPosition(), m.source());
    }

    /** 仅 message（Kotlin 默认参数 {@code sqlMaxLength = null} 的等价形态）。 */
    @Nullable
    public static kotlin.Triple<Integer, Boolean, String> extractPosition(@org.jetbrains.annotations.NotNull String message) {
        return extractPosition(message, null);
    }

    private static List<Marker> collectMarkers(String message) {
        List<Marker> out = new ArrayList<>();
        Matcher oracle = ORACLE_POSITION.matcher(message);
        while (oracle.find()) {
            out.add(new Marker("oracle-position", Integer.parseInt(oracle.group(1)) + 1, true));
        }
        // T-Round10FU（独立复核 ㉕）：驱动 toString 形态（`, Position : N`）。不需遮蔽——
        // PG 的 `\bPosition:` 的 `\s*` 在冒号**之后**，结构上匹配不到 `Position :`（实测 0 命中）。
        Matcher driver = ORACLE_DRIVER_POSITION.matcher(message);
        while (driver.find()) {
            out.add(new Marker("oracle-position", Integer.parseInt(driver.group(1)) + 1, true));
        }
        String rest = maskOraclePositions(message);
        Matcher pg = PG_POSITION.matcher(rest);
        while (pg.find()) out.add(new Marker("pg-position", Integer.parseInt(pg.group(1)), true));
        Matcher atLine = AT_LINE.matcher(rest);
        while (atLine.find()) out.add(new Marker("mysql-at-line", Integer.parseInt(atLine.group(1)), false));
        Matcher lineColon = LINE_COLON.matcher(rest);
        while (lineColon.find()) out.add(new Marker("line", Integer.parseInt(lineColon.group(1)), false));
        return out;
    }

    /** 把 Oracle 位置段替换为等长空格（保持其余 marker 的偏移与文本内容不变）。 */
    private static String maskOraclePositions(String message) {
        Matcher m = ORACLE_POSITION.matcher(message);
        List<int[]> spans = new ArrayList<>(); // {matchStart, matchEnd}
        while (m.find()) spans.add(new int[]{m.start(), m.end()});
        if (spans.isEmpty()) return message;
        StringBuilder sb = new StringBuilder(message.length());
        int last = 0;
        for (int[] span : spans) {
            sb.append(message, last, span[0]);
            sb.append(" ".repeat(span[1] - span[0]));
            last = span[1];
        }
        sb.append(message, last, message.length());
        return sb.toString();
    }

    /**
     * 从反射链 catch 点已解包的 cause 构建富信息：SQLException 链取 SQLState/errorCode
     * （cause 链与 nextException 链各走一层，深度有界），位置从消息文本提取。
     */
    @org.jetbrains.annotations.NotNull
    public static ExecutionErrorInfo buildInfo(@Nullable Throwable cause,
                                               @org.jetbrains.annotations.NotNull String message,
                                               @Nullable Integer sqlMaxLength) {
        String sqlState = null;
        Integer errorCode = null;
        Throwable e = cause;
        int depth = 0;
        while (e != null && depth <= 5) {
            if (e instanceof SQLException) {
                SQLException se = (SQLException) e;
                if (sqlState == null && se.getSQLState() != null && !se.getSQLState().isBlank()) {
                    sqlState = se.getSQLState();
                }
                if (errorCode == null && se.getErrorCode() != 0) {
                    errorCode = se.getErrorCode();
                }
                // nextException 链（批量场景第二个 SQLException 常在此）
                SQLException next = se.getNextException();
                int d2 = 0;
                while (next != null && d2 <= 3) {
                    if (sqlState == null && next.getSQLState() != null && !next.getSQLState().isBlank()) {
                        sqlState = next.getSQLState();
                    }
                    if (errorCode == null && next.getErrorCode() != 0) {
                        errorCode = next.getErrorCode();
                    }
                    next = next.getNextException();
                    d2++;
                }
                break;
            }
            e = e.getCause();
            depth++;
        }
        kotlin.Triple<Integer, Boolean, String> pos = extractPosition(message, sqlMaxLength);
        Integer positionChar = null;
        Integer lineInSql = null;
        if (pos != null) {
            if (pos.getSecond()) {
                positionChar = pos.getFirst();
            } else {
                lineInSql = pos.getFirst();
            }
        }
        return new ExecutionErrorInfo(
                message,
                sqlState,
                errorCode,
                positionChar,
                lineInSql,
                pos != null ? pos.getThird() : null,
                sqlMaxLength);
    }

    /** 不带长度钳制的便捷形态（Kotlin 默认参数 {@code sqlMaxLength = null} 的等价形态）。 */
    @org.jetbrains.annotations.NotNull
    public static ExecutionErrorInfo buildInfo(@Nullable Throwable cause, @org.jetbrains.annotations.NotNull String message) {
        return buildInfo(cause, message, null);
    }
}
