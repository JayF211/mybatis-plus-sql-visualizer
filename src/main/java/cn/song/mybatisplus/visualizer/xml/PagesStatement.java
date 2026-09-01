package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * maintainPage 语句（id + 元数据 + SQL + 列信息）。
 *
 * @param id         语句 id
 * @param title      标题
 * @param autoQuery  是否自动查询
 * @param entityClass 实体类名
 * @param sql        SQL
 * @param columns    列信息
 */
public record PagesStatement(String id, String title, boolean autoQuery, String entityClass, String sql, List<PagesColumn> columns) {
}
