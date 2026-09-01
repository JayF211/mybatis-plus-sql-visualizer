package cn.song.mybatisplus.visualizer.model;

/**
 * 支持的 Wrapper 类型（F-01/09/10/11/12/17）。
 *
 * M1 仅实现 LAMBDA_QUERY；NESTED 用于 and/or 嵌套 lambda 的内部链标记。
 * T-M21-08：MPJ 官方 UPDATE_JOIN/DELETE_JOIN（UpdateJoinWrapper / DeleteJoinWrapper，
 * 1.4.5+ 联表更新/删除，原 P3-01 提前）——UPDATE t LEFT JOIN ... ON ... SET ... WHERE ... /
 * DELETE t,t1 FROM ... LEFT JOIN ... WHERE ... 形态，由 UpdateJoin/DeleteJoinConverter 转换。
 */
public enum WrapperType {
    LAMBDA_QUERY,
    LAMBDA_UPDATE,
    LAMBDA_DELETE,
    MPJ_LAMBDA,
    MPJ_QUERY,
    MPJ_LAMBDA_QUERY,
    QUERY,
    UPDATE,
    /** T-M21-08：UpdateJoinWrapper（MPJ 官方联表更新，1.4.5+）。 */
    UPDATE_JOIN,
    /** T-M21-08：DeleteJoinWrapper（MPJ 官方联表删除，1.4.5+）。 */
    DELETE_JOIN,
    NESTED
}
