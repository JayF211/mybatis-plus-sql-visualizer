package cn.song.mybatisplus.visualizer.xml;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertThrows;
import org.junit.Assert.assertTrue;
import org.junit.Test;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 脚本构建器测试（T-M24-01）：DOM → SqlNode 树——
 * 标签分派（if/where/set/trim/foreach/choose/bind）、CDATA 按文本处理、
 * 未知元素与 choose 多 otherwise 报错（对齐 MyBatis parseDynamicTags）。
 */
public class XmlScriptBuilderTest {

    private static Element element(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(("<root>" + xml + "</root>").getBytes()))
                .getDocumentElement();
    }

    private static MixedSqlNode parse(String xml) throws Exception {
        return (MixedSqlNode) new XmlScriptBuilder().parse(element(xml));
    }

    @Test
    public void 文本与if混合解析() throws Exception {
        MixedSqlNode node = parse("SELECT * FROM t<if test=\"a != null\"> WHERE a = 1</if> tail");
        assertEquals(3, node.getChildren().size());
        assertTrue(node.getChildren().get(0) instanceof StaticTextSqlNode);
        IfSqlNode ifNode = (IfSqlNode) node.getChildren().get(1);
        assertEquals("a != null", ifNode.getTest());
        assertTrue(node.getChildren().get(2) instanceof StaticTextSqlNode);
    }

    @Test
    public void 含dollar的文本为动态节点() throws Exception {
        MixedSqlNode node = parse("SELECT ${col} FROM t");
        assertTrue(node.getChildren().get(0) instanceof TextSqlNode);
        assertTrue(TextSqlNode.isDynamic("${col}"));
    }

    @Test
    public void CDATA按纯文本处理() throws Exception {
        MixedSqlNode node = parse("<![CDATA[ WHERE age < 18 ]]>");
        assertEquals(1, node.getChildren().size());
        String text = ((StaticTextSqlNode) node.getChildren().get(0)).getText();
        assertTrue(text.contains("WHERE age < 18"));
    }

    @Test
    public void 各动态标签解析为对应节点() throws Exception {
        MixedSqlNode node = parse(
                "<where>WHERE 1=1</where>" +
                        "<set>SET a=1</set>" +
                        "<trim prefix=\"P\" suffixOverrides=\",\">x,</trim>" +
                        "<foreach collection=\"list\" item=\"x\" separator=\",\">#{x}</foreach>" +
                        "<bind name=\"n\" value=\"'a' + x\"/>" +
                        "<choose><when test=\"t == 1\">A</when><otherwise>B</otherwise></choose>"
        );
        assertEquals(6, node.getChildren().size());
        assertTrue(node.getChildren().get(0) instanceof WhereSqlNode);
        assertTrue(node.getChildren().get(1) instanceof SetSqlNode);
        assertTrue(node.getChildren().get(2) instanceof TrimSqlNode);
        ForeachSqlNode foreach = (ForeachSqlNode) node.getChildren().get(3);
        assertEquals("list", foreach.getCollectionExpression());
        assertTrue(node.getChildren().get(4) instanceof VarDeclSqlNode);
        ChooseSqlNode choose = (ChooseSqlNode) node.getChildren().get(5);
        assertEquals(1, choose.getWhenNodes().size());
        assertTrue(choose.getDefaultNode() != null);
    }

    @Test
    public void choose缺otherwise时default为null() throws Exception {
        MixedSqlNode node = parse("<choose><when test=\"t == 1\">A</when></choose>");
        ChooseSqlNode choose = (ChooseSqlNode) node.getChildren().get(0);
        assertTrue(choose.getDefaultNode() == null);
    }

    @Test
    public void 未知元素报错() {
        assertThrows(XmlScriptException.class, () -> parse("<else>oops</else>"));
    }

    @Test
    public void choose多otherwise报错() {
        assertThrows(XmlScriptException.class, () ->
                parse("<choose><when test=\"a\">A</when><otherwise>B</otherwise><otherwise>C</otherwise></choose>"));
    }

    @Test
    public void foreach属性读取() throws Exception {
        MixedSqlNode node = parse("<foreach collection=\"ids\" item=\"id\" index=\"i\" open=\"(\" close=\")\" separator=\",\" nullable=\"true\">#{id}</foreach>");
        ForeachSqlNode foreach = (ForeachSqlNode) node.getChildren().get(0);
        assertEquals("ids", foreach.getCollectionExpression());
        assertEquals("id", foreach.getItem());
        assertEquals("i", foreach.getIndex());
        assertEquals("(", foreach.getOpen());
        assertEquals(")", foreach.getClose());
        assertEquals(",", foreach.getSeparator());
    }
}
