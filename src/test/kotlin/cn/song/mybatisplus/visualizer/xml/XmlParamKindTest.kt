package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.sql.ParamQuote
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver
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
 *  - 无类型信息（UNKNOWN）行为与升级前逐值一致（防回归）；
 *  - T-PC2-01/02：`BigInteger` 归数值族、`java.util.UUID` 归文本档（类型文本映射层，零 PSI）；
 *  - T-PC2-04（D1=B）：[XmlPreviewModel.unknownParamSources] 执行前提示信号口径。
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

    // ------------------------------------------------------------------
    // T-PC2-01 / T-PC2-02：数值族补缺（BigInteger）与 UUID 归文本（类型文本映射层）
    // ------------------------------------------------------------------

    @Test
    fun `BigInteger归数值族_不再落UNKNOWN`() {
        // 漏收修复：BigInteger 此前不在数值名单 → 经 XmlParamTypeResolver 落 UNKNOWN
        // （引号兜底 + mock "1" + 无校验警示）；补入后与 BigDecimal 同规
        assertEquals("BigInteger 应裸渲染（数值族）", false, ParamQuote.decide("java.math.BigInteger"))
        assertEquals(false, ParamQuote.decide("BigInteger"))
        assertEquals("同族回归：BigDecimal 仍裸渲染", false, ParamQuote.decide("java.math.BigDecimal"))
        // 经类型文本映射落 NUMBER（NUMBER 档的 mock 与引号语义随之生效）
        assertEquals(ParamKind.NUMBER, XmlParamTypeResolver.kindOfTypeText("java.math.BigInteger"))
        assertEquals(ParamKind.NUMBER, XmlParamTypeResolver.kindOfTypeText("BigInteger"))
        assertEquals(false, ParamKind.NUMBER.quoteRequired)
    }

    @Test
    fun `UUID归文本档_带引号`() {
        // T-PC2-02：UUID 在 SQL 中以带引号字符串形态比较 → TEXT（不扩枚举，避免 when 分支连带）
        assertEquals(ParamKind.TEXT, XmlParamTypeResolver.kindOfTypeText("java.util.UUID"))
        assertEquals(ParamKind.TEXT, XmlParamTypeResolver.kindOfTypeText("UUID"))
        assertEquals(true, ParamKind.TEXT.quoteRequired)
        // 边界：UUID 数组仍按集合、泛型剥壳后按外层类型
        assertEquals(ParamKind.COLLECTION, XmlParamTypeResolver.kindOfTypeText("java.util.UUID[]"))
        assertEquals(ParamKind.COLLECTION, XmlParamTypeResolver.kindOfTypeText("java.util.List<java.util.UUID>"))
        // 未收窄：其余 java.util 类型仍不落 TEXT
        assertNull(XmlParamTypeResolver.kindOfTypeText("java.util.Optional"))
    }

    // ------------------------------------------------------------------
    // T-PC2-04（D1=B）：执行前提示信号（UNKNOWN 参数清单）
    // ------------------------------------------------------------------

    @Test
    fun `unknownParamSources_仅有签名条目且未识别才计入`() {
        val types = mapOf(
            "p" to ParamTypeInfo(ParamKind.OBJECT),
            "p.status" to ParamTypeInfo(ParamKind.UNKNOWN),
            "p.id" to ParamTypeInfo(ParamKind.NUMBER),
            "flag" to ParamTypeInfo(ParamKind.UNKNOWN),
            "keyword" to ParamTypeInfo(ParamKind.TEXT),
        )
        val m = model("p", types)
        // 计入：有类型条目但类别未识别（枚举/boolean 等 → 执行时以字面 ? 提交）
        assertEquals(listOf("p.status", "flag"), m.unknownParamSources(listOf("p.status", "flag", "p.id", "keyword")))
        // 不计入：无类型信息（键不在 paramTypes——mapper 接口不可解析 / 参数未参与签名解析）
        assertEquals(emptyList<String>(), m.unknownParamSources(listOf("noTypeInfo", "other.field")))
        // 去重 + 保持入参顺序（同一参数在 SQL 里多次出现只提示一次计数）
        assertEquals(listOf("flag", "p.status"), m.unknownParamSources(listOf("flag", "p.status", "flag")))
        // 前缀兜底命中的 DTO 属性（p 为 OBJECT）不计入
        assertEquals(emptyList<String>(), m.unknownParamSources(listOf("p.merchantName")))
        // 无类型信息模型（paramTypes 空）恒空——不因接口解析失败而每次执行提示
        assertEquals(emptyList<String>(), model("v").unknownParamSources(listOf("v", "a.b")))
    }
}
