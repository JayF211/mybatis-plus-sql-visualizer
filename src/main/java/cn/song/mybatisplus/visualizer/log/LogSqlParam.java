package cn.song.mybatisplus.visualizer.log;

/**
 * 单个日志参数：值 + 类型标注。
 *
 * @param value 参数值文本（MyBatis 打印形态，如 abc、1、2025-01-01 12:00:00.0）；null 表示 null 字面量
 * @param type  类型标注（(Long) 中的 Long）；null 表示无类型标注（如 null 字面量或旧版日志）
 */
public record LogSqlParam(
    String value,
    String type
) {}
