package cn.song.mybatisplus.visualizer.xml;

/**
 * 列元数据。
 *
 * @param id       列 id
 * @param title    标题
 * @param dataType 数据类型
 * @param visible  是否可见
 * @param sortable 是否可排序
 */
public record PagesColumn(String id, String title, String dataType, boolean visible, boolean sortable) {
}
