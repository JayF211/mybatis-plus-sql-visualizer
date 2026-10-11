package cn.song.mybatisplus.visualizer.log;

/** 导出行（面板行集 → 导出的中性投影）。 */
public record SqlExportRow(
        /** 展示时间文本。 */
        String time,
        /** SQL 原文（restoredSql）。 */
        String sql,
        /** 来源标签（空串 = 无来源）。 */
        String source,
        /** 执行耗时（毫秒）；null = 未解析到。 */
        Long durationMs
) {
    /** Kotlin 默认参数 durationMs = null 的重载等价。 */
    public SqlExportRow(String time, String sql, String source) {
        this(time, sql, source, null);
    }
}
