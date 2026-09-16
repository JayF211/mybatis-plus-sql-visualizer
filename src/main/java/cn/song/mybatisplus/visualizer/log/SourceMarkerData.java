package cn.song.mybatisplus.visualizer.log;

/**
 * 标记行解析结果（M56 T-M56-00）：3 段基础位置 + 可选的第 4/5 段扩展。
 * mapperStatement → {@link LogSqlEntry#mapperStatement()}，requestTag → {@link LogSqlEntry#requestTag()}。
 */
public record SourceMarkerData(
        /** 相对项目根的源文件路径（正斜杠）。 */
        String file,
        /** 源码行号。 */
        int line,
        /** 第 4 段：mapper 语句 id；缺段 null。 */
        String mapperStatement,
        /** 第 5 段：请求级聚合标签；缺段 null。 */
        String requestTag
) {
    public SourceMarkerData(String file, int line) {
        this(file, line, null, null);
    }
}
