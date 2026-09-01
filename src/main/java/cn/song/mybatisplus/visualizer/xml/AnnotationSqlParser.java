package cn.song.mybatisplus.visualizer.xml;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 注解 SQL 解析器（T-M25-02）：MyBatis 注解（@Select/@Insert/@Update/@Delete 及 Provider 折叠结果）
 * 的 SQL 文本 → 预览模型。纯逻辑（输入已按 MyBatis 拼接规则处理的文本）。
 *
 * 构建规则对齐 mybatis-3.5.19 源码：
 * - startsWith("<script>") → 包 <script> 根走 DOM 解析 + XmlScriptBuilder；
 * - 否则纯文本节点（TextSqlNode：${}/#{} 语义与 XML 文本一致）。
 */
public class AnnotationSqlParser {

    /**
     * @param sqlText   注解 SQL 文本
     * @param kind      语句类型
     * @param paramNames 方法参数 mock 名（@Param 值优先 + 实际名 + param1..N）
     * @param staticFieldValues 静态字段值（T-M25.2）：@class@field → 值
     */
    public XmlPreviewModel parse(
            String sqlText,
            XmlStatementKind kind,
            List<String> paramNames,
            Map<String, Object> staticFieldValues
    ) {
        SqlNode node;
        if (sqlText.startsWith("<script>")) {
            node = parseScript(sqlText);
        } else {
            node = new MixedSqlNode(List.of(new TextSqlNode(sqlText)));
        }
        XmlStatement statement = new XmlStatement("annotation", kind, node, null);
        return XmlPreviewModel.from(statement, List.of(), new HashSet<>(paramNames), staticFieldValues, Map.of(), Map.of());
    }

    /** 注解 SQL 解析（staticFieldValues 默认空映射）。 */
    public XmlPreviewModel parse(String sqlText, XmlStatementKind kind, List<String> paramNames) {
        return parse(sqlText, kind, paramNames, Map.of());
    }

    private SqlNode parseScript(String scriptText) {
        Document document = new XmlMapperParser().parseDocument(scriptText);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new XmlScriptException("script 内容为空。");
        }
        if (!root.getTagName().equals("script")) {
            throw new XmlScriptException("注解 SQL 以 <script> 开头但根元素为 <" + root.getTagName() + ">。");
        }
        return new XmlScriptBuilder().parse(root);
    }
}
