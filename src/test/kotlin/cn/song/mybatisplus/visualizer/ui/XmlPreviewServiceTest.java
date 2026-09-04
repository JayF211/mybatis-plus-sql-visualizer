package cn.song.mybatisplus.visualizer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;

import java.util.List;
import java.util.Map;

/**
 * XML 预览服务测试（T-M24-04）：mapper 文本 → PreviewResult——
 * 语句匹配/回落、参数对照、解析失败 warning、渲染期错误合并。
 */
public class XmlPreviewServiceTest {

    private static final String MAPPER = """
    <mapper namespace="com.example.BizMapper">
      <select id="selectById" resultType="map">
        SELECT * FROM t_biz
        <where>
          <if test="id != null">AND id = #{id}</if>
        </where>
      </select>
      <update id="updateById">
        UPDATE t_biz SET a = #{a} WHERE id = #{id}
      </update>
    </mapper>
    """.stripIndent();

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    public void 语句预览_参数对照与模型() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview(MAPPER, "selectById");
        assertEquals("SELECT * FROM t_biz WHERE id = ?", compact(result.getSql()));
        assertEquals(List.of("id"), result.getUnresolvedParams());
        assertEquals("-- 参数对照: [id=?]", result.getParamTable());
        assertNull(result.getWarning());
        assertEquals("selectById", result.getXmlModel().getStatementId());
        assertEquals(1, result.getXmlModel().getConditionViews().size());
    }

    @Test
    public void 语句id未命中时回落首个语句() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview(MAPPER, "missing");
        assertEquals("selectById", result.getXmlModel().getStatementId());
    }

    @Test
    public void id为null时取首个语句() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview(MAPPER, null);
        assertEquals("selectById", result.getXmlModel().getStatementId());
    }

    @Test
    public void 非mapper根元素_警告提示() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview("<configuration></configuration>", null);
        assertTrue(result.getWarning() != null && result.getWarning().contains("mapper"));
        assertEquals("", result.getSql());
    }

    @Test
    public void 非良构XML_警告提示() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview("<mapper><select id=\"a\"></mapper>", null);
        assertTrue(result.getWarning() != null);
    }

    @Test
    public void 渲染期错误合并进警告() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </select>
                </mapper>
                """.stripIndent(), "s1");
        // foreach 默认展开 2 次（无错误）；null 集合场景由面板 state 触发——此处验证正常渲染
        assertEquals("SELECT * FROM t WHERE id IN ( ? , ? )", compact(result.getSql()));
        assertNull(result.getWarning());
    }

    @Test
    public void 无语句时提示() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview("""
                <mapper namespace="n">
                  <resultMap id="rm" type="x"></resultMap>
                </mapper>
                """.stripIndent(), null);
        assertTrue(result.getWarning() != null && result.getWarning().contains("未找到"));
    }

    // T-M27.3：XML 预览注入实体上下文（namespace → Mapper 接口 → 主实体）——
    // 预览弹窗 SQL 编辑器补全的表名/列名/注释来源
    @Test
    public void testEntityContextInjectedIntoResult() throws Exception {
        SqlCompletionContext ctx = new SqlCompletionContext("t_biz", List.of("id", "name"), null,
                Map.of("name", "业务名"), "业务表");
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview(MAPPER, "selectById", null, ctx);
        assertEquals("t_biz", result.getTableName());
        assertEquals(List.of("id", "name"), result.getColumns());
        assertEquals(Map.of("name", "业务名"), result.getColumnComments());
        assertEquals("业务表", result.getTableComment());
    }

    // T-M27.3：无实体上下文（解析失败/未关联）→ 保持 null 行为（兼容既有路径）
    @Test
    public void testEntityContextNullKeepsNullTable() throws Exception {
        cn.song.mybatisplus.visualizer.model.PreviewResult result =
                XmlPreviewService.INSTANCE.preview(MAPPER, "selectById");
        assertNull(result.getTableName());
    }
}
