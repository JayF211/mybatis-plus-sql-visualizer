package cn.song.mybatisplus.visualizer.reverse;

/**
 * UNION / UNION ALL 子查询（T-SqlToJavaPromote M1）：keyword 为 UNION 或 UNION ALL，sql 为子查询原文。
 * 与正向模型 {@link cn.song.mybatisplus.visualizer.model.UnionClause} 对称——桥接直转。
 *
 * @param keyword 联结关键字（UNION / UNION ALL）
 * @param sql     子查询原文（首尾括号已剥除，渲染时按 {@code UNION (子查询)} 单层括号）
 */
public record ReverseUnion(String keyword, String sql) {
}
