package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import cn.song.mybatisplus.visualizer.xml.PagesColumn;

import java.util.List;

/**
 * PagesXmlPreviewService 测试：XML 文本 → PreviewResult——
 * 语句定位、参数提取、列信息传递、异常路径。
 */
public class PagesXmlPreviewServiceTest {

    private static final String SAMPLE_XML = """
    <pages>
        <maintainPage id="maintainLangLibPage" title="maintainLangLibPage"
                      autoQuery="true" entityClass="com.vtradex.forest.model.lang.TfLangLib">
            <datasource><![CDATA[
            SELECT
                t.id,
                a.name,
                t.name,
                t.lang
              from TfLangLib t
              inner join TFApp a on t.appId = a.id
             WHERE 1=1
               ORDER BY t.id DESC
            ]]></datasource>
            <columns>
                <column id="t.id" title="t.id" visible="false" sortable="true" dataType="long"/>
                <column id="a.name" title="a.name" visible="true" sortable="true" dataType="string"/>
                <column id="t.name" title="t.name" visible="true" sortable="true" dataType="string"/>
                <column id="t.lang" title="t.lang" visible="true" sortable="true" dataType="enum"/>
            </columns>
        </maintainPage>
    </pages>
    """.stripIndent();

    @Test
    public void 标准预览_SQL完整() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        assertTrue(result.getSql().contains("SELECT"));
        assertTrue(result.getSql().contains("TfLangLib"));
        assertTrue(result.getSql().contains("ORDER BY t.id DESC"));
        assertNull(result.getWarning());
    }

    @Test
    public void compactSql_单行() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        String compact = result.getCompactSql();
        // 单行：无换行符
        assertTrue(!compact.contains("\n"));
        assertTrue(compact.contains("SELECT"));
    }

    @Test
    public void 按id定位_命中目标() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                <datasource><![CDATA[SELECT 1]]></datasource>
            </maintainPage>
            <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                <datasource><![CDATA[SELECT 2]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, "page2");
        assertTrue(result.getSql().contains("SELECT 2"));
    }

    @Test
    public void id未命中_回落首个() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                <datasource><![CDATA[SELECT 1]]></datasource>
            </maintainPage>
            <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                <datasource><![CDATA[SELECT 2]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, "missing");
        assertTrue(result.getSql().contains("SELECT 1"));
    }

    @Test
    public void id为null_取首个() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="page1" title="P1" autoQuery="true" entityClass="com.A">
                <datasource><![CDATA[SELECT 1]]></datasource>
            </maintainPage>
            <maintainPage id="page2" title="P2" autoQuery="true" entityClass="com.B">
                <datasource><![CDATA[SELECT 2]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, null);
        assertTrue(result.getSql().contains("SELECT 1"));
    }

    @Test
    public void 无maintainPage_空结果加warning() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview("<pages></pages>", null);
        assertEquals("", result.getSql());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning() != null && result.getWarning().contains("maintainPage"));
    }

    @Test
    public void 非pages根元素_警告提示() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview("<configuration></configuration>", null);
        assertEquals("", result.getSql());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning() != null && result.getWarning().contains("pages"));
    }

    @Test
    public void 非良构XML_警告提示() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview("<pages><maintainPage></pages>", null);
        assertNotNull(result.getWarning());
    }

    @Test
    public void 无参数SQL_参数列表为空() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        assertEquals(List.of(), result.getUnresolvedParams());
        assertNull(result.getParamTable());
    }

    @Test
    public void 含占位符SQL_参数提取正确() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                <datasource><![CDATA[SELECT * FROM t WHERE id = ? AND name = ?]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, "p1");
        assertEquals(List.of("param1", "param2"), result.getUnresolvedParams());
        assertNotNull(result.getParamTable());
    }

    @Test
    public void 列信息传递_pagesColumns非空() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        assertNotNull(result.getPagesColumns());
        assertEquals(4, result.getPagesColumns().size());
        assertEquals("t.id", result.getPagesColumns().get(0).id());
        assertEquals("long", result.getPagesColumns().get(0).dataType());
    }

    @Test
    public void 无列信息_pagesColumns为null() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="p1" title="P1" autoQuery="true" entityClass="com.A">
                <datasource><![CDATA[SELECT 1]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, "p1");
        assertNull(result.getPagesColumns());
    }

    @Test
    public void HQL内容_直接展示无参数() throws Exception {
        String xml = """
        <pages>
            <maintainPage id="hqlPage" title="HQL" autoQuery="true"
                          entityClass="com.example.model.User">
                <datasource><![CDATA[
                select u.name, u.email from User u
              where u.age > 18
              order by u.createdDate desc
                ]]></datasource>
            </maintainPage>
        </pages>
        """.stripIndent();
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(xml, "hqlPage");
        assertTrue(result.getSql().contains("User"));
        assertTrue(result.getSql().contains("order by"));
        // HQL 无占位符 → 参数列表为空
        assertEquals(List.of(), result.getUnresolvedParams());
        assertNull(result.getParamTable());
    }

    @Test
    public void wrapperType为null() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        assertNull(result.getWrapperType());
    }

    @Test
    public void xmlModel和previewModel为null() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                PagesXmlPreviewService.INSTANCE.preview(SAMPLE_XML, "maintainLangLibPage");
        assertNull(result.getXmlModel());
        assertNull(result.getPreviewModel());
    }
}
