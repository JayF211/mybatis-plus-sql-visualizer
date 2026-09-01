package cn.song.mybatisplus.visualizer.xml;

/**
 * 解析出的语句（id + SqlNode 树 + parameterType 元信息）。
 *
 * @param id             语句 id
 * @param kind           语句类型
 * @param node           SqlNode 树
 * @param parameterType parameterType（可能为 null）
 */
public record XmlStatement(String id, XmlStatementKind kind, SqlNode node, String parameterType) {
}
