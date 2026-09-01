package cn.song.mybatisplus.visualizer.xml;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

/**
 * mapper 文件解析器（T-M24-01）：JDK DOM 解析（secure-processing，禁 DTD/外部实体），
 * 提取 <mapper> 下的 select/insert/update/delete 语句。
 */
public class XmlMapperParser {

    /** 语句标签集合。 */
    public static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    public XmlMapperModel parse(String fileText) {
        Document document = parseDocument(fileText);
        Element root = document.getDocumentElement();
        if (root == null) {
            throw new XmlParseException("XML 文档为空。");
        }
        if (!root.getTagName().equals("mapper")) {
            throw new XmlParseException("不是 MyBatis mapper 文件：根元素为 <" + root.getTagName() + ">（期望 <mapper>）。");
        }
        List<String> errors = new ArrayList<>();
        String namespaceRaw = root.getAttribute("namespace");
        String namespace = namespaceRaw.isEmpty() ? null : namespaceRaw;

        Map<String, Element> fragments = new LinkedHashMap<>();
        for (Element it : childElements(root)) {
            if (it.getTagName().equals("sql") && !it.getAttribute("id").isEmpty()) {
                fragments.put(it.getAttribute("id"), it);
            }
        }

        List<XmlStatement> statements = childElements(root).stream()
                .filter(it -> STATEMENT_TAGS.contains(it.getTagName()))
                .map(element -> {
                    XmlIncludeExpander.expand(element, fragments, errors);
                    return new XmlStatement(
                            element.getAttribute("id"),
                            kindOf(element.getTagName()),
                            new XmlScriptBuilder().parse(element),
                            element.getAttribute("parameterType").isEmpty() ? null : element.getAttribute("parameterType")
                    );
                })
                .toList();
        return new XmlMapperModel(namespace, statements, errors);
    }

    /** 解析 XML 文本为 DOM（安全配置：禁 DTD/外部实体）。注解 <script> 等片段解析复用（T-M25-02）。 */
    Document parseDocument(String fileText) {
        try {
            return newDocumentBuilder().parse(new InputSource(new StringReader(fileText)));
        } catch (Throwable t) {
            throw new XmlParseException("XML 解析失败：" + t.getMessage());
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

    private static XmlStatementKind kindOf(String tagName) {
        return switch (tagName) {
            case "select" -> XmlStatementKind.SELECT;
            case "insert" -> XmlStatementKind.INSERT;
            case "update" -> XmlStatementKind.UPDATE;
            default -> XmlStatementKind.DELETE;
        };
    }

    private DocumentBuilder newDocumentBuilder() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Throwable ignored) {
        }
        // 允许 DOCTYPE 声明（MyBatis mapper 常见官方 DTD 声明）；XXE 防护由三重组合承担
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        } catch (Throwable ignored) {
        }
        try {
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Throwable ignored) {
        }
        try {
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Throwable ignored) {
        }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(true);
        try {
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
        } catch (Throwable ignored) {
        }
        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setEntityResolver((String publicId, String systemId) -> new InputSource(new StringReader("")));
            return db;
        } catch (Throwable t) {
            throw new XmlParseException("XML 解析失败：" + t.getMessage());
        }
    }
}
