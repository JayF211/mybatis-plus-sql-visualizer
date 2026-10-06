package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.fakeprobe.FakePlatformInjector
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlSynHi D68：平台让位线 per-host 探测——门与探测主体分级用例。
 *
 * 背景定案（字节码实证，出处见 [XmlSynHiGate] KDoc）：`SqlLanguageInjector` 在 243 构建发行物
 * 与用户真机 IU-262.10968.63 均不存在，「类在场即让位」在新平台结构性失效；现行平台 SQL 注入器
 * （SqlTypeBasedInjector 两代）喂入类型全为 SQL PSI，mapper XML 宿主平台从不着色——让位门槛
 * 下沉为「平台会对**该宿主**注入」探测。
 *
 * 分级：
 *  - 门级（seam）：[XmlSynHiGate.platformInjectionProbe] 缝注入三形态——会注入→让位（关键新语义：
 *    Ultimate 平台真会着色的位不双写）、不会注入→不让位（关键修复：平台未覆盖位由本插件补色）、
 *    抛异常→让位（fail-safe 回保守）；另钉共通线短路（开关/三方命中时探针零咨询）。
 *  - 主体级：[XmlSynHiGate.platformInjectorsWouldInject] 直注假注入器清单（外部包三类过滤 +
 *    自身族排除 + 喂入契约预筛 + startInjecting 捕获 + 试探异常保守让位）。
 *  - 默认探针冒烟：真实测试平台 EP 上不炸、可判定。
 */
class XmlSynHiGatePlatformProbeTest : BasePlatformTestCase() {

    private fun firstMapperText(): XmlText {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val file = myFixture.file
        return PsiTreeUtil.collectElementsOfType(file, XmlText::class.java).first()
    }

    private fun withProbeSeam(
        probe: (com.intellij.openapi.project.Project?, PsiElement) -> Boolean,
        block: () -> Unit,
    ) {
        val original = XmlSynHiGate.platformInjectionProbe
        XmlSynHiGate.platformInjectionProbe = probe
        try {
            block()
        } finally {
            XmlSynHiGate.platformInjectionProbe = original
        }
    }

    // ------------------------------------------------------------------
    // 门级（seam 三形态 + 短路序）
    // ------------------------------------------------------------------

    /** 关键新语义（对应旧「类在场即让位」用例的改造）：平台探针判定会注入 → 让位。 */
    @Test
    fun testF68_探针判定会注入_让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> true }) {
            assertTrue("平台会对该宿主注入 → 让位（不双写）", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 关键修复用例：平台探针判定不会注入 → 不让位（Ultimate 平台未覆盖位由本插件补色）。 */
    @Test
    fun testF68_探针判定不会注入_不让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> false }) {
            assertFalse(
                "平台不会注入该宿主 → 不让位（本插件补色；旧形态类在场一票否决即杀这里）",
                XmlSynHiGate.shouldYield(project, host),
            )
        }
    }

    /** fail-safe：探针抛异常 → 让位（D68 口径：探测不了时回到保守让位）。 */
    @Test
    fun testF68_探针抛异常_保守让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> throw IllegalStateException("probe boom") }) {
            assertTrue("探针异常 → 让位（fail-safe）", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 探针收到的是分发后的同一个宿主实例（per-host 语义的形态证据）。 */
    @Test
    fun testF68_探针收到分发后同款宿主() {
        val host = firstMapperText()
        var received: PsiElement? = null
        withProbeSeam({ _, h -> received = h; false }) {
            XmlSynHiGate.shouldYield(project, host)
        }
        assertSame("per-host 探针须收到喂入的宿主实例", host, received)
    }

    /** 短路序：设置开关关 → 探针零咨询（喂入税面上探针只在共通线全过才走）。 */
    @Test
    fun testF68_开关关_探针零咨询() {
        val host = firstMapperText()
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        var consulted = 0
        try {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
            withProbeSeam({ _, _ -> consulted++; true }) {
                assertTrue(XmlSynHiGate.shouldYield(project, host))
            }
            assertEquals("开关关短路 → 探针零咨询", 0, consulted)
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    /** 短路序：三方 MCH-Pro 在位 → 探针零咨询（缓存语义沿用 F38：先清缓存保证走真实解析）。 */
    @Test
    fun testF68_三方在位_探针零咨询() {
        val host = firstMapperText()
        val originalProbe = XmlSynHiGate.codeHelperProbe
        XmlSynHiGate.codeHelperProbe = { true }
        XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        var consulted = 0
        try {
            withProbeSeam({ _, _ -> consulted++; true }) {
                assertTrue(XmlSynHiGate.shouldYield(project, host))
            }
            assertEquals("三方命中短路 → 探针零咨询", 0, consulted)
        } finally {
            XmlSynHiGate.codeHelperProbe = originalProbe
            XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        }
    }

    // ------------------------------------------------------------------
    // 主体级（platformInjectorsWouldInject 直注清单）
    // ------------------------------------------------------------------

    /** 平台系注入器：喂入命中 + startInjecting 被调 → 让位。 */
    @Test
    fun testF68_平台注入器_喂入命中且start_让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(XmlText::class.java), start = true)
        assertTrue(
            "平台注入器对该宿主 startInjecting → 让位",
            XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals("喂入命中须真实咨询", 1, fake.consulted)
    }

    /** 平台系注入器：喂入命中但未 startInjecting（如用户未配置注入规则）→ 不让位。 */
    @Test
    fun testF68_平台注入器_喂入命中但未start_不让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(XmlText::class.java), start = false)
        assertFalse(
            "咨询后未 startInjecting → 平台不给该宿主着色 → 不让位",
            XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals(1, fake.consulted)
    }

    /** 喂入契约预筛：elementsToInjectIn 不含宿主类型 → 不咨询、不让位。 */
    @Test
    fun testF68_平台注入器_喂入不命中_不咨询不让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(com.intellij.psi.PsiFile::class.java))
        assertFalse(XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
        assertEquals("喂入契约预筛不命中 → 零咨询（243 probeElementsUpInner 同口径）", 0, fake.consulted)
    }

    /** 自身族排除：本插件包前缀的注入器即使会 start 也不让位（自身让位由其余各线负责）。 */
    @Test
    fun testF68_自身族注入器_排除不咨询() {
        val host = firstMapperText()
        val fake = FakeSelfInjector(listOf(XmlText::class.java))
        assertFalse(XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
        assertEquals("自身族 EP 在册项被包前缀排除", 0, fake.consulted)
    }

    /** MCH-Pro 族排除：com.ccnode.* 注入器不进 per-host 探测（行为归三方安装态线）。 */
    @Test
    fun testF68_codeHelper族注入器_排除不咨询() {
        val host = firstMapperText()
        val fake = com.ccnode.fakeprobe.FakeCodeHelperInjector(listOf(XmlText::class.java))
        assertFalse(XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
        assertEquals("三方族由 codeHelperActive() 线负责", 0, fake.consulted)
    }

    /** 非平台包排除：未知三方注入器不咨询、不让位（让位清单收口在 设置/平台/MCH-Pro 三线）。 */
    @Test
    fun testF68_非平台包注入器_排除不咨询() {
        val host = firstMapperText()
        val fake = org.example.fakeprobe.FakeUnknownInjector(listOf(XmlText::class.java))
        assertFalse(XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
        assertEquals(0, fake.consulted)
    }

    /** 试探异常保守让位：注入器 startInjecting 后/试探中炸 → 视为会让位（反方向有双写叠色风险）。 */
    @Test
    fun testF68_注入器试探抛异常_保守让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(XmlText::class.java), throwOnInject = true)
        assertTrue("试探异常 → 保守让位（fail-safe）", XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
    }

    /** 喂入类型表读取异常同口径：保守让位。 */
    @Test
    fun testF68_喂入类型表抛异常_保守让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(emptyList(), throwOnFeed = true)
        assertTrue("elementsToInjectIn 异常 → 保守让位（fail-safe）", XmlSynHiGate.platformInjectorsWouldInject(listOf(fake), host))
    }

    // ------------------------------------------------------------------
    // 默认探针冒烟（真实测试平台 EP）
    // ------------------------------------------------------------------

    /**
     * 真实 EP 冒烟：裸测试平台（无 DatabaseTools/IntelliLang 注入配置）上默认探针可判定且不炸；
     * XmlText/XmlAttributeValue 宿主面无平台注入器命中 → 判 false（该断言同时钉住「裸环境不让位」
     * ——注入链在此环境必须活）。
     */
    @Test
    fun testF68_默认探针_真实EP环境_不炸且裸环境判不让位() {
        val host = firstMapperText()
        val result = XmlSynHiGate.platformInjectionProbe(project, host)
        assertFalse("裸测试平台 EP 无 XML 宿主命中 → 不让位（注入链活）", result)
    }

    /** 空清单形态：无在册注入器 → 不让位（Community 无 DatabaseTools 的常态）。 */
    @Test
    fun testF68_空注入器清单_不让位() {
        val host = firstMapperText()
        assertFalse(XmlSynHiGate.platformInjectorsWouldInject(emptyList(), host))
    }
}

/** 自身族假注入器（包名 = 本插件族前缀 → 排除组；与被测门同包，模拟本插件 EP 在册项）。 */
internal class FakeSelfInjector(
    private val fedTypes: List<Class<out PsiElement>>,
) : MultiHostInjector {

    var consulted: Int = 0
        private set

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        val host = context as? PsiLanguageInjectionHost ?: return
        registrar.startInjecting(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE)
            .addPlace(null, null, host, TextRange(0, host.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = fedTypes
}
