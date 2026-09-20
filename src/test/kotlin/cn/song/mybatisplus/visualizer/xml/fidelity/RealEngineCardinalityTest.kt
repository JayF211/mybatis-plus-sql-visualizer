package cn.song.mybatisplus.visualizer.xml.fidelity

import cn.song.mybatisplus.visualizer.log.countPlaceholders
import cn.song.mybatisplus.visualizer.xml.AnnotationSqlParser
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlSqlRenderer
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绑定基数自洽钉（O-40 / Z-25 T-REC-01、T-REC-03）——**不依赖真引擎**。
 *
 * ## 钉的关系
 * 我方任一渲染链的产物满足：`countPlaceholders(sql) == mappings.size`
 * （SQL 文本里串感知的 `?` 位点数 == 参数映射条数）。两条推导链独立：一条是渲染器写出的**文本**，
 * 一条是渲染器累积的 **mappings 清单**——`#{}` token 只进其中一侧、`?` 只由 `#{}` 产生这两件事
 * 任一处漂移，本钉即红。这条关系是三方对账（[RealEngineFidelityTest] 的 T-REC-02，需真引擎 jar）
 * 在**无 jar 环境**（CI、他机）的降级替身：真引擎对照集在本机缺 jar 时按 O-34 协议硬失败，
 * 而本类零真引擎依赖，照常守护。
 *
 * `countPlaceholders` 直接复用产品侧 `log/LogEntryPreviewRecognizer.kt` 的既有实现
 * （`internal fun`，测试与主代码同模块可达）——**不为测试放宽任何产品代码可见性**，也不自造计数
 * （裸 `sql.count { it == '?' }` 会把字面量/注释内的 `?` 误计，本仓已有串感知口径）。
 *
 * ## 覆盖的三条渲染链（均从生产入口驱动，非旁路构造）
 * | 链 | 入口 | 场景 |
 * |---|---|---|
 * | XML mapper | `XmlMapperParser` → `XmlSqlRenderer.render` | X1~X4（`<if>` / `<foreach>` / `<bind>` + `${}` 混用 / 字面量与注释内的 `?`）|
 * | 注解 SQL | `AnnotationSqlParser.parse` → `XmlPreviewModel.renderSql` | A1（纯文本）/ A2（`<script>` 动态标签）|
 * | MP wrapper 占位 | `XmlPreviewModel.from(extraBindings = ew)` → `renderSql` | W1（片段注入 + 尾随 `#{}`）/ W2（片段内带引号 `?`）|
 * wrapper 链在此按 **xml 层形态**驱动（`ew` → `MockObject(customSqlSegment=…)`，与 `XmlPreviewService`
 * 注入的形态一致）——不引入 PSI/`ReferencesSearch`（那是 `ui/XmlEwResolverTest` 的覆盖面，
 * 本钉只关心渲染产物内部的基数自洽）。
 *
 * ## 边界：这条关系**不是**普适恒等式（前提 1 实测结论）
 * 两种**合法**形态下 `countPlaceholders(sql) != mappings.size`，且此时我方与真引擎**完全一致**
 * （非保真差异，是「串感知计数」与「引擎非串感知 `#{}` 识别」两个口径的固有分歧）：
 * 1. `${}` 直替出的值自身含 `?`——`?` 由文本注入而非 `#{}` 产生（`mappings` 少、`?` 多）；
 * 2. `#{}` 落在字符串字面量 / `--` 注释内——引擎照样产出 `?`（`?` 落进字面量/注释里，
 *    串感知计数不认它）（`mappings` 多、`?` 少）。
 * 两种形态由 [边界钉] 用例按实测读数**正向钉住**：口径一变即红，强制同步差异清单 §9，
 * 也防止后人把本条关系误当普适恒等式去「修」串感知计数或 `#{}` 识别。
 * 故本条关系的适用域＝**不含上述两种形态的渲染产物**；T-REC-02 的三方对账域同理（28 条一致面实测全部成立）。
 *
 * ## T-REC-03：6 条已知差异为何不参与基数对账
 * 6 条（P0 / P14 / P15 / P16 / P20 / P30）是**保真差异**场景：真引擎硬抛（P0/P14/P15/P16）或
 * 两侧语义分叉（P20 自研不展开、P30 求值为空）——其中 P20 两侧基数**本就不同**（真引擎 1 条 /
 * 自研 0 条），把它纳入「三方基数相等」等于要求它不成立。它们按登记形态断言（见
 * `RealEngineFidelityTest` 的「已知差异 6 条」用例），基数对账的排除由 T-REC-02 用例内的场景集
 * 不相交断言兜底（场景被误搬进一致面即红），口径同步落在差异清单 §9。
 */
class RealEngineCardinalityTest {

    // ------------------------------------------------------------------
    // T-REC-01：三条渲染链的基数自洽钉
    // ------------------------------------------------------------------

    @Test
    fun `T-REC-01 基数自洽：XML mapper 链的串感知问号位点数等于参数条数`() {
        assertRelation(
            xmlReading(
                "X1 if 分支展开（count != 0 真值）",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>""",
                "count" to 1,
            ),
            // 同一 mapper、分支关闭 → 两侧同为 0（分支差异不改变自洽关系）
            xmlReading(
                "X1' if 分支关闭（count == 0 假值）",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>""",
                "count" to 0,
            ),
            xmlReading(
                "X2 foreach 展开（3 次迭代）",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>""",
                "ids" to listOf(1, 2, 3),
            ),
            xmlReading(
                "X3 bind + \${} 直替 + #{} 混用",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{table}<bind name="like" value="'%' + name + '%'"/> WHERE name LIKE #{like} AND c = #{c}</select></mapper>""",
                "table" to "orders", "name" to "x", "c" to 1,
            ),
            xmlReading(
                "X4 字面量与注释内的问号不计（串感知方向）",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT '中文？', 'a?b' FROM t -- tail?
WHERE a = #{a} /* block? */ AND b = #{b}</select></mapper>""",
                "a" to 1, "b" to 2,
            ),
        )
    }

    @Test
    fun `T-REC-01 基数自洽：注解 SQL 链（纯文本与 script 动态标签）的位点数等于参数条数`() {
        assertRelation(
            annotationReading(
                "A1 纯文本注解 SQL（含字面量内问号）",
                "SELECT * FROM t WHERE name = 'a?b' AND a = #{a} AND b = #{b}",
                listOf("a", "b"),
            ),
            annotationReading(
                "A2 script 注解 SQL（if + foreach）",
                "<script>SELECT * FROM t<if test=\"ids != null\"> WHERE id IN" +
                    "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></if></script>",
                listOf("ids"),
            ),
        )
    }

    @Test
    fun `T-REC-01 基数自洽：MP wrapper 占位链（片段注入）的位点数等于参数条数`() {
        assertRelation(
            wrapperReading(
                "W1 \${ew.customSqlSegment} 片段 + 尾随 #{}",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t ${'$'}{ew.customSqlSegment} AND status = #{status}</select></mapper>""",
                "WHERE (id = 1 AND name = 'x')",
                "status" to 1,
            ),
            wrapperReading(
                "W2 片段内带引号问号（串感知不计）",
                """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t ${'$'}{ew.customSqlSegment} AND a = #{a}</select></mapper>""",
                "WHERE (name = 'a?b' OR tag = 'x?')",
                "a" to 1,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 边界钉：两种合法的「不等」形态（前提 1 实测结论固化）
    // ------------------------------------------------------------------

    /**
     * 边界钉（前提 1 实测结论）：`countPlaceholders(sql) != mappings.size` 的两种**合法**形态。
     *
     * 实测读数（mybatis 3.5.19 + 本仓渲染器，两侧逐字节一致）：
     * | 形态 | 真引擎 | 我方 |
     * |---|---|---|
     * | `${}` 值含 `?` | `props=0` / `?`=1 | 同 |
     * | `#{}` 在字符串字面量内 | `props=2` / `?`=1 | 同 |
     * | `#{}` 在 `--` 注释内 | `props=2` / `?`=1 | 同 |
     *
     * 本用例只钉**我方**读数（本类零真引擎依赖）；真引擎侧同一形态的读数由
     * [RealEngineFidelityTest] 的「T-REC 前提 1 边界」用例钉住。两处都是「口径变化即红」的
     * 登记式断言——不是待修缺陷。
     */
    @Test
    fun `T-REC 边界钉：两种合法形态下位点数与参数条数不相等（登记形态，变化即红）`() {
        // 形态 1：`?` 由 `${}` 直替的文本注入（mappings 少一条、位点多一个）
        val dollarInjected = xmlReading(
            "B1 \${} 值自身含问号",
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{cond}</select></mapper>""",
            "cond" to "t WHERE a = ?",
        )
        assertEquals("B1 直替注入的 `?` 不产生参数映射", 0, dollarInjected.rendered.mappings.size)
        assertEquals("B1 位点计数认这个 `?`（文本层事实）", 1, countPlaceholders(dollarInjected.rendered.sql))
        assertEquals(
            "B1 渲染文本（直替原文照进产物）",
            "SELECT * FROM t WHERE a = ?",
            compact(dollarInjected.rendered.sql),
        )

        // 形态 2：`#{}` 落在字符串字面量内（引擎的 `#{}` 识别非串感知 → `?` 进字面量，位点计数不认）
        val inLiteral = xmlReading(
            "B2 字符串字面量内的 #{}",
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT 'x#{a}' FROM t WHERE b = #{b}</select></mapper>""",
            "a" to 1, "b" to 2,
        )
        assertEquals("B2 字面量内的 #{} 照样产生参数映射", 2, inLiteral.rendered.mappings.size)
        assertEquals("B2 位点计数只认字面量外的那个 `?`", 1, countPlaceholders(inLiteral.rendered.sql))
        assertEquals(
            "B2 渲染文本（`?` 落在字面量内）",
            "SELECT 'x?' FROM t WHERE b = ?",
            compact(inLiteral.rendered.sql),
        )

        // 形态 3：`#{}` 落在 `--` 行注释内（同上，注释内 `?` 不计）
        val inComment = xmlReading(
            "B3 行注释内的 #{}",
            "<mapper namespace=\"n\"><select id=\"s1\" resultType=\"map\">SELECT a FROM t -- #{c}\nWHERE b = #{b}</select></mapper>",
            "b" to 2, "c" to 3,
        )
        assertEquals("B3 注释内的 #{} 照样产生参数映射", 2, inComment.rendered.mappings.size)
        assertEquals("B3 位点计数只认注释外的那个 `?`", 1, countPlaceholders(inComment.rendered.sql))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 一条渲染读数（场景 id + 产物）——id 进失败报文，便于定位是哪条场景漂移。 */
    private class Reading(val id: String, val rendered: RenderedXmlSql)

    /**
     * 逐条断言 `countPlaceholders(sql) == mappings.size`；失败报文含场景 id 与两侧读数
     * （照 O-34 一致面「收集全部偏差再一次报出」的形态，不首条即断）。
     */
    private fun assertRelation(vararg readings: Reading) {
        val mismatches = mutableListOf<String>()
        for (reading in readings) {
            val placeholders = countPlaceholders(reading.rendered.sql)
            val mappings = reading.rendered.mappings.size
            if (placeholders != mappings) {
                mismatches += "${reading.id}: 位点数 $placeholders != 参数条数 $mappings" +
                    "\n    sql=[${compact(reading.rendered.sql)}]" +
                    "\n    props=${reading.rendered.mappings.map { it.property }}"
            }
            assertTrue(
                "${reading.id}: 渲染不应产生错误提示（自洽关系的前提）errors=${reading.rendered.errors}",
                reading.rendered.errors.isEmpty(),
            )
        }
        assertTrue(
            "基数自洽关系出现 ${mismatches.size} 条偏差（`?` 位点数 != 参数条数）：\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
        println(
            "[cardinality] 基数自洽 ${readings.size} 条读数全部成立：" +
                readings.joinToString(", ") {
                    "${it.id}=${it.rendered.mappings.size}"
                },
        )
    }

    /** XML mapper 链：解析 → 渲染（bindings 形态对齐 `XmlPreviewModel.mockBindings`：顶层变量 + `_parameter`）。 */
    private fun xmlReading(id: String, mapper: String, vararg bindings: Pair<String, Any?>): Reading {
        val statement = XmlMapperParser().parse(mapper).statements.first()
        return Reading(id, XmlSqlRenderer().render(statement.node, bindings(bindings)))
    }

    /** 注解 SQL 链：`AnnotationSqlParser.parse` → `XmlPreviewModel.renderSql`（生产入口同款）。 */
    private fun annotationReading(id: String, sqlText: String, paramNames: List<String>): Reading {
        val model = AnnotationSqlParser().parse(sqlText, XmlStatementKind.SELECT, paramNames)
        return Reading(id, model.renderSql())
    }

    /** wrapper 占位链：`${ew.customSqlSegment}` 经 `extraBindings` 注入片段（xml 层形态，同 `XmlPreviewService`）。 */
    private fun wrapperReading(
        id: String,
        mapper: String,
        ewFragment: String,
        vararg bindings: Pair<String, Any?>,
    ): Reading {
        val parsed = XmlMapperParser().parse(mapper)
        val statement = parsed.statements.first()
        val model = XmlPreviewModel.from(
            statement,
            parsed.errors,
            extraBindings = mapOf("ew" to MockObject(mapOf("customSqlSegment" to ewFragment))),
        )
        return Reading(id, model.renderSql())
    }

    /** 自研侧 bindings 形态（顶层变量 + `_parameter`（同值 Map）+ `_databaseId`，对齐 `mockBindings` 键形态）。 */
    private fun bindings(pairs: Array<out Pair<String, Any?>>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        pairs.forEach { map[it.first] = it.second }
        map["_parameter"] = LinkedHashMap(map)
        map["_databaseId"] = null
        return map
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
}
