package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

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
}
