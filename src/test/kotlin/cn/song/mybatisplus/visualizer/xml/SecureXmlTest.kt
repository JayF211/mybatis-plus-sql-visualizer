package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.XMLConstants

/**
 * XML 解析安全配置收敛与内联 DTD 膨胀防护测试（T-XmlDtdHardening：T-XDH-01/02/03/05）。
 *
 * **D1=A 定案（2026-09-19）**：内联 DTD 子集**维持放行**——内部实体照旧展开，语义不变
 * （`XmlSqlRendererTest.内部实体定义可解析` 保持绿），本任务只加膨胀防护。
 * 故此处「合法内部实体仍可解析」「上限内多层实体可解析」是**不回退**的钉住项。
 *
 * 实测基线（JDK 17.0.19，`build/tmpprobe/Probe2.java`）：4 层 × 10 引用 × 10 字符的
 * billion laughs 展开 100,016 字符**可正常解析**（JDK `entityExpansionLimit=64000` 只数
 * 展开次数，本例仅 11,110 次，管不到）——这是膨胀防护要堵的口子，也是本文件红转绿的判据。
 */
class SecureXmlTest {

    // ------------------------------------------------------------------
    // T-XDH-01：配置收敛（防漂移）
    // ------------------------------------------------------------------

    @Test
    fun `解析器安全配置收敛到唯一实现`() {
        // 改前 src/main 有两份逐字相同的 DocumentBuilderFactory 配置（XmlMapperParser/PagesXmlParser），
        // 收敛后只剩 SecureXml 一处——本用例即 T-XDH-01 的先红后绿判据。
        val hits = mainSources()
            .filter { (_, code) -> code.contains(EXTERNAL_ENTITIES_URI) || code.contains("ACCESS_EXTERNAL_DTD") }
            .map { (path, _) -> path }
        assertEquals(
            "DocumentBuilderFactory 安全配置必须只有一处实现（T-XDH-01），src/main 命中：$hits",
            1,
            hits.size,
        )
        assertTrue(
            "唯一实现须落在 xml/SecureXml.kt，实际：${hits.single()}",
            hits.single().endsWith("xml/SecureXml.kt"),
        )
    }

    // ------------------------------------------------------------------
    // T-XDH-03：内联 DTD 子集膨胀防护
    // ------------------------------------------------------------------

    @Test
    fun `指数膨胀实体被拒绝_不OOM不挂死`() {
        // billion laughs：4 层 × 10 引用 × 10 字符 → 展开估算 100,016 字符（改前实测可解析）
        val xml = laughs(levels = 4, fanout = 10, payloadLen = 10)
        val started = System.currentTimeMillis()
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(xml)
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("拒绝原因须点明实体展开超限，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
        assertTrue("拒绝须即时（实测 ${elapsed}ms）", elapsed < 5_000)
    }

    @Test
    fun `实体声明数量超限被拒绝`() {
        // 100 个内部字面量实体声明（改前实测可解析）
        val xml = manyDeclarations(100)
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(xml)
        }
        assertTrue("拒绝原因须点明实体声明数，实际：${e.message}", e.message.orEmpty().contains("实体声明"))
    }

    @Test
    fun `实体引用嵌套深度超限被拒绝`() {
        // 40 层链式引用（改前实测可解析，展开后仅 4 字符——只有深度判据能拦）
        val xml = deepChain(40)
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(xml)
        }
        assertTrue("拒绝原因须点明引用嵌套深度，实际：${e.message}", e.message.orEmpty().contains("嵌套深度"))
    }

    @Test
    fun `循环实体引用被拒绝`() {
        // 改前由 JDK 报「递归实体引用」；加防护后由预扫判据先拒（原因口径统一到膨胀防护）
        val xml = """
            <!DOCTYPE mapper [<!ENTITY a "&a;">]>
            <mapper namespace="n">
              <select id="s1">SELECT '&a;' FROM t</select>
            </mapper>
        """.trimIndent()
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(xml)
        }
        assertTrue("拒绝原因须点明成环，实际：${e.message}", e.message.orEmpty().contains("成环"))
    }

    // ------------------------------------------------------------------
    // D1=A：放行语义不回退（钉住项，改前改后都必须绿）
    // ------------------------------------------------------------------

    @Test
    fun `合法内部实体保持可解析`() {
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper [<!ENTITY status "ACTIVE">]>
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE status = '&status;'</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(
            "SELECT * FROM t WHERE status = 'ACTIVE'",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `上限内的多层实体可解析`() {
        // 3 层 × 10 引用 × 10 字符 = 10,016 字符，远在 65,536 上限内——防误伤边界
        val xml = laughs(levels = 3, fanout = 10, payloadLen = 10)
        val parsed = XmlMapperParser().parse(xml)
        val sql = XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
        assertTrue("多层实体展开须照常（长度 ${sql.length}）", sql.length > 10_000)
    }

    @Test
    fun `内联DTD声明元素与属性不受实体上限影响`() {
        // 官方 mybatis-3-mapper.dtd 的 `<!ENTITY` 声明数为 0（实测下载计数：ELEMENT 33 / ATTLIST 28 /
        // ENTITY 0）——「把官方 DTD 整段内联进 mapper」这种真实写法不该被声明数上限误伤
        val subset = buildString {
            repeat(30) {
                append("<!ELEMENT e").append(it).append(" (#PCDATA)>\n")
                append("<!ATTLIST e").append(it).append(" id CDATA #IMPLIED>\n")
            }
            append("<!ENTITY short \"ok\">")
        }
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper [
            $subset
            ]>
            <mapper namespace="n">
              <select id="s1">SELECT '&short;' FROM t</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(
            "SELECT 'ok' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    // ------------------------------------------------------------------
    // T-XDH-02：防护生效性断言 + 降级可观测（防「以为设了其实静默降级」）
    // ------------------------------------------------------------------

    @Test
    fun `关键feature在当前JDK确实生效`() {
        // 改前 setFeature 全包 runCatching：解析器不支持某 feature 时静默降级、无任何断言兜底
        val warnings = mutableListOf<String>()
        val factory = SecureXml.newDocumentBuilderFactory(warnings::add)
        assertTrue("当前 JDK 不应出现 feature 降级，实际告警：$warnings", warnings.isEmpty())
        assertTrue(
            "FEATURE_SECURE_PROCESSING 须生效",
            factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING),
        )
        assertFalse(
            "外部通用实体须禁用（XXE 主防线）",
            factory.getFeature(SecureXml.EXTERNAL_GENERAL_ENTITIES_FEATURE),
        )
        assertFalse(
            "外部参数实体须禁用",
            factory.getFeature(SecureXml.EXTERNAL_PARAMETER_ENTITIES_FEATURE),
        )
        assertFalse(
            "DOCTYPE 须放行（防回退：改回 true 会让官方 DTD 的 mapper 全部解析失败）",
            factory.getFeature(SecureXml.DOCTYPE_DECL_FEATURE),
        )
        SecureXml.KEY_FEATURES.forEach { name ->
            assertTrue(
                "关键 feature `$name` 在当前 JDK 须可设置",
                SecureXml.applyFeature(factory, name, factory.getFeature(name), warnings::add),
            )
        }
        assertTrue("关键 feature 全部生效时不应有降级告警，实际：$warnings", warnings.isEmpty())
    }

    @Test
    fun `feature设置失败_降级有warn可观测`() {
        val warnings = mutableListOf<String>()
        val factory = SecureXml.newDocumentBuilderFactory(warnings::add)
        val bogus = "urn:mpsv:nonexistent-feature"
        assertFalse("不存在的 feature 应返回失败", SecureXml.applyFeature(factory, bogus, true, warnings::add))
        assertEquals("降级须恰有一条告警", 1, warnings.size)
        assertTrue("告警须含 feature 名，实际：${warnings.single()}", warnings.single().contains(bogus))
        // 单项失败不得连带削弱关键防护
        assertFalse(factory.getFeature(SecureXml.EXTERNAL_GENERAL_ENTITIES_FEATURE))
        assertFalse(factory.getFeature(SecureXml.EXTERNAL_PARAMETER_ENTITIES_FEATURE))
    }

    @Test
    fun `外部DTD读取拦为空_防回退`() {
        // ① 解析器单元面：EntityResolver 恒返回空 InputSource（非 null——null 会交回默认解析器联网）
        val source = SecureXml.entityResolver().resolveEntity(
            "-//mybatis.org//DTD Mapper 3.0//EN",
            "http://mybatis.org/dtd/mybatis-3-mapper.dtd",
        )
        assertNotNull("须返回空 InputSource 而非 null（null = 交回默认解析器联网）", source)
        assertEquals("拦截内容须为空串", -1, source.characterStream.read())

        // ② 行为面：DOCTYPE 指向不可达地址仍能解析——实测（JDK 17.0.19）摘掉 EntityResolver
        // 即抛 ConnectException: Connection refused，故本断言是「resolver 真挂在 builder 上」的判据
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper SYSTEM "http://127.0.0.1:1/x.dtd">
            <mapper namespace="n"><select id="s1">SELECT 1 FROM t</select></mapper>
            """.trimIndent(),
        )
        assertEquals(1, parsed.statements.size)
    }

    @Test
    fun `官方DTD声明放行不回退`() {
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id = #{id}</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(1, parsed.statements.size)
        assertEquals("s1", parsed.statements.single().id)
    }

    // ------------------------------------------------------------------
    // T-XDH-03：判据边界与误伤面（上限取值给足余量，合法场景须通过）
    // ------------------------------------------------------------------

    @Test
    fun `实体展开上限边界_上限内放行上限外拒绝`() {
        val atLimit = SecureXml.MAX_ENTITY_EXPANSION_CHARS.toInt()
        assertEquals(
            "上限内单实体须照常解析",
            1,
            XmlMapperParser().parse(singleEntityXml(atLimit)).statements.size,
        )

        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(singleEntityXml(atLimit + 1))
        }
        assertTrue("拒绝原因须点明展开估算，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `实体声明数上限边界_上限内放行上限外拒绝`() {
        assertEquals(
            "上限内声明数须照常解析",
            1,
            XmlMapperParser().parse(manyDeclarations(SecureXml.MAX_ENTITY_DECLARATIONS)).statements.size,
        )
        assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(manyDeclarations(SecureXml.MAX_ENTITY_DECLARATIONS + 1))
        }
    }

    @Test
    fun `参数实体参与计数与展开`() {
        // 参数实体（`<!ENTITY % p ...>`）此前零用例：合法时照常展开
        val legal = """
            <!DOCTYPE mapper [<!ENTITY % p "<!ENTITY inner 'INNER'>">%p;]>
            <mapper namespace="n">
              <select id="s1">SELECT '&inner;' FROM t</select>
            </mapper>
        """.trimIndent()
        val parsed = XmlMapperParser().parse(legal)
        assertEquals(
            "SELECT 'INNER' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
        // 超限时同样计入（内部字面量参数实体与通用实体同一口径）
        assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(manyParamDeclarations(SecureXml.MAX_ENTITY_DECLARATIONS + 1))
        }
    }

    @Test
    fun `外部实体声明不计入膨胀上限`() {
        // 外部实体声明（SYSTEM/PUBLIC）无内容可展开（已由 feature + EntityResolver 双重拦死），
        // 故不计入声明数——否则「大量外部实体声明」这种无风险写法会被误伤
        val xml = buildString {
            append("<!DOCTYPE mapper [\n")
            repeat(200) { append("<!ENTITY x").append(it).append(" SYSTEM \"file:///etc/hostname\">\n") }
            append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&x0;' FROM t</select></mapper>")
        }
        val parsed = XmlMapperParser().parse(xml)
        assertEquals(
            "SELECT '' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `未声明的内置实体引用按单字符计`() {
        // `&lt;`/`&amp;`/`&#38;` 不在子集里声明：按 1 字符计，不得当成「未声明 = 拒绝」而误伤
        SecureXml.checkEntityExpansion(
            """<!DOCTYPE mapper [<!ENTITY x "&lt;&amp;&#38;">]><mapper namespace="n"/>""",
        )
    }

    // ------------------------------------------------------------------
    // T-XDH-07：不可见声明收口（独立复核抓到的预扫绕过，2026-09-19）
    //
    // 改前实测读数（JDK 17.0.19，探针 F:/tmp/xdh7/ProbeB.java 复刻改前预扫逻辑 + 生产解析配置）：
    // 4 层 × 10 引用 × 4000 字符的载荷（未加诱饵时预扫正确拒绝 chars=65537）加上诱饵后预扫 PASS，
    // JDK 实际展开 40,000,016 字符（164~384ms）；参数实体走私载荷展开 10,000,016 字符。
    // ------------------------------------------------------------------

    @Test
    fun `实体值里的子集结束符诱饵不放行`() {
        // 改前 `<!ENTITY decoy "]>">` 置于子集首行：indexOf("]>") 切出的子集没有闭合引号 → 声明正则 0 命中
        // → `declarations.isEmpty()` 早退 → 三道判据全废（实测预扫 PASS + 展开 40,000,016 字符）
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(laughsWithDecoy("""<!ENTITY decoy "]>">"""))
        }
        assertTrue("拒绝原因须点明膨胀判据，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `注释里的子集结束符诱饵不放行`() {
        // 改前：注释里的 `]>` 同样提前截断子集（实测预扫 PASS + 展开 40,000,016 字符）
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(laughsWithDecoy("<!-- ]> -->"))
        }
        assertTrue("拒绝原因须点明膨胀判据，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `处理指令里的子集结束符诱饵不放行`() {
        // 改前：子集内的处理指令 `<?pi ]> ?>` 合法（实测 JDK 接受，且其中的 `]>` 不结束子集），
        // 但 indexOf("]>") 会在该处截断 → 预扫 PASS + 展开 40,000,016 字符
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(laughsWithDecoy("<?pi ]> ?>"))
        }
        assertTrue("拒绝原因须点明膨胀判据，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `序言里的假DOCTYPE不抢占定位`() {
        // 改前：`fileText.indexOf("<!DOCTYPE")` 会认下注释/处理指令里的假 DOCTYPE（两者实测均被 JDK 接受），
        // 其子集为空 → 早退 → 真 DOCTYPE 的实体全不受扫（实测预扫 PASS + 展开 40,000,016 字符）
        listOf(
            "<!-- <!DOCTYPE x [ ]> -->" to "注释",
            "<?pi <!DOCTYPE x [ ]> ?>" to "处理指令",
        ).forEach { (decoy, kind) ->
            val e = assertThrows(XmlParseException::class.java) {
                XmlMapperParser().parse(decoy + laughs(4, 10, 4000))
            }
            assertTrue(
                "${kind}里的假 DOCTYPE 不得抢占定位，实际：${e.message}",
                e.message.orEmpty().contains("实体展开"),
            )
        }
    }

    @Test
    fun `子集结束符前有空白的载荷仍被扫描`() {
        // 改前：`]` 与 `>` 之间的空白（实测 JDK 接受 `] >` 形式）让 indexOf("]>") 找不到子集 → 完全不扫
        assertEquals(
            "`] >` 形式的子集在上限内须照常解析",
            1,
            XmlMapperParser().parse(singleEntityXml(SecureXml.MAX_ENTITY_EXPANSION_CHARS.toInt()).replace("]>", "] >"))
                .statements.size,
        )
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(
                singleEntityXml(SecureXml.MAX_ENTITY_EXPANSION_CHARS.toInt() + 1).replace("]>", "] >"),
            )
        }
        assertTrue("拒绝原因须点明膨胀判据，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `参数实体携带的声明对三项判据同时可见`() {
        // 改前：参数实体字面量里的声明对预扫不可见（`%hide;` 引用被当作 1 字符），声明数/深度/字符数判据同时失明
        // ① 声明数：300 层链全在 PE 字面量里 → 改前可见声明数 1（PASS），真值 302
        val countError = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(peCarriedChain(depth = 300))
        }
        assertTrue(
            "PE 携带的声明须计入声明数（302 = 1 个 PE 声明 + 301 个实体声明），实际：${countError.message}",
            countError.message.orEmpty().contains("实体声明数 302"),
        )

        // ② 引用深度：20 层链全在 PE 字面量里（声明数 21 未触上限，只有深度判据能拦）
        val depthError = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(peCarriedChain(depth = 20))
        }
        assertTrue("PE 携带的引用链深度须参与判据，实际：${depthError.message}", depthError.message.orEmpty().contains("嵌套深度"))

        // ③ 展开字符数：4 层 × 10 引用 × 1000 字符全在 PE 字面量里 → 改前预扫 PASS + 展开 10,000,016 字符
        val charsError = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(peCarriedChain(depth = 4, payloadLen = 1000, fanout = 10))
        }
        assertTrue("PE 携带的展开量须参与判据，实际：${charsError.message}", charsError.message.orEmpty().contains("实体展开"))
    }

    @Test
    fun `非ASCII实体名同样参与膨胀判据`() {
        // 改前：实体名正则要求 `[A-Za-z_]` 起头，`<!ENTITY é "…">` 与其引用 `&é;` 双双漏认
        // （实测 100,000 字符的 é 实体被引用 10 次 → 展开 1,000,016 字符且预扫 PASS）
        val name = "\u00e9"
        val e = assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse(
                """<!DOCTYPE mapper [<!ENTITY $name "${"y".repeat(65_537)}">]>
                   <mapper namespace="n"><select id="s1">SELECT '&$name;' FROM t</select></mapper>""".trimIndent(),
            )
        }
        assertTrue("非 ASCII 实体名须参与膨胀判据，实际：${e.message}", e.message.orEmpty().contains("实体展开"))
        // 上限内的非 ASCII 实体名不得误伤
        val parsed = XmlMapperParser().parse(
            """<!DOCTYPE mapper [<!ENTITY $name "ok">]>
               <mapper namespace="n"><select id="s1">SELECT '&$name;' FROM t</select></mapper>""".trimIndent(),
        )
        assertEquals(
            "SELECT 'ok' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `实体引用深度边界与声明顺序无关`() {
        // 改前：递归护栏 `visiting.size >= 16` 让「头先声明」的 16 层链被误拒（实测 head-first 16 层 REJECT、
        // leaf-first 16 层 PASS）——深度判据不该依赖声明顺序
        listOf(false to "叶先声明", true to "头先声明").forEach { (headFirst, kind) ->
            assertEquals(
                "${kind}的 16 层引用链（恰在上限）须放行",
                1,
                XmlMapperParser().parse(chain(16, headFirst)).statements.size,
            )
            val e = assertThrows(XmlParseException::class.java) {
                XmlMapperParser().parse(chain(17, headFirst))
            }
            assertTrue("${kind}的 17 层引用链须按深度拒绝，实际：${e.message}", e.message.orEmpty().contains("嵌套深度"))
        }
    }

    @Test
    fun `菱形复用实体不误判成环`() {
        // 护栏：d3 → {d2, d1}、d2 → {d1, d0}，d1 有两条可达路径（DAG 复用，非环）——不得报「成环」
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper [
            <!ENTITY d0 "x">
            <!ENTITY d1 "&d0;&d0;">
            <!ENTITY d2 "&d1;&d1;">
            <!ENTITY d3 "&d2;&d1;">
            ]>
            <mapper namespace="n"><select id="s1">SELECT '&d3;' FROM t</select></mapper>
            """.trimIndent(),
        )
        assertEquals(
            "SELECT 'xxxxxx' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `子集内处理指令与注释里的文本不算声明`() {
        // 与 JDK 同口径：`<?pi <!ENTITY evil "…"> ?>` 在子集里**不声明** evil（实测 JDK 报
        // 「引用了实体 evil 但未声明它」），故预扫也不得把它当声明——否则一段 70,000 字符的 PI 文本
        // 会把合法文件误判为「实体展开超限」（JDK 实测接受该文件）
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper [
            <?pi <!ENTITY evil "${"y".repeat(70_000)}"> ?>
            <!-- <!ENTITY evil "${"y".repeat(70_000)}"> -->
            <!ENTITY ok "OK">
            ]>
            <mapper namespace="n"><select id="s1">SELECT '&ok;' FROM t</select></mapper>
            """.trimIndent(),
        )
        assertEquals(
            "SELECT 'OK' FROM t",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `官方DTD整段内联不被误伤`() {
        // 真官方 DTD 文本（test classpath 上的 mybatis-3.5.19 资源，实测 7,819 字节 / ELEMENT 33 /
        // ATTLIST 28 / ENTITY 0 / 无 `%` 引用 / 无 `]>`）整段内联：T-XDH-07 的可见化只对 `<!ENTITY` 与
        // `%name;` 生效，官方 DTD 两者皆无——声明数 0 早退，子集切分与改前同区间。
        // 首行的 XML 声明必须去掉（`<?xml` 不是合法 PI 目标，JDK 直接拒；内联 DTD 时本就不能带它）。
        val dtd = requireNotNull(
            javaClass.classLoader.getResourceAsStream("org/apache/ibatis/builder/xml/mybatis-3-mapper.dtd"),
        ) { "mybatis-3-mapper.dtd 不在 test classpath（mybatis jar 传递依赖缺失？）" }
            .bufferedReader().use { it.readText() }
            .lineSequence()
            .filterNot { it.trimStart().startsWith("<?xml") }
            .joinToString("\n")
        val parsed = XmlMapperParser().parse(
            """
            <!DOCTYPE mapper [
            $dtd
            <!ENTITY status "ACTIVE">
            ]>
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE status = '&status;'</select>
            </mapper>
            """.trimIndent(),
        )
        assertEquals(
            "SELECT * FROM t WHERE status = 'ACTIVE'",
            XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql().sql
                .replace(Regex("\\s+"), " ").trim(),
        )
    }

    // ------------------------------------------------------------------
    // 构造器与源码扫描工具
    // ------------------------------------------------------------------

    /** [length] 个字符的单实体（无引用）——展开估算恰为 [length]，用于钉住字符数上限边界。 */
    private fun singleEntityXml(length: Int): String =
        """<!DOCTYPE mapper [<!ENTITY big "${"y".repeat(length)}">]>
           <mapper namespace="n"><select id="s1">SELECT '&big;' FROM t</select></mapper>""".trimIndent()

    /** [count] 个内部字面量参数实体声明。 */
    private fun manyParamDeclarations(count: Int): String = buildString {
        append("<!DOCTYPE mapper [\n")
        for (index in 0 until count) append("<!ENTITY % p").append(index).append(" \"\">\n")
        append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT 'x' FROM t</select></mapper>")
    }

    /** 指数膨胀实体定义（billion laughs 形态）：层级 [levels]、每层 [fanout] 次引用、叶子 [payloadLen] 字符。 */
    private fun laughs(levels: Int, fanout: Int, payloadLen: Int): String = buildString {
        append("<!DOCTYPE mapper [\n<!ENTITY e0 \"").append("x".repeat(payloadLen)).append("\">\n")
        for (level in 1..levels) {
            append("<!ENTITY e").append(level).append(" \"")
            repeat(fanout) { append("&e").append(level - 1).append(';') }
            append("\">\n")
        }
        append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&e").append(levels)
            .append(";' FROM t</select></mapper>")
    }

    /** [count] 个内部字面量实体声明。 */
    private fun manyDeclarations(count: Int): String = buildString {
        append("<!DOCTYPE mapper [\n")
        for (index in 0 until count) append("<!ENTITY d").append(index).append(" \"v").append(index).append("\">\n")
        append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&d0;' FROM t</select></mapper>")
    }

    /** [depth] 层链式实体引用（c0 字面量，cN 引用 c(N-1)）；[headFirst] 时链头先于叶子声明。 */
    private fun deepChain(depth: Int): String = chain(depth, headFirst = false)

    /** [depth] 层链式引用；[headFirst] = true 时先声明链头（声明顺序不得影响深度判据）。 */
    private fun chain(depth: Int, headFirst: Boolean): String = buildString {
        append("<!DOCTYPE mapper [\n")
        if (headFirst) {
            for (level in depth downTo 1) {
                append("<!ENTITY c").append(level).append(" \"&c").append(level - 1).append(";\">\n")
            }
            append("<!ENTITY c0 \"base\">\n")
        } else {
            append("<!ENTITY c0 \"base\">\n")
            for (level in 1..depth) {
                append("<!ENTITY c").append(level).append(" \"&c").append(level - 1).append(";\">\n")
            }
        }
        append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&c").append(depth)
            .append(";' FROM t</select></mapper>")
    }

    /** 在 [laughs] 载荷的子集首行插入 [decoy]（切分诱饵）——复现「子集切分被诱饵提前截断」的绕过形态。 */
    private fun laughsWithDecoy(decoy: String): String =
        laughs(4, 10, 4000).replace("<!DOCTYPE mapper [\n", "<!DOCTYPE mapper [\n$decoy\n")

    /**
     * 参数实体携带的声明链：`<!ENTITY % hide "<!ENTITY b0 '…'>…">%hide;`——**全部声明都在 PE 字面量里**
     * （改前对预扫不可见），供声明数 / 深度 / 字符数三项判据各自的可见性用例使用。
     */
    private fun peCarriedChain(depth: Int, payloadLen: Int = 1, fanout: Int = 1): String = buildString {
        append("<!DOCTYPE mapper [\n<!ENTITY % hide \"<!ENTITY b0 '").append("B".repeat(payloadLen)).append("'>")
        for (level in 1..depth) {
            append("<!ENTITY b").append(level).append(" '")
            repeat(fanout) { append("&b").append(level - 1).append(';') }
            append("'>")
        }
        append("\">\n%hide;\n]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&b")
            .append(depth).append(";' FROM t</select></mapper>")
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** src/main 全部 Kotlin 源码（路径 → 剥注释后的文本）；字符串字面量保留（URI 里的 `//` 不能被截）。 */
    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val c = source[index]
            when {
                c == '"' || c == '\'' -> {
                    val quote = c.toString()
                    val marker = if (source.startsWith(quote + quote + quote, index)) quote + quote + quote else quote
                    val end = source.indexOf(marker, index + marker.length)
                    val stop = if (end < 0) source.length else end + marker.length
                    out.append(source, index, stop)
                    index = stop
                }
                source.startsWith("//", index) -> while (index < source.length && source[index] != '\n') index++
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    index = if (end < 0) source.length else end + 2
                }
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** 外部实体 feature URI（配置收敛判据锚点）。 */
        const val EXTERNAL_ENTITIES_URI = "http://xml.org/sax/features/external-general-entities"
    }
}
