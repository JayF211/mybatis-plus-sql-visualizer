package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PT-01 / T-PT-03 类型类别契约测试（xml 层，纯逻辑零 PSI/IDE 依赖，进 testFast 快测桶）。
 *
 * 覆盖：
 *  - [ParamKind.quoteRequired] 派生引号视图（NUMBER 裸渲染、其余引号——升级前布尔通道的显式化）；
 *  - `paramTypes` → `paramQuotes` 派生（精确/前缀命中、未命中 null 兜底）；
 *  - mock 占位值按类别分派（NUMBER→1 / TEMPORAL→日期字面量 / COLLECTION→MockIterable /
 *    OBJECT→MockObject / TEXT·UNKNOWN→"1"）；
 *  - 无类型信息（UNKNOWN）行为与升级前逐值一致（防回归）。
 */
class XmlParamKindTest {

    /** 由内联 XML 构建模型：`<bind>` 探针取变量 mock 值（bindValues 出栈即为占位值 toString）。 */
    private fun model(variable: String, paramTypes: Map<String, ParamTypeInfo> = emptyMap()): XmlPreviewModel {
        val xml = """
            <mapper namespace="com.example.ProbeMapper">
              <select id="probe">
                SELECT 1 <bind name="probe" value="$variable"/>
              </select>
            </mapper>
        """.trimIndent()
        val parsed = XmlMapperParser().parse(xml)
        return XmlPreviewModel.from(parsed.statements.single(), parsed.errors, paramTypes = paramTypes)
    }

    private fun bindProbe(kind: ParamKind?): String? {
        val types = if (kind == null) emptyMap() else mapOf("v" to ParamTypeInfo(kind))
        return model("v", types).renderSql().bindValues["probe"]
    }

    // ------------------------------------------------------------------
    // 派生引号视图
    // ------------------------------------------------------------------

    @Test
    fun `quoteRequired_仅NUMBER裸渲染`() {
        assertEquals(false, ParamKind.NUMBER.quoteRequired)
        // TEMPORAL 显式定案：走引号档（升级前 LocalDate 即落引号档，本次升格为显式规则）
        assertEquals(true, ParamKind.TEMPORAL.quoteRequired)
        assertEquals(true, ParamKind.TEXT.quoteRequired)
        assertEquals(true, ParamKind.COLLECTION.quoteRequired)
        assertEquals(true, ParamKind.OBJECT.quoteRequired)
        assertEquals(true, ParamKind.UNKNOWN.quoteRequired)
    }

    @Test
    fun `paramTypes_派生引号视图_与显式布尔通道逐值一致`() {
        val types = mapOf(
            "p" to ParamTypeInfo(ParamKind.OBJECT),
            "p.settlementStartDate" to ParamTypeInfo(ParamKind.TEMPORAL),
            "p.dicCreditStatus" to ParamTypeInfo(ParamKind.NUMBER),
            "keyword" to ParamTypeInfo(ParamKind.TEXT),
        )
        val derived = model("p", types)
        val explicit = XmlPreviewModel.from(
            XmlMapperParser().parse(
                """
                <mapper namespace="com.example.ProbeMapper">
                  <select id="probe">SELECT 1 <bind name="probe" value="p"/></select>
                </mapper>
                """.trimIndent(),
            ).statements.single(),
            emptyList(),
            paramQuotes = types.mapValues { (_, info) -> info.kind.quoteRequired },
        )
        for (source in listOf("p", "p.settlementStartDate", "p.dicCreditStatus", "keyword", "unknown")) {
            assertEquals("$source 引号派生应一致", explicit.quoteFor(source), derived.quoteFor(source))
        }
        assertEquals(true, derived.quoteFor("p.settlementStartDate"))
        assertEquals(false, derived.quoteFor("p.dicCreditStatus"))
        assertEquals(true, derived.quoteFor("keyword"))
        assertNull("未命中返回 null（面板兜底引号）", derived.quoteFor("unknown"))
    }

    @Test
    fun `paramTypeFor_精确与前缀命中_未命中UNKNOWN`() {
        val types = mapOf(
            "p" to ParamTypeInfo(ParamKind.OBJECT),
            "p.dicCreditStatus" to ParamTypeInfo(ParamKind.NUMBER, nullable = false),
        )
        val m = model("p", types)
        assertEquals(ParamKind.OBJECT, m.kindFor("p"))
        assertEquals(ParamKind.OBJECT, m.kindFor("p.merchantName"))
        assertEquals(ParamKind.NUMBER, m.kindFor("p.dicCreditStatus"))
        assertEquals(false, m.paramTypeFor("p.dicCreditStatus")?.nullable)
        assertEquals(ParamKind.UNKNOWN, m.kindFor("other"))
    }

    // ------------------------------------------------------------------
    // mock 占位值分派（T-PT-03）
    // ------------------------------------------------------------------

    @Test
    fun `mock值按类别分派`() {
        assertEquals("1", bindProbe(ParamKind.NUMBER))
        assertEquals(XmlPreviewModel.TEMPORAL_MOCK_VALUE, bindProbe(ParamKind.TEMPORAL))
        assertEquals("'2026-01-01 00:00:00'", bindProbe(ParamKind.TEMPORAL))
        assertEquals("1", bindProbe(ParamKind.TEXT))
        assertEquals("MockObject(properties={})", bindProbe(ParamKind.OBJECT))
        assertTrue("COLLECTION 应为可迭代 mock", bindProbe(ParamKind.COLLECTION)?.contains("MockIterable") == true)
    }

    @Test
    fun `无类型信息时mock占位与升级前一致`() {
        assertEquals("1", bindProbe(null))
        assertEquals("1", bindProbe(ParamKind.UNKNOWN))
        assertEquals(ParamKind.UNKNOWN, model("v").kindFor("v"))
    }

    @Test
    fun `dollar直替按类别出值`() {
        val xml = """
            <mapper namespace="com.example.ProbeMapper">
              <select id="probe">SELECT ${'$'}{v} FROM t</select>
            </mapper>
        """.trimIndent()
        fun rendered(kind: ParamKind?): String {
            val types = if (kind == null) emptyMap() else mapOf("v" to ParamTypeInfo(kind))
            val parsed = XmlMapperParser().parse(xml)
            return XmlPreviewModel.from(parsed.statements.single(), parsed.errors, paramTypes = types)
                .renderSql().sql
        }
        assertEquals("SELECT 1 FROM t", rendered(ParamKind.NUMBER))
        assertEquals("SELECT '2026-01-01 00:00:00' FROM t", rendered(ParamKind.TEMPORAL))
        assertEquals("SELECT 1 FROM t", rendered(ParamKind.TEXT))
        assertEquals("SELECT 1 FROM t", rendered(null))
    }
}
