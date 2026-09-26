package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * foreach item 占位键类型派生测试（O-78 T-ForeachItemRender）：
 * item 键 `__frch_<item>_<n>` 不在 paramTypes（表按方法签名生成），引号/类别应经
 * forEachViews 反查 collection 表达式取 [ParamTypeInfo.elementKind]；
 * 旧布尔通道（paramTypes 空）与 dotted 子路径保持 null 兜底。
 */
class XmlPreviewModelForeachItemTest {

    private val mapper = """
        <mapper namespace="n">
          <select id="q">
            SELECT * FROM t_order
            <where>
              <if test="statusList != null and statusList.size() > 0">
                AND status IN
                <foreach collection="statusList" item="st" open="(" separator="," close=")">#{st}</foreach>
              </if>
            </where>
          </select>
        </mapper>
    """.trimIndent()

    private fun model(
        paramTypes: Map<String, ParamTypeInfo> = mapOf(
            "statusList" to ParamTypeInfo(ParamKind.COLLECTION, true, ParamKind.NUMBER),
        ),
        paramQuotes: Map<String, Boolean> = emptyMap(),
    ): XmlPreviewModel = XmlMapperParser().parse(mapper).let {
        XmlPreviewModel.from(it.statements.single(), it.errors, setOf(), mapOf(), mapOf(), paramQuotes, paramTypes)
    }

    @Test
    fun `item占位键引号与类别由集合元素类别派生`() {
        val m = model()
        assertEquals("NUMBER 元素应裸渲染（引号=false）", false, m.quoteFor("__frch_st_0"))
        assertEquals("键尾数字段任意下标同判", false, m.quoteFor("__frch_st_3"))
        assertEquals(ParamKind.NUMBER, m.kindFor("__frch_st_0"))
    }

    @Test
    fun `TEXT元素仍引号`() {
        val m = model(
            mapOf("statusList" to ParamTypeInfo(ParamKind.COLLECTION, true, ParamKind.TEXT)),
        )
        assertEquals("TEXT 元素保持引号", true, m.quoteFor("__frch_st_0"))
        assertEquals(ParamKind.TEXT, m.kindFor("__frch_st_0"))
    }

    @Test
    fun `无元素类别信息时item键保持null兜底`() {
        val m = model(mapOf("statusList" to ParamTypeInfo(ParamKind.COLLECTION)))
        assertNull("集合无 elementKind → null（面板兜底引号，行为=升级前）", m.quoteFor("__frch_st_0"))
        assertEquals(ParamKind.UNKNOWN, m.kindFor("__frch_st_0"))
    }

    @Test
    fun `item名不在foreach视图时保持null兜底`() {
        val m = model()
        assertNull(m.quoteFor("__frch_other_0"))
        assertEquals(ParamKind.UNKNOWN, m.kindFor("__frch_other_0"))
    }

    @Test
    fun `旧布尔通道paramTypes空时行为不变`() {
        val m = model(paramTypes = emptyMap(), paramQuotes = mapOf("statusList" to false))
        assertEquals("显式布尔通道自身照常命中", false, m.quoteFor("statusList"))
        assertNull("item 键不命中旧通道（P2）", m.quoteFor("__frch_st_0"))
    }

    @Test
    fun `dotted子路径不接元素类别`() {
        val m = model()
        assertNull(m.quoteFor("__frch_st_0.name"))
        assertEquals(ParamKind.UNKNOWN, m.kindFor("__frch_st_0.name"))
    }
}
