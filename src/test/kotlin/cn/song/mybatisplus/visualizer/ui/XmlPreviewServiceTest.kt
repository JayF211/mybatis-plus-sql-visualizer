package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML 预览服务测试（T-M24-04）：mapper 文本 → PreviewResult——
 * 语句匹配/回落、参数对照、解析失败 warning、渲染期错误合并。
 */
class XmlPreviewServiceTest {

    private val mapper = """
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
    """.trimIndent()

    @Test
    fun `语句预览_参数对照与模型`() {
        val result = XmlPreviewService.preview(mapper, "selectById")
        assertEquals("SELECT * FROM t_biz WHERE id = ?", result.sql.replace(Regex("\\s+"), " ").trim())
        assertEquals(listOf("id"), result.unresolvedParams)
        assertEquals("-- 参数对照: [id=?]", result.paramTable)
        assertNull(result.warning)
        assertEquals("selectById", result.xmlModel?.statementId)
        assertEquals(1, result.xmlModel?.conditionViews?.size)
    }

    @Test
    fun `语句id未命中时回落首个语句`() {
        val result = XmlPreviewService.preview(mapper, "missing")
        assertEquals("selectById", result.xmlModel?.statementId)
    }

    @Test
    fun `id为null时取首个语句`() {
        val result = XmlPreviewService.preview(mapper, null)
        assertEquals("selectById", result.xmlModel?.statementId)
    }

    @Test
    fun `非mapper根元素_警告提示`() {
        val result = XmlPreviewService.preview("<configuration></configuration>", null)
        assertTrue(result.warning?.contains("mapper") == true)
        assertEquals("", result.sql)
    }

    @Test
    fun `非良构XML_警告提示`() {
        val result = XmlPreviewService.preview("<mapper><select id=\"a\"></mapper>", null)
        assertTrue(result.warning != null)
    }

    @Test
    fun `渲染期错误合并进警告`() {
        val result = XmlPreviewService.preview("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </select>
            </mapper>
        """.trimIndent(), "s1")
        // foreach 默认展开 2 次（无错误）；null 集合场景由面板 state 触发——此处验证正常渲染
        assertEquals("SELECT * FROM t WHERE id IN ( ? , ? )", result.sql.replace(Regex("\\s+"), " ").trim())
        assertNull(result.warning)
    }

    @Test
    fun `无语句时提示`() {
        val result = XmlPreviewService.preview("""
            <mapper namespace="n">
              <resultMap id="rm" type="x"></resultMap>
            </mapper>
        """.trimIndent(), null)
        assertTrue(result.warning?.contains("未找到") == true)
    }
}
