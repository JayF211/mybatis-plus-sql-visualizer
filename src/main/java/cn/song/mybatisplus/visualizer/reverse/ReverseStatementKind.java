package cn.song.mybatisplus.visualizer.reverse;

/** 反向转换语句类型（T-M36.7）：增删改查全支持。 */
public enum ReverseStatementKind {
    /** 查询。 */
    SELECT,
    /** 更新。 */
    UPDATE,
    /** 删除。 */
    DELETE,
    /** 插入。 */
    INSERT
}
