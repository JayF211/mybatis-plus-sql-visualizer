package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SecureXml 工厂/构建器按线程复用测试（T-PerfSweep6 T-PS6-03②）。
 *
 * 覆盖点：
 *  1. 默认回调路径下同线程两次取工厂/构建器 → 同一实例（缓存生效，省掉 JAXP 工厂装配）；
 *  2. 传自定义回调（测试收集器形态）→ 现建现用（否则「feature 不被支持」的告警只会每线程报一次）；
 *  3. 跨线程各持一份（`DocumentBuilderFactory` / `DocumentBuilder` 都非线程安全）；
 *  4. 复用构建器后解析结果正确（同一构建器可反复 parse，各自产出新 Document）；
 *  5. **预扫语义不因缓存削弱**——`checkEntityExpansion` 是文本级静态扫描，每次 parse 照跑：
 *     同一超限载荷连解两次都须抛 [XmlEntityExpansionException]（缓存若把预扫一起吃掉，第二次会放行）。
 */
class SecureXmlFactoryCacheTest {

    @Test
    fun `同线程两次取工厂与构建器复用同一实例`() {
        assertSame(
            "默认回调路径下同线程应复用同一工厂（T-PS6-03② 的省法就在这里）",
            SecureXml.newDocumentBuilderFactory(),
            SecureXml.newDocumentBuilderFactory(),
        )
        assertSame(
            "构建器同为非线程安全对象，按线程复用",
            SecureXml.newDocumentBuilder(),
            SecureXml.newDocumentBuilder(),
        )
    }

    @Test
    fun `自定义回调不复用_告警不被缓存吃掉`() {
        val w1 = mutableListOf<String>()
        val w2 = mutableListOf<String>()
        assertNotSame(
            "传自定义回调时必须现建现用——缓存会把 feature 告警压到每线程一次，观测面被吃掉",
            SecureXml.newDocumentBuilderFactory(w1::add),
            SecureXml.newDocumentBuilderFactory(w2::add),
        )
        // 既有 SecureXmlTest 的安全配置断言走的就是这条路径，读数必须与缓存引入前一致
        val factory = SecureXml.newDocumentBuilderFactory(w1::add)
        assertEquals(false, factory.getFeature(SecureXml.EXTERNAL_GENERAL_ENTITIES_FEATURE))
        assertEquals(false, factory.getFeature(SecureXml.DOCTYPE_DECL_FEATURE))
    }

    @Test
    fun `跨线程各持一份工厂`() {
        val main = SecureXml.newDocumentBuilderFactory()
        var other: javax.xml.parsers.DocumentBuilderFactory? = null
        val latch = CountDownLatch(1)
        val t = Thread({ other = SecureXml.newDocumentBuilderFactory(); latch.countDown() }, "securexml-probe")
        t.isDaemon = true
        t.start()
        assertTrue("子线程未在 5s 内完成", latch.await(5, TimeUnit.SECONDS))
        assertNotSame(
            "DocumentBuilderFactory 非线程安全，跨线程必须各持一份（ThreadLocal 而非单例）",
            main,
            other,
        )
    }

    @Test
    fun `复用构建器后反复解析结果正确`() {
        val xml = "<mapper namespace=\"n\"><select id=\"s\">SELECT 1</select></mapper>"
        repeat(3) {
            val doc = SecureXml.parse(xml)
            assertEquals("mapper", doc.documentElement.tagName)
            assertEquals("n", doc.documentElement.getAttribute("namespace"))
        }
    }

    @Test
    fun `预扫语义不因工厂缓存削弱`() {
        val overLimit = manyDeclarations(SecureXml.MAX_ENTITY_DECLARATIONS + 1)
        repeat(2) { round ->
            assertThrows(
                "第 ${round + 1} 次解析也必须被预扫拦下——缓存只覆盖工厂/构建器，" +
                    "checkEntityExpansion 每次 parse 都要跑",
                XmlEntityExpansionException::class.java,
            ) { SecureXml.parse(overLimit) }
        }
    }

    /** 与 `SecureXmlTest.manyDeclarations` 同形（实体声明数超限载荷）。 */
    private fun manyDeclarations(count: Int): String = buildString {
        append("<!DOCTYPE mapper [\n")
        for (index in 0 until count) append("<!ENTITY d").append(index).append(" \"v").append(index).append("\">\n")
        append("]>\n<mapper namespace=\"n\"><select id=\"s1\">SELECT '&d0;' FROM t</select></mapper>")
    }
}
