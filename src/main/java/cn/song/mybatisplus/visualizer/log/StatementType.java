package cn.song.mybatisplus.visualizer.log;

/**
 * SQL 语句类型分类器（P-06，纯逻辑，可独立单测）：按 restoredSql 首 token 分类。
 */
public enum StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    OTHER,
}
