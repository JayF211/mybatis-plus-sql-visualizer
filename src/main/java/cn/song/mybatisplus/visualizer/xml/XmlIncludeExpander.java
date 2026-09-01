package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * include 展开器（T-M24-03）：复刻 MyBatis 3.5.19 XMLIncludeTransformer.applyIncludes（同文件形态）。
 *
 * - <include refid="..."> 在加载期内联展开：深拷贝 <sql id> 片段的子节点到 include 位置；
 * - <property name value> 为静态字符串替换：只作用于片段中的 ${name}；
 * - refid 支持 ${name}（property 解析后查表）；跨命名空间 refid → 错误记录并移除 include；
 * - 自引用/过深嵌套 → 错误记录（深度上限 MAX_DEPTH）。
 */
public final class XmlIncludeExpander {

    public static final XmlIncludeExpander INSTANCE = new XmlIncludeExpander();

    /** 嵌套深度上限。 */
    public static final int MAX_DEPTH = 20;

    private XmlIncludeExpander() {
    }

    /** 展开语句元素中的全部 include（就地修改 DOM）。 */
    public static void expand(Element statement, Map<String, Element> fragments, List<String> errors) {
        expandRecursive(statement, fragments, errors, 0, Set.of());
    }

    private static void expandRecursive(
            Element element,
            Map<String, Element> fragments,
            List<String> errors,
            int depth,
            Set<String> chain
    ) {
        if (element.getTagName().equals("include")) {
            if (depth > MAX_DEPTH) {
                errors.add("Include nesting too deep (possible include cycle).");
                if (element.getParentNode() != null) {
                    element.getParentNode().removeChild(element);
                }
                return;
            }
            Map<String, String> props = propertyMap(element);
            String refid = resolveRefid(element.getAttribute("refid"), props);
            if (chain.contains(refid)) {
                errors.add("Include cycle detected: '" + refid + "'.");
                if (element.getParentNode() != null) {
                    element.getParentNode().removeChild(element);
                }
                return;
            }
            Element target = fragments.get(refid);
            if (target == null) {
                errors.add("Include refid '" + refid + "' not found in this mapper (cross-namespace include is not supported).");
                if (element.getParentNode() != null) {
                    element.getParentNode().removeChild(element);
                }
                return;
            }
            List<Node> copies = copyChildren(target, props);
            replaceNode(element, copies);
            for (Node copy : copies) {
                if (copy instanceof Element e) {
                    expandRecursive(e, fragments, errors, depth + 1, addToSet(chain, refid));
                }
            }
            return;
        }
        // 快照遍历（展开会修改子节点集合）
        NodeList children = element.getChildNodes();
        List<Node> snapshot = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            snapshot.add(children.item(i));
        }
        for (Node child : snapshot) {
            if (child instanceof Element e) {
                expandRecursive(e, fragments, errors, depth + 1, chain);
            }
        }
    }

    private static Map<String, String> propertyMap(Element include) {
        Map<String, String> props = new HashMap<>();
        NodeList children = include.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element e && e.getTagName().equals("property")) {
                String name = e.getAttribute("name");
                if (!name.isEmpty()) {
                    props.put(name, e.getAttribute("value"));
                }
            }
        }
        return props;
    }

    /** refid 中的 ${name} 按 property 静态替换（不在 property 中的保留原样）。 */
    private static String resolveRefid(String refid, Map<String, String> props) {
        return TokenParser.parse(refid, "${", "}", s -> props.getOrDefault(s, "${" + s + "}"));
    }

    /** 深拷贝片段子节点：文本节点与属性值中的 ${name} 按 property 替换（不污染原始片段）。 */
    private static List<Node> copyChildren(Element target, Map<String, String> props) {
        List<Node> result = new ArrayList<>();
        NodeList children = target.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node copy = children.item(i).cloneNode(true);
            applyProperties(copy, props);
            result.add(copy);
        }
        return result;
    }

    private static void applyProperties(Node node, Map<String, String> props) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            node.setNodeValue(replaceProps(node.getNodeValue(), props));
        } else if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            NamedNodeMap attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Node attr = attributes.item(j);
                attr.setNodeValue(replaceProps(attr.getNodeValue(), props));
            }
            NodeList children = element.getChildNodes();
            for (int k = 0; k < children.getLength(); k++) {
                applyProperties(children.item(k), props);
            }
        }
    }

    private static String replaceProps(String text, Map<String, String> props) {
        if (text == null) {
            return "";
        }
        return TokenParser.parse(text, "${", "}", s -> props.getOrDefault(s, "${" + s + "}"));
    }

    /** 用拷贝节点替换 include 节点（保持顺序；空片段 → 直接移除 include）。 */
    private static void replaceNode(Element include, List<Node> copies) {
        Node parent = include.getParentNode();
        if (parent == null) {
            return;
        }
        Node anchor = include;
        for (int i = 0; i < copies.size(); i++) {
            Node copy = copies.get(i);
            if (i == 0) {
                parent.replaceChild(copy, anchor);
            } else {
                parent.insertBefore(copy, anchor.getNextSibling());
            }
            anchor = copy;
        }
        if (copies.isEmpty()) {
            parent.removeChild(include);
        }
    }

    private static Set<String> addToSet(Set<String> chain, String refid) {
        Set<String> next = new HashSet<>(chain);
        next.add(refid);
        return next;
    }
}
