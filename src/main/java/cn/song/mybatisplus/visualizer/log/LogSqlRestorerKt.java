package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code LogSqlRestorer.kt} 顶层扩展函数（{@code LogSqlParam.toHint()}）的 Java 承载类。
 */
public final class LogSqlRestorerKt {
    private LogSqlRestorerKt() {
    }

    /**
     * 日志参数 → 回填提示（T-M42）：数值/布尔原样直出（{@link LogSqlRestorer#PLAIN_TYPES}），
     * 其余类型标注引号（对齐 {@link LogSqlRestorer} 还原语义）；无类型标注走面板启发式（null）。
     * 供 {@code LogSqlEntry.toRecognizedPreview} 识别参数时生成参数行回填提示。
     */
    public static ParamHint toHint(LogSqlParam param) {
        if (param.type() == null) {
            return new ParamHint(null, null, false);
        }
        if (LogSqlRestorer.PLAIN_TYPES.contains(param.type())) {
            return new ParamHint(false, null, false);
        }
        return new ParamHint(true, null, false);
    }

    /**
     * string-aware `?` 占位符定位（T-HibDualCapture）：单引号字符串字面量（{@code ''} 转义）与
     * {@code --} 行注释、块注释内的 {@code ?} 不算占位符——中文问句、PG {@code ?|} 操作符不再被
     * 误计数/误回填。还原（LogSqlRestorer.restore）与预览识别计数共用同一口径，避免错位。
     * 双引号标识符内的 {@code ?} 不在保护范围（标准 SQL 标识符不含 {@code ?}，误伤面可忽略）。
     */
    public static List<Integer> placeholderPositions(String sql) {
        List<Integer> positions = new ArrayList<>();
        boolean inString = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
            } else {
                if (c == '\'') {
                    inString = true;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') i++;
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                    i++;
                } else if (c == '?') {
                    positions.add(i);
                }
            }
            i++;
        }
        return positions;
    }
}
