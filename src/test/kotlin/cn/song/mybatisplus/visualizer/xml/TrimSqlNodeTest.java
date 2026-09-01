package cn.song.mybatisplus.visualizer.xml;

import org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * trim 族节点测试（T-M24-01）：复刻 MyBatis TrimSqlNode 算法——
 * 整体 trim、前缀/后缀 override 大小写不敏感只删一次、prefix 恒带空格、空内容不输出；
 * where 的换行/回车/制表符变体；set 的逗号处理。
 */
public class TrimSqlNodeTest {

    private static RenderedXmlSql render(SqlNode node) {
        return new XmlSqlRenderer().render(node, Map.of());
    }

    private static SqlNode text(String value) {
        return new MixedSqlNode(List.of(new StaticTextSqlNode(value)));
    }

    // ------------------------------------------------------------------
    // where
    // ------------------------------------------------------------------

    @Test
    public void where_去掉AND前缀并插WHERE() {
        assertEquals("WHERE  id = ?", render(new WhereSqlNode(text(" AND id = #{id}"))).sql());
    }

    @Test
    public void where_OR前缀() {
        assertEquals("WHERE  x = 1", render(new WhereSqlNode(text("OR x = 1"))).sql());
    }

    @Test
    public void where_小写前缀也匹配_大小写不敏感() {
        assertEquals("WHERE  x = 1", render(new WhereSqlNode(text("and x = 1"))).sql());
    }

    @Test
    public void where_换行变体() {
        assertEquals("WHERE \nx = 1", render(new WhereSqlNode(text("AND\nx = 1"))).sql());
        assertEquals("WHERE \tx = 1", render(new WhereSqlNode(text("OR\tx = 1"))).sql());
    }

    @Test
    public void where_只删第一个匹配() {
        assertEquals("WHERE  AND x = 1", render(new WhereSqlNode(text("AND AND x = 1"))).sql());
    }

    @Test
    public void where_内容为空白时不输出() {
        assertEquals("", render(new WhereSqlNode(text("  \n  "))).sql());
    }

    @Test
    public void where_整体trim后再匹配_前后空白被去掉() {
        assertEquals("WHERE  x = 1", render(new WhereSqlNode(text("  AND x = 1  "))).sql());
    }

    // ------------------------------------------------------------------
    // set
    // ------------------------------------------------------------------

    @Test
    public void set_去掉尾逗号() {
        assertEquals("SET a = ?, b = ?", render(new SetSqlNode(text("a = #{a}, b = #{b},"))).sql());
    }

    @Test
    public void set_无尾逗号也正常() {
        assertEquals("SET a = ?", render(new SetSqlNode(text("a = #{a}"))).sql());
    }

    @Test
    public void set_内容为空时不输出() {
        assertEquals("", render(new SetSqlNode(text("   "))).sql());
    }

    @Test
    public void set_前导逗号被去掉() {
        assertEquals("SET a = ?", render(new SetSqlNode(text(",a = #{a}"))).sql());
    }

    // ------------------------------------------------------------------
    // 自定义 trim
    // ------------------------------------------------------------------

    @Test
    public void trim_前缀后缀与overrides() {
        TrimSqlNode node = new TrimSqlNode(text("B x,"), "PREFIX", List.of("A ", "B "), "END", List.of(","));
        assertEquals("PREFIX  x END", render(node).sql());
    }

    @Test
    public void trim_prefix与内容之间恒有一个空格() {
        TrimSqlNode node = new TrimSqlNode(text("x"), "PREFIX", null, null, null);
        assertEquals("PREFIX x", render(node).sql());
    }

    @Test
    public void trim_内容为空时prefix与suffix都不加() {
        TrimSqlNode node = new TrimSqlNode(text("  "), "PREFIX", null, "END", null);
        assertEquals("", render(node).sql());
    }

    // ------------------------------------------------------------------
    // 与 if 组合（条件开关覆盖）
    // ------------------------------------------------------------------

    @Test
    public void where_内层if关闭后内容为空() {
        IfSqlNode inner = new IfSqlNode(new StaticTextSqlNode(" AND id = #{id}"), "id != null");
        TrimSqlNode node = new WhereSqlNode(new MixedSqlNode(List.of(inner)));
        assertEquals("", render(node).sql());
        assertEquals(
                "WHERE  id = ?",
                new XmlSqlRenderer().render(node, Map.of(), Map.of("id != null", true)).sql());
    }
}
