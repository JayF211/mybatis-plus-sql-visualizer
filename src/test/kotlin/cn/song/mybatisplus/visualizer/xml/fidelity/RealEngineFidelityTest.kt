package cn.song.mybatisplus.visualizer.xml.fidelity

import cn.song.mybatisplus.visualizer.xml.EvalResult
import cn.song.mybatisplus.visualizer.xml.MockObject
import cn.song.mybatisplus.visualizer.xml.OgnlEvaluator
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlSqlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 MyBatis 引擎 vs 自研渲染器 —— 标签语义对照集（T-TailSweep2 T-FS-02 协议 / T-FS-03 场景集）。
 *
 * 来源：T-FS-01 临时探针 `FidelityProbeTest` 的 31 条 P0~P30 实测读数（探针按自身 KDoc「不入库」
 * 处置——已删除，其读数转为本文件的断言与 `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md`）。
 *
 * ## 协议（T-FS-02）
 * 同一批**真实值**分别喂两侧：真引擎吃 [Scenario.realParams]（Map / POJO / List / Integer 等真实对象），
 * 自研吃 [Scenario.bindings]（`_parameter` + 顶层变量 + `_databaseId`，对齐 `XmlPreviewModel.mockBindings`
 * 的键形态）。两侧产物直接对照，**绕开面板 mock 层**。
 *
 * ## 断言面（只覆盖标签语义）
 * ① SQL 文本（空白归一后逐字节相等）；② `#{}` 参数属性名序列（顺序即 `?` 出现顺序，foreach 内为
 * `__frch_<名>_<n>` 形态）；③（辅助）自研侧 `errors` 是否为空——一致面要求为空，差异面按登记形态断言。
 * **不覆盖**：mock 值语义（引号 / 类型 / 占位形态）、面板回填链、`${ew.customSqlSegment}`、性能面。
 *
 * ## 空白归一（唯一的非逐字节环节）
 * 真引擎保留 mapper 原文空白（换行/缩进原样进 SQL），自研渲染器按 `DynamicContext.appendSql` 的
 * 「段间单空格」规则拼接（对齐 3.5.13+ 的 `StringJoiner(" ")`）。故断言前对两侧做 `\s+` → 单空格
 * 归一（[compact]）——归一只动空白，不动 token 与 `?` 位点。
 *
 * ## 已知差异（6 条：P0 / P14 / P15 / P16 / P20 / P30）
 * 按 T-FS-02 纪律**登记为已知差异而非失败**：mock 值语义与降级形态差异若一律断言不等，对照集会被
 * 淹没而失去判别力。这 6 条按**登记形态**断言（真引擎抛什么类 / 自研渲染出什么），形态一变即红，
 * 强制同步更新差异清单文档。逐条成因见
 * `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md`。
 *
 * ## 驱动不可用时的口径：硬失败（不 skip）
 * `RealEngineDriver.create()` 在 jar 缺失时抛 [RealEngineUnavailableException] → 本类构造期即红。
 * 依据：① [RealEngineDriver] 自身 KDoc「调用方断言红，禁止静默跳过：跳过=对照集形同不存在」；
 * ② mybatis jar 由 `build.gradle.kts` 的 `testImplementation("org.mybatis:mybatis:3.5.19")` 显式声明，
 * 已在测试依赖图内（仓内软跳先例 `DialectDockerVerificationTest` 的 Docker 探测适用前提是「被测能力
 * 不在本机依赖图内」，此处不成立）；③ 对照集是回归资产，静默降级即退化为装饰。
 */
class RealEngineFidelityTest {

    /** 构造期即建驱动：不可用 → 硬失败（口径见类 KDoc）。 */
    private val driver = RealEngineDriver.create()

    @Test
    fun `一致面 25 条：SQL 文本与参数队列逐字节相等`() {
        val mismatches = mutableListOf<String>()
        for (scenario in equivalentScenarios()) {
            val real = runCatching { driver.render(scenario.mapper, NAMESPACE, STATEMENT_ID, scenario.realParams) }
            if (real.isFailure) {
                mismatches += "${scenario.id}: 真引擎意外抛错（期望两侧一致）——${real.exceptionOrNull()!!.message}"
                continue
            }
            val realBound = real.getOrThrow()
            val mine = mine(scenario)
            val realSql = compact(realBound.sql)
            val mineSql = compact(mine.sql)
            if (realSql != mineSql) {
                mismatches += "${scenario.id}: SQL 不一致\n    真引擎=[$realSql]\n    自研  =[$mineSql]"
            }
            val mineProps = mine.mappings.map { it.property }
            if (realBound.parameterProperties != mineProps) {
                mismatches += "${scenario.id}: 参数队列不一致\n    真引擎=${realBound.parameterProperties}\n    自研  =$mineProps"
            }
            if (mine.errors.isNotEmpty()) {
                mismatches += "${scenario.id}: 自研渲染意外产生 errors=${mine.errors}"
            }
        }
        assertTrue(
            "标签语义一致面出现 ${mismatches.size} 条偏差：\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `已知差异 6 条：按登记形态断言（形态变化即红，强制同步差异清单）`() {
        val known = knownDifferenceScenarios()

        // ---- P0 无 DOCTYPE：真引擎 BuilderException（强制 DOCTYPE）vs 自研正常渲染（更宽容）----
        val p0 = known.getValue("P0")
        val p0Real = runCatching { driver.render(p0.mapper, NAMESPACE, STATEMENT_ID, p0.realParams) }
        assertEquals(
            "P0 真引擎应抛 BuilderException（DOCTYPE 强制校验）",
            "org.apache.ibatis.builder.BuilderException",
            (p0Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p0Mine = mine(p0)
        assertEquals("P0 自研 SQL", "SELECT * FROM t WHERE a = ?", compact(p0Mine.sql))
        assertEquals("P0 自研参数队列", listOf("a"), p0Mine.mappings.map { it.property })
        assertEquals("P0 自研无错误提示（宽容侧静默）", emptyList<String>(), p0Mine.errors)

        // ---- P14 `||flag||`：真引擎 ExpressionSyntaxException vs 自研剥壳渲染（我方超集能力）----
        val p14 = known.getValue("P14")
        val p14Real = runCatching { driver.render(p14.mapper, NAMESPACE, STATEMENT_ID, p14.realParams) }
        val p14Cause = (p14Real.exceptionOrNull() as? RealEngineException)?.causeClassName
        assertEquals("P14 真引擎应抛 BuilderException", "org.apache.ibatis.builder.BuilderException", p14Cause)
        assertTrue(
            "P14 真引擎应因 Malformed OGNL 失败：${p14Real.exceptionOrNull()?.message}",
            p14Real.exceptionOrNull()?.message.orEmpty().contains("ExpressionSyntaxException"),
        )
        val p14Mine = mine(p14)
        assertEquals("P14 自研剥壳后渲染（超集能力）", "SELECT * FROM t AND x = 1", compact(p14Mine.sql))
        assertEquals("P14 自研无错误提示（剥壳是显式兼容语义）", emptyList<String>(), p14Mine.errors)

        // ---- P15 顶层 if 非法表达式：真引擎硬抛 vs 自研容错展开 + 记 errors ----
        val p15 = known.getValue("P15")
        val p15Real = runCatching { driver.render(p15.mapper, NAMESPACE, STATEMENT_ID, p15.realParams) }
        assertEquals(
            "P15 真引擎应抛 BuilderException",
            "org.apache.ibatis.builder.BuilderException",
            (p15Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p15Mine = mine(p15)
        assertEquals("P15 自研容错展开", "SELECT * FROM t AND x = 1", compact(p15Mine.sql))
        assertEquals("P15 自研错误提示条数", 1, p15Mine.errors.size)
        assertTrue("P15 自研错误提示应说明按 true 展开：${p15Mine.errors}", p15Mine.errors[0].contains("条件表达式无法求值"))

        // ---- P16 foreach 体内 if 非法表达式：真引擎硬抛 vs 自研展开且**不记 errors**（T-FS-07 在册缺口）----
        // 这条 errors 为空是**已登记的静默**（PrefixedContext/FilteredContext 未转发 DynamicContext.errors，
        // T-TailSweep2 T-FS-07 的尾款）；T-FS-07 落地后本断言会红 → 届时同步改差异清单。
        val p16 = known.getValue("P16")
        val p16Real = runCatching { driver.render(p16.mapper, NAMESPACE, STATEMENT_ID, p16.realParams) }
        assertEquals(
            "P16 真引擎应抛 BuilderException",
            "org.apache.ibatis.builder.BuilderException",
            (p16Real.exceptionOrNull() as? RealEngineException)?.causeClassName,
        )
        val p16Mine = mine(p16)
        assertEquals("P16 自研展开产物", "SELECT * FROM t WHERE id IN ( ? , ? )", compact(p16Mine.sql))
        assertEquals("P16 自研参数队列", listOf("__frch_id_0", "__frch_id_1"), p16Mine.mappings.map { it.property })
        assertEquals("P16 自研 errors 应为空（T-FS-07 在册静默缺口）", emptyList<String>(), p16Mine.errors)

        // ---- P20 DTO 属性在 test 中：真引擎判真展开 vs 自研判假不展开（求值器无 POJO 属性访问）----
        val p20 = known.getValue("P20")
        val p20Real = driver.render(p20.mapper, NAMESPACE, STATEMENT_ID, p20.realParams)
        assertEquals("P20 真引擎 SQL", "SELECT * FROM t AND name = ?", compact(p20Real.sql))
        assertEquals("P20 真引擎参数队列", listOf("u.name"), p20Real.parameterProperties)
        val p20Mine = mine(p20)
        assertEquals("P20 自研整段不展开", "SELECT * FROM t", compact(p20Mine.sql))
        assertEquals("P20 自研无参数", emptyList<String>(), p20Mine.mappings.map { it.property })
        assertEquals("P20 自研 errors 为空（差异静默，无提示）", emptyList<String>(), p20Mine.errors)

        // ---- P30 `${}` 求值为空：SQL 两侧相同，自研多一条提示 errors ----
        val p30 = known.getValue("P30")
        val p30Real = driver.render(p30.mapper, NAMESPACE, STATEMENT_ID, p30.realParams)
        val p30Mine = mine(p30)
        assertEquals("P30 两侧 SQL 相同（真引擎）", "SELECT * FROM WHERE a = 1", compact(p30Real.sql))
        assertEquals("P30 两侧 SQL 相同（自研）", compact(p30Real.sql), compact(p30Mine.sql))
        assertEquals("P30 两侧参数队列相同", p30Real.parameterProperties, p30Mine.mappings.map { it.property })
        assertEquals("P30 自研多一条提示", 1, p30Mine.errors.size)
        assertTrue("P30 提示应说明求值为空：${p30Mine.errors}", p30Mine.errors[0].contains("求值为空"))
    }

    /**
     * P20 归因钉：自研求值器的属性访问只支持 `MockObject` / `Map` / `Collection` / `Array` / `CharSequence`
     * （`xml/Ognl.kt` 的 `property()`，其余载体 `else -> null`），**真实 DTO/POJO 实例的属性一律取不到**——
     * 这是 P20 两侧分叉的根因（不是探针构造问题，也不是真引擎行为差异）。
     *
     * 本钉不修缺陷，只把根因固化成可复跑证据：载体一换（DTO → MockObject）结果就翻转。
     */
    @Test
    fun `P20 归因：求值器属性访问的载体面不含 POJO`() {
        assertEquals(
            "DTO 载体：u.name 取不到 → null → 判假",
            EvalResult.Ok(false),
            OgnlEvaluator.evaluate("u.name != null", mapOf("u" to ProbeDto("a"))),
        )
        assertEquals(
            "MockObject 载体：u.name 取到 → 判真",
            EvalResult.Ok(true),
            OgnlEvaluator.evaluate("u.name != null", mapOf("u" to MockObject(mapOf("name" to "a")))),
        )
    }

    // ------------------------------------------------------------------
    // 场景表（31 条，读数基线见差异清单文档）
    // ------------------------------------------------------------------

    /** 逐字节一致面（25 条）：P1~P13、P17~P19、P21~P29。 */
    private fun equivalentScenarios(): List<Scenario> = listOf(
        Scenario(
            "P1 if Integer 0 / Map param",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>"""),
            mapOf("count" to 0), bindings("count" to 0),
        ),
        Scenario(
            "P2 if Integer 1",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="count != 0"> AND c = #{count}</if></select></mapper>"""),
            mapOf("count" to 1), bindings("count" to 1),
        ),
        Scenario(
            "P3 choose/when/otherwise",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<choose><when test="type == 1"> ORDER BY a</when><when test="type == 2"> ORDER BY b</when><otherwise> ORDER BY id</otherwise></choose></select></mapper>"""),
            mapOf("type" to 2), bindings("type" to 2),
        ),
        Scenario(
            "P4 where + set trim",
            doc("""<mapper namespace="n"><update id="s1">UPDATE t<set><if test="a != null">a = #{a},</if><if test="b != null">b = #{b},</if></set><where><if test="a != null">AND a = #{a}</if></where></update></mapper>"""),
            mapOf("a" to 1, "b" to null), bindings("a" to 1, "b" to null),
        ),
        Scenario(
            "P5 trim custom",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<trim prefix="WHERE" prefixOverrides="AND |OR " suffix="ORDER BY id"> AND x = #{x}</trim></select></mapper>"""),
            mapOf("x" to 1), bindings("x" to 1),
        ),
        Scenario(
            "P6 foreach List<Integer>",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></select></mapper>"""),
            mapOf("ids" to listOf(1, 2, 3)), bindings("ids" to listOf(1, 2, 3)),
        ),
        Scenario(
            "P7 foreach DTO item props",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="list" item="u" separator="OR"> u.name = #{u.name}</foreach></select></mapper>"""),
            mapOf("list" to listOf(ProbeDto("a"), ProbeDto("b"))),
            bindings("list" to listOf(ProbeDto("a"), ProbeDto("b"))),
        ),
        Scenario(
            "P8 foreach Map entry index=key",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="m" index="k" item="v" separator="AND"> #{k} = #{v}</foreach></select></mapper>"""),
            mapOf("m" to linkedMapOf("a" to 1, "b" to 2)),
            bindings("m" to linkedMapOf("a" to 1, "b" to 2)),
        ),
        Scenario(
            "P9 nested foreach",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="groups" item="g" open="(" separator=" OR " close=")">gid IN<foreach collection="g.ids" item="i" open="(" separator="," close=")">#{i}</foreach></foreach></select></mapper>"""),
            mapOf("groups" to listOf(mapOf("ids" to listOf(1, 2)), mapOf("ids" to listOf(3)))),
            bindings("groups" to listOf(mapOf("ids" to listOf(1, 2)), mapOf("ids" to listOf(3)))),
        ),
        Scenario(
            "P10 bind + like",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<bind name="like" value="'%' + name + '%'"/> WHERE name LIKE #{like}</select></mapper>"""),
            mapOf("name" to "x"), bindings("name" to "x"),
        ),
        Scenario(
            "P11 dollar direct",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{table} WHERE c = #{c}</select></mapper>"""),
            mapOf("table" to "orders", "c" to 1), bindings("table" to "orders", "c" to 1),
        ),
        Scenario(
            "P12 static field @java.lang.Integer@MAX_VALUE",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = ${'$'}{@java.lang.Integer@MAX_VALUE}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(), mapOf("java.lang.Integer@MAX_VALUE" to Int.MAX_VALUE),
        ),
        Scenario(
            "P13 static field local class",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = ${'$'}{@cn.song.mybatisplus.visualizer.xml.fidelity.ProbeConsts@FLAG}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(), mapOf("cn.song.mybatisplus.visualizer.xml.fidelity.ProbeConsts@FLAG" to ProbeConsts.FLAG),
        ),
        Scenario(
            "P17 empty string truthiness",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="emptyStr"> AND x = 1</if></select></mapper>"""),
            mapOf("emptyStr" to ""), bindings("emptyStr" to ""),
        ),
        Scenario(
            "P18 or value semantics (a='' or b='x')",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a or b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to "", "b" to "x"), bindings("a" to "", "b" to "x"),
        ),
        Scenario(
            "P19 and value semantics (a='' and b=null)",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a and b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to "", "b" to null), bindings("a" to "", "b" to null),
        ),
        Scenario(
            "P21 pojo as _parameter",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="name != null"> AND name = #{name}</if></select></mapper>"""),
            ProbeDto("a"),
            mapOf("_parameter" to ProbeDto("a"), "_databaseId" to null, "name" to "a"),
        ),
        Scenario(
            "P22 empty collection foreach",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="ids != null"> WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></if></select></mapper>"""),
            mapOf("ids" to emptyList<Int>()), bindings("ids" to emptyList<Int>()),
        ),
        Scenario(
            "P23 escaped hash",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE c = \#{literal}</select></mapper>"""),
            emptyMap<String, Any?>(), bindings(),
        ),
        Scenario(
            "P24 include with property",
            doc("""<mapper namespace="n"><sql id="w"><if test="status != null">AND status = #{status}</if></sql><select id="s1" resultType="map">SELECT * FROM t<where><include refid="w"><property name="status" value="1"/></include></where></select></mapper>"""),
            mapOf("status" to "1"), bindings("status" to "1"),
        ),
        Scenario(
            "P25 foreach index on list",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE<foreach collection="ids" item="id" index="i" separator="OR"> (i = #{i} AND id = #{id})</foreach></select></mapper>"""),
            mapOf("ids" to listOf(7, 8)), bindings("ids" to listOf(7, 8)),
        ),
        Scenario(
            "P26 jdbcType attribute in token",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = #{a,jdbcType=INTEGER} AND b = #{b, javaType=java.lang.String}</select></mapper>"""),
            mapOf("a" to 1, "b" to "x"), bindings("a" to 1, "b" to "x"),
        ),
        Scenario(
            "P27 if null-safe == null",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a == null"> WHERE a IS NULL</if></select></mapper>"""),
            mapOf("a" to null), bindings("a" to null),
        ),
        Scenario(
            "P28 if set/list size",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="ids != null and ids.size > 0"> AND id IN<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></if></select></mapper>"""),
            mapOf("ids" to listOf(1)), bindings("ids" to listOf(1)),
        ),
        Scenario(
            "P29 nested property chain map",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="q.name != null"> AND n = #{q.name}</if></select></mapper>"""),
            mapOf("q" to linkedMapOf("name" to "z")), bindings("q" to linkedMapOf("name" to "z")),
        ),
    )

    /** 已知差异面（6 条）：P0 / P14 / P15 / P16 / P20 / P30。 */
    private fun knownDifferenceScenarios(): Map<String, Scenario> = mapOf(
        "P0" to Scenario(
            "P0 no DOCTYPE",
            """<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE a = #{a}</select></mapper>""",
            mapOf("a" to 1), bindings("a" to 1),
        ),
        "P14" to Scenario(
            "P14 or-bars wrap",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="||flag||"> AND x = 1</if></select></mapper>"""),
            mapOf("flag" to true), bindings("flag" to true),
        ),
        "P15" to Scenario(
            "P15 invalid expr in if (top level)",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="a === b"> AND x = 1</if></select></mapper>"""),
            mapOf("a" to 1), bindings("a" to 1),
        ),
        "P16" to Scenario(
            "P16 invalid expr in foreach body if",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t WHERE id IN<foreach collection="ids" item="id" open="(" separator="," close=")"><if test="id === 1">#{id}</if></foreach></select></mapper>"""),
            mapOf("ids" to listOf(1, 2)), bindings("ids" to listOf(1, 2)),
        ),
        "P20" to Scenario(
            "P20 DTO property in test",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM t<if test="u.name != null"> AND name = #{u.name}</if></select></mapper>"""),
            mapOf("u" to ProbeDto("a")), bindings("u" to ProbeDto("a")),
        ),
        "P30" to Scenario(
            "P30 dollar null value",
            doc("""<mapper namespace="n"><select id="s1" resultType="map">SELECT * FROM ${'$'}{tbl} WHERE a = 1</select></mapper>"""),
            mapOf("tbl" to null), bindings("tbl" to null),
        ),
    )

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun mine(scenario: Scenario): RenderedXmlSql {
        val statement = XmlMapperParser().parse(scenario.mapper).statements.first()
        return XmlSqlRenderer().render(
            statement.node,
            scenario.bindings,
            staticFieldValues = scenario.staticFieldValues,
        )
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    /**
     * 自研侧 bindings 形态（对齐 `XmlPreviewModel.mockBindings` 的键形态，非面板 mock 值策略）：
     * 顶层变量 + `_parameter`（同值 Map）+ `_databaseId`。
     */
    private fun bindings(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        pairs.forEach { map[it.first] = it.second }
        map["_parameter"] = LinkedHashMap(map)
        map["_databaseId"] = null
        return map
    }

    /** 官方 DOCTYPE 头（真引擎 3.5.19 强制校验：无 DOCTYPE 直接 `BuilderException`，见 P0）。 */
    private fun doc(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
            body

    private companion object {
        const val NAMESPACE = "n"
        const val STATEMENT_ID = "s1"
    }
}

/** 对照场景（两侧同源输入：真引擎吃 [realParams]，自研吃 [bindings] + [staticFieldValues]）。 */
internal class Scenario(
    val id: String,
    val mapper: String,
    val realParams: Any?,
    val bindings: Map<String, Any?>,
    val staticFieldValues: Map<String, Any?> = emptyMap(),
)

/** 对照用 DTO（真实实例——真引擎经 OGNL 反射取属性，自研求值器见 P20 归因钉）。 */
internal class ProbeDto(val name: String)

/** 对照用常量类（P13 `@class@field` 静态字段引用的宿主类，真引擎从宿主 classpath 解析）。 */
internal object ProbeConsts {
    const val FLAG = "PROBE_FLAG"
}
