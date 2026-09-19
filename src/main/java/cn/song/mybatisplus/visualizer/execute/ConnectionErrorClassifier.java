// T-SyncK2JMaster309 自 execute/ConnectionErrorClassifier.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import java.util.List;
import java.util.Set;

/**
 * 连接类异常分类器（T-DdlRecover T-DR-01，DBX 吸收清单 #20 语义）：
 * 「失败不等于未生效」——执行 DDL 撞上连接断开时数据库侧可能已隐式提交，先把连接类错误
 * 从普通失败里分出来，供上层做已生效性探测。
 *
 * <p>判定三路（任一命中即连接类，优先级从高到低）：</p>
 * <ul>
 *  <li>SQLState 08xxx（SQL 标准连接异常类，跨驱动最可靠）；</li>
 *  <li>vendor 错误码表（MySQL 通讯族 2002/2003/2006/2013、Oracle JDBC 17002/17008/17410/1089
 *      及 ORA 数字族 3113/3114——JDBC errorCode 通常已是数字形态）；</li>
 *  <li>消息文本签名兜底（对齐既有 extractConsoleError 的字符串签名思路，小写 contains）。</li>
 * </ul>
 */
public final class ConnectionErrorClassifier {

    public static final ConnectionErrorClassifier INSTANCE = new ConnectionErrorClassifier();
    private ConnectionErrorClassifier() {}

    /** vendor 连接族错误码（errorCode 为数值形态；SQLState 不规范的驱动的兜底）。 */
    private static final Set<Integer> VENDOR_CONNECTION_CODES = Set.of(
            2002, 2003, 2006, 2013,          // MySQL: via pipe / refused / gone away / during query
            17002, 17008, 17410, 1089,       // Oracle JDBC: IO exception / closed connection / no more data / session killed
            3113, 3114                       // ORA-03113/03114 数字形态
    );

    /** 消息文本签名兜底（小写 contains；与 vendor 码/SQLState 三路互补）。 */
    private static final List<String> MESSAGE_SIGNATURES = List.of(
            "broken pipe",
            "connection refused",
            "connection reset",
            "connection timed out",
            "communications link failure",
            "closed connection",
            "the connection is closed",
            "connection attempt failed",
            "could not open connection"
    );

    /** 明确排除：连接词出现在非连接语义里（deadlock 是普通执行错误，误判会触发无意义探测）。 */
    private static final List<String> EXCLUSION_SIGNATURES = List.of("deadlock", "access denied", "password");

    public static boolean isConnectionError(@org.jetbrains.annotations.NotNull ExecutionErrorInfo info) {
        // 1. SQLState 08xxx（标准连接异常类）
        if (info.sqlState() != null) {
            String sqlState = info.sqlState();
            if (sqlState.length() >= 2 && sqlState.substring(0, 2).equalsIgnoreCase("08")) return true;
        }
        // 2. vendor 错误码
        if (info.errorCode() != null) {
            if (VENDOR_CONNECTION_CODES.contains(info.errorCode())) return true;
        }
        // 3. 消息签名兜底（排除表先行——"deadlock" 等非连接语义不误判）
        String lower = info.message().toLowerCase();
        for (String s : EXCLUSION_SIGNATURES) {
            if (lower.contains(s)) return false;
        }
        for (String s : MESSAGE_SIGNATURES) {
            if (lower.contains(s)) return true;
        }
        return false;
    }
}
