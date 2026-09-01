package cn.song.mybatisplus.visualizer.reverse;

/**
 * SELECT 列项（T-M46 保留列别名）。
 *
 * @param expr  列原文（含表别名前缀与反引号）
 * @param alias 可选 AS 别名
 */
public record SelectColumn(String expr, String alias) {
}
