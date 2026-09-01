package cn.song.mybatisplus.visualizer.xml;

import java.util.List;

/**
 * <pages> 文件解析结果。
 *
 * @param statements maintainPage 语句清单
 */
public record PagesModel(List<PagesStatement> statements) {
}
