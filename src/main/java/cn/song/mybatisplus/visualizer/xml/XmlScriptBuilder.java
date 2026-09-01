package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 脚本构建器（T-M24-01）：复刻 MyBatis 3.5.19 XMLScriptBuilder 的 parseDynamicTags——
 * 把语句元素递归解析为 SqlNode 树。
 *
 * - 文本 / CDATA 节点 → TextSqlNode（含 ${}）或 StaticTextSqlNode；
 * - 元素节点 → 按标签名分派 handler（trim/where/set/foreach/if/choose/bind；
 *   when/otherwise 在 choose 内由 choose 专门处理）；
 * - 未知标签 → XmlScriptException（对齐 MyBatis "Unknown element" 行为）；
 * - 任何 XML 元素都令语句为动态。
 */
public class XmlScriptBuilder {

    public SqlNode parse(Element statement) {
        return new MixedSqlNode(parseDynamicTags(statement).contents);
    }

    private static final class DynamicResult {
        final List<SqlNode> contents;

        DynamicResult(List<SqlNode> contents) {
            this.contents = contents;
        }
    }

    private DynamicResult parseDynamicTags(Node node) {
        List<SqlNode> contents = new ArrayList<>();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                switch (element.getTagName()) {
                    case "trim" -> parseTrim(element, contents);
                    case "where" -> contents.add(new WhereSqlNode(new MixedSqlNode(parseDynamicTags(element).contents)));
                    case "set" -> contents.add(new SetSqlNode(new MixedSqlNode(parseDynamicTags(element).contents)));
                    case "foreach" -> parseForeach(element, contents);
                    case "if" -> contents.add(new IfSqlNode(new MixedSqlNode(parseDynamicTags(element).contents), element.getAttribute("test")));
                    case "choose" -> parseChoose(element, contents);
                    case "when" -> contents.add(new IfSqlNode(new MixedSqlNode(parseDynamicTags(element).contents), element.getAttribute("test")));
                    case "otherwise" -> contents.add(new MixedSqlNode(parseDynamicTags(element).contents));
                    case "bind" -> contents.add(new VarDeclSqlNode(element.getAttribute("name"), element.getAttribute("value")));
                    default -> throw new XmlScriptException("Unknown element <" + element.getTagName() + "> in SQL statement.");
                }
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String data = child.getNodeValue() != null ? child.getNodeValue() : "";
                if (TextSqlNode.isDynamic(data)) {
                    contents.add(new TextSqlNode(data));
                } else {
                    contents.add(new StaticTextSqlNode(data));
                }
            }
        }
        return new DynamicResult(contents);
    }

    private void parseTrim(Element element, List<SqlNode> contents) {
        contents.add(new TrimSqlNode(
                new MixedSqlNode(parseDynamicTags(element).contents),
                element.getAttribute("prefix").isEmpty() ? null : element.getAttribute("prefix"),
                splitOverrides(element.getAttribute("prefixOverrides")),
                element.getAttribute("suffix").isEmpty() ? null : element.getAttribute("suffix"),
                splitOverrides(element.getAttribute("suffixOverrides"))
        ));
    }

    private static List<String> splitOverrides(String raw) {
        if (raw != null && !raw.isBlank()) {
            return Arrays.asList(raw.split("\\|"));
        }
        return null;
    }

    private void parseForeach(Element element, List<SqlNode> contents) {
        contents.add(new ForeachSqlNode(
                new MixedSqlNode(parseDynamicTags(element).contents),
                element.getAttribute("collection"),
                element.getAttribute("separator").isEmpty() ? null : element.getAttribute("separator"),
                element.getAttribute("open").isEmpty() ? null : element.getAttribute("open"),
                element.getAttribute("close").isEmpty() ? null : element.getAttribute("close"),
                element.getAttribute("item").isEmpty() ? null : element.getAttribute("item"),
                element.getAttribute("index").isEmpty() ? null : element.getAttribute("index"),
                "true".equals(element.getAttribute("nullable"))
        ));
    }

    private void parseChoose(Element element, List<SqlNode> contents) {
        List<SqlNode> whenNodes = new ArrayList<>();
        List<SqlNode> defaultNodes = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element e) {
                if (e.getTagName().equals("when")) {
                    whenNodes.add(new IfSqlNode(new MixedSqlNode(parseDynamicTags(e).contents), e.getAttribute("test")));
                } else if (e.getTagName().equals("otherwise")) {
                    defaultNodes.add(new MixedSqlNode(parseDynamicTags(e).contents));
                }
            }
        }
        if (defaultNodes.size() > 1) {
            throw new XmlScriptException("Too many default (otherwise) elements in choose statement.");
        }
        contents.add(new ChooseSqlNode(whenNodes, defaultNodes.isEmpty() ? null : defaultNodes.get(0)));
    }
}
