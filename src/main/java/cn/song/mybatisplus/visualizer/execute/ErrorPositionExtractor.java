// T-SyncK2JMaster309 自 execute/ExecutionErrorInfo.kt（ErrorPositionExtractor 对象）移植
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
 * <p>越界钳制：调用方传入被执行 SQL 长度时，位置钳到 [1, sqlMaxLength]；钳后越界（&lt;=0）返回 null。</p>
 */
public final class ErrorPositionExtractor {

    public static final ErrorPositionExtractor INSTANCE = new ErrorPositionExtractor();
    private ErrorPositionExtractor() {}

    /** 位置信号形态：PG pgjdbc 把 server 错误的 Position 拼进 message（`…  Position: 42`）。 */
    private static final Pattern PG_POSITION = Pattern.compile("(?i)\\bPosition:\\s*(\\d+)");

    /** 行号形态：MySQL 语法错误（`… near '…' at line 1`）与 PG 旧形态（`At line N`）。 */
    private static final Pattern AT_LINE = Pattern.compile("(?i)\\bat line (\\d+)");

    /** 行号形态：`Line: N`（部分驱动/方言的详细错误附行号冒号形态）。 */
    private static final Pattern LINE_COLON = Pattern.compile("(?i)\\bline:\\s*(\\d+)");

    /** 单个位置信号：种类标签 + 1-based 值。 */
    private record Marker(String source, int value, boolean isCharPosition) {}

    /**
     * 从错误消息提取位置；无信号 / 多 marker / 越界返回 null（调用方按无位置降级，不猜测）。
     *
     * @param message       错误消息全文
     * @param sqlMaxLength  被执行 SQL 的长度（用于越界钳制）；null = 不钳制
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
        Matcher pg = PG_POSITION.matcher(message);
        while (pg.find()) out.add(new Marker("pg-position", Integer.parseInt(pg.group(1)), true));
        Matcher atLine = AT_LINE.matcher(message);
        while (atLine.find()) out.add(new Marker("mysql-at-line", Integer.parseInt(atLine.group(1)), false));
        Matcher lineColon = LINE_COLON.matcher(message);
        while (lineColon.find()) out.add(new Marker("line", Integer.parseInt(lineColon.group(1)), false));
        return out;
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
                pos != null ? pos.getThird() : null);
    }

    /** 不带长度钳制的便捷形态（Kotlin 默认参数 {@code sqlMaxLength = null} 的等价形态）。 */
    @org.jetbrains.annotations.NotNull
    public static ExecutionErrorInfo buildInfo(@Nullable Throwable cause, @org.jetbrains.annotations.NotNull String message) {
        return buildInfo(cause, message, null);
    }
}
