package cn.song.mybatisplus.visualizer.execute;

/**
 * UI 三入口共用的纯文本错误呈现；原始消息完整保留，元数据只显示实际存在的有效值。
 *
 * <p>master 的 {@code object ExecutionErrorFormatter} 的 Java 承载（全 static 形态）。</p>
 */
public final class ExecutionErrorFormatter {

    private ExecutionErrorFormatter() {
    }

    public static String format(ExecutionErrorInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(info.message());
        if (info.sqlState() != null && !info.sqlState().isBlank()) {
            sb.append("\nsqlState: ").append(info.sqlState());
        }
        if (info.errorCode() != null && info.errorCode() != 0) {
            sb.append("\nerrorCode: ").append(info.errorCode());
        }
        if (info.positionSource() != null && !info.positionSource().isBlank()) {
            if (info.positionChar() != null && info.positionChar() > 0) {
                sb.append("\nerrorPosition: char ").append(info.positionChar());
            } else if (info.lineInSql() != null && info.lineInSql() > 0) {
                sb.append("\nerrorPosition: line ").append(info.lineInSql());
            }
        }
        return sb.toString();
    }
}
