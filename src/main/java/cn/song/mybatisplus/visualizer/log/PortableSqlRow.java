package cn.song.mybatisplus.visualizer.log;

/**
 * 可移植导出行：一条语句 + 方言形态标注三字段
 * （{@link SqlExportService#formatPortable} 消费）。
 */
public record PortableSqlRow(
        /** 来源方言名（{@code Dialect.getName()}，如 {@code MySQL} / {@code H2}）。 */
        String dialect,
        /** 语句种类。 */
        PortableSqlKind kind,
        /** upsert 形态标注（{@link PortableSqlExport} 四常量之一）。 */
        String upsert,
        /** 语句本体（导出不改写）。 */
        String sql
) {
}
