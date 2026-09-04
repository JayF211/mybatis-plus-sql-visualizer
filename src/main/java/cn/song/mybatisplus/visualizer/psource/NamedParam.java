package cn.song.mybatisplus.visualizer.psource;

/**
 * 命名参数（NamedParameterJdbcTemplate `:name` 风格）。
 *
 * @param name        参数名（去冒号，如 `CUR_USER`、`flowKey`、`1`）
 * @param quotedLike  引号提示：true=String/时间语义（引号），false=数值/语义名（裸渲染）
 */
public record NamedParam(
        String name,
        boolean quotedLike
) {
}
