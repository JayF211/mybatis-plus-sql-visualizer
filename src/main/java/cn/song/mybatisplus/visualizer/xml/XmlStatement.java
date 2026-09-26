// T-SyncK2JMaster187 增量（O-77 T-EwStatementScope）：新增 rawText 组件
package cn.song.mybatisplus.visualizer.xml;

/**
 * 解析出的语句（id + SqlNode 树 + parameterType 元信息）。
 *
 * @param id             语句 id
 * @param kind           语句类型
 * @param node           SqlNode 树
 * @param parameterType parameterType（可能为 null）
 * @param rawText        O-77 T-EwStatementScope：语句原文（include 展开后的 element.textContent，实体已解码，
 *                       不含标签/属性/注释）——MP 占位按语句域扫描用。null＝非 mapper 解析链构造
 *                       （注解 {@code <script>} 等），调用方回落文件全文
 */
public record XmlStatement(String id, XmlStatementKind kind, SqlNode node, String parameterType, String rawText) {

    /** Kotlin 默认参数等价重载：{@code rawText} 缺省为 {@code null}（非 mapper 解析链构造）。 */
    public XmlStatement(String id, XmlStatementKind kind, SqlNode node, String parameterType) {
        this(id, kind, node, parameterType, null);
    }
}
