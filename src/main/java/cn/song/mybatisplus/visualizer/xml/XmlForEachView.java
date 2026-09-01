package cn.song.mybatisplus.visualizer.xml;

/**
 * foreach 视图：collection 表达式（面板集合值/null 控件按表达式定位）+ item 名（T-M25.4 集合值回填键前缀）。
 *
 * @param collectionExpr collection 表达式
 * @param item           item 名（可能为 null）
 */
public record XmlForEachView(String collectionExpr, String item) {
}
