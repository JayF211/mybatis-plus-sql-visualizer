package cn.song.mybatisplus.visualizer.sql;

/**
 * 光标所在位置的 SQL 子句语义（M16 位置感知补全；T-M27-01 自 {@code SqlCompletionContributor} 迁入本模块）。
 *
 * <ul>
 *   <li>{@link #TABLE}：FROM / JOIN（含 LEFT/RIGHT/INNER/FULL JOIN）之后 → 表名位；</li>
 *   <li>{@link #COLUMN}：WHERE / ON / HAVING / GROUP BY / ORDER BY 之后 → 列名位；</li>
 *   <li>{@link #SELECT_COLUMN}：SELECT 列位（列 + 聚合函数）；</li>
 *   <li>{@link #UNKNOWN}：无法判定 → 全量候选。</li>
 * </ul>
 */
public enum SqlClause {
    TABLE, COLUMN, SELECT_COLUMN, UNKNOWN
}
