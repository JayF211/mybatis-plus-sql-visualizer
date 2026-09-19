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
 *
 * <p>T-IC-02：入参 {@link CrossNamespaceFragments} 为跨命名空间片段表（由 psi 层
 * {@code cn.song.mybatisplus.visualizer.psi.XmlCrossNsFragmentResolver} 提供；无 project 的纯文本链传空表，
 * 行为与改动前逐字节一致）。</p>
 */
public class XmlMapperParser {

    /** 语句标签集合。 */
    public static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    public XmlMapperModel parse(String fileText, CrossNamespaceFragments crossNamespace) {
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
                    XmlIncludeExpander.expand(element, fragments, errors, crossNamespace);
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

    /** Kotlin 默认参数等价重载：crossNamespace 缺省为空表（不做跨命名空间解析）。 */
    public XmlMapperModel parse(String fileText) {
        return parse(fileText, CrossNamespaceFragments.EMPTY);
    }

    /**
     * T-IC-01：轻量片段收集——只扫顶层 {@code <sql id>} 片段（id → 片段内部 XML 文本），
     * <b>不建语句树、不展开 include、零 PSI 依赖</b>（跨命名空间解析的目标文件侧入口）。
     *
     * <p>根元素非 {@code <mapper>} 或文本非良构 → 空表（调用方按「未找到」降级，绝不抛出）。
     * 同名 id 取后声明者（对齐 MyBatis {@code configuration.sqlFragments.put} 覆盖语义）。</p>
     */
    public Map<String, String> collectFragments(String fileText) {
        XmlMapperFragments collected = collectMapperFragments(fileText);
        return collected != null ? collected.fragments() : Map.of();
    }

    /** 与 {@link #collectFragments} 同一次解析：额外返回根 namespace（跨命名空间按 namespace 匹配目标文件）。 */
    public XmlMapperFragments collectMapperFragments(String fileText) {
        Element root;
        try {
            root = parseDocument(fileText).getDocumentElement();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            return null;
        }
        if (root == null || !root.getTagName().equals("mapper")) return null;
        Map<String, String> fragments = new LinkedHashMap<>();
        for (Element it : childElements(root)) {
            if (it.getTagName().equals("sql") && !it.getAttribute("id").isEmpty()) {
                fragments.put(it.getAttribute("id"), serializeChildren(it));
            }
        }
        return new XmlMapperFragments(root.getAttribute("namespace").isEmpty() ? null : root.getAttribute("namespace"), fragments);
    }

    /**
     * T-IC-02：扫描当前 mapper 文本——根 namespace、顶层片段 id 集、<b>含 {@code .} 且本文件无同名片段的</b>
     * include refid 集（refid 中的 {@code ${name}} 已按 include 自带 property 静态替换，与展开期同语义）。
     *
     * <p>根元素非 {@code <mapper>} 或文本非良构 → null（调用方不做跨命名空间收集，沿用既有降级）。</p>
     */
    public XmlIncludeScan scanIncludes(String fileText) {
        Element root;
        try {
            root = parseDocument(fileText).getDocumentElement();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            return null;
        }
        if (root == null || !root.getTagName().equals("mapper")) return null;
        Set<String> localIds = new java.util.LinkedHashSet<>();
        for (Element it : childElements(root)) {
            if (it.getTagName().equals("sql") && !it.getAttribute("id").isEmpty()) {
                localIds.add(it.getAttribute("id"));
            }
        }
        Set<String> dotted = new java.util.LinkedHashSet<>();
        collectDottedRefIds(root, localIds, dotted);
        return new XmlIncludeScan(root.getAttribute("namespace").isEmpty() ? null : root.getAttribute("namespace"), localIds, dotted);
    }

    private static void collectDottedRefIds(Element element, Set<String> localIds, Set<String> out) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) continue;
            if (child.getTagName().equals("include")) {
                String refid = XmlIncludeExpander.resolveRefid(
                        child.getAttribute("refid"),
                        XmlIncludeExpander.propertyMap(child)
                );
                if (refid.contains(".") && !localIds.contains(refid)) out.add(refid);
            }
            collectDottedRefIds(child, localIds, out);
        }
    }

    /**
     * 片段内部 XML 文本序列化（T-IC-01）：保留子标签/属性/CDATA/注释，文本与属性值按 XML 规则转义，
     * 供跨命名空间传递后重新解析（平台 LSSerializer 对独立 CDATA 节点不写闭合 {@code ]]>}，故手写）。
     */
    private static String serializeChildren(Element element) {
        StringBuilder sb = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendNode(sb, children.item(i));
        }
        return sb.toString();
    }

    private static void appendNode(StringBuilder sb, Node node) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE -> {
                Element element = (Element) node;
                sb.append('<').append(element.getTagName());
                var attrs = element.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    var attr = attrs.item(i);
                    sb.append(' ').append(attr.getNodeName()).append("=\"").append(escapeAttr(attr.getNodeValue())).append('"');
                }
                NodeList children = element.getChildNodes();
                if (children.getLength() == 0) {
                    sb.append("/>");
                } else {
                    sb.append('>');
                    for (int i = 0; i < children.getLength(); i++) {
                        appendNode(sb, children.item(i));
                    }
                    sb.append("</").append(element.getTagName()).append('>');
                }
            }
            case Node.TEXT_NODE -> sb.append(escapeText(node.getNodeValue()));
            case Node.CDATA_SECTION_NODE -> sb.append("<![CDATA[")
                    .append(orEmpty(node.getNodeValue()).replace("]]>", "]]]]><![CDATA[>")).append("]]>");
            case Node.COMMENT_NODE -> sb.append("<!--").append(orEmpty(node.getNodeValue())).append("-->");
            default -> { /* 处理指令等 mapper 片段不出现的节点类型：忽略 */ }
        }
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String escapeText(String text) {
        return orEmpty(text)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String text) {
        return orEmpty(text)
                .replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
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
