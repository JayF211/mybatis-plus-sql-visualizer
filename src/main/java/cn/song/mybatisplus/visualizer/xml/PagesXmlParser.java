package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * maintainPage XML 解析器：解析 <pages> 根元素下的 <maintainPage> 语句清单。
 *
 * - 根元素必须是 <pages>（否则抛 PagesXmlParseException）
 * - 遍历 <maintainPage> 子元素，提取 id/title/autoQuery/entityClass 属性
 * - <datasource> CDATA → sql（trim）
 * - <columns>/<column> → 列元数据列表
 */
public class PagesXmlParser {

    public PagesModel parse(String fileText) {
        Document document = parseDocument(fileText);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new PagesXmlParseException("XML 文档为空。");
        }
        if (!root.getTagName().equals("pages")) {
            throw new PagesXmlParseException("不是 maintainPage 文件：根元素为 <" + root.getTagName() + ">（期望 <pages>）。");
        }
        List<PagesStatement> statements = childElements(root).stream()
                .filter(it -> it.getTagName().equals("maintainPage"))
                .map(PagesXmlParser::parseMaintainPage)
                .toList();
        return new PagesModel(statements);
    }

    private static PagesStatement parseMaintainPage(Element element) {
        String id = element.getAttribute("id");
        String title = element.getAttribute("title");
        boolean autoQuery = "true".equals(element.getAttribute("autoQuery"));
        String entityClass = element.getAttribute("entityClass");
        // <datasource> CDATA → sql
        Element datasourceTag = childElements(element).stream()
                .filter(it -> it.getTagName().equals("datasource"))
                .findFirst()
                .orElse(null);
        String sql = datasourceTag != null && datasourceTag.getTextContent() != null
                ? datasourceTag.getTextContent().trim() : "";
        // <columns>/<column> → 列元数据
        Element columnsTag = childElements(element).stream()
                .filter(it -> it.getTagName().equals("columns"))
                .findFirst()
                .orElse(null);
        List<PagesColumn> columns = columnsTag != null ? parseColumns(columnsTag) : List.of();
        return new PagesStatement(id, title, autoQuery, entityClass, sql, columns);
    }

    private static List<PagesColumn> parseColumns(Element columnsTag) {
        return childElements(columnsTag).stream()
                .filter(it -> it.getTagName().equals("column"))
                .map(element -> new PagesColumn(
                        element.getAttribute("id"),
                        element.getAttribute("title"),
                        element.getAttribute("dataType"),
                        "true".equals(element.getAttribute("visible")),
                        "true".equals(element.getAttribute("sortable"))
                ))
                .toList();
    }

    /** 解析 XML 文本为 DOM（安全配置见 {@link SecureXml}：与 mapper 解析器同一实现，含膨胀预扫）。 */
    Document parseDocument(String fileText) {
        try {
            return SecureXml.parse(fileText);
        } catch (Throwable t) {
            throw new PagesXmlParseException("XML 解析失败：" + t.getMessage());
        }
    }

    private static List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                result.add(e);
            }
        }
        return result;
    }
}
