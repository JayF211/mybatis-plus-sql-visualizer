package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W6 修订用例：**宿主 XML 文本**侧的拼写豁免（[SqlMapperXmlSpellcheckingStrategy]）。
 *
 * 背景（沙盒走查实证）：注入语言层的豁免管不到宿主 XmlText 上的拼写检查，SQL 文本仍出 typo 波浪线。
 * 本类钉住修法的两条边界：
 *  - mapper 语句文本 → 空 tokenizer（豁免）；
 *  - 其余（非 mapper 文件的文本、mapper 内的属性值）→ **原样委托平台策略**（非空，行为不变）。
 */
class SqlMapperXmlSpellcheckingStrategyTest : BasePlatformTestCase() {

    private val strategy = SqlMapperXmlSpellcheckingStrategy()

    private fun firstText(): XmlText =
        PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java)
            .first { it.text.isNotBlank() }

    @Test
    fun testW6修订_mapper语句文本_豁免拼写检查() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <select id="a">
    SELECT * FROM t WHERE a = #{id}
  </select>
</mapper>""",
        )
        assertSame(
            "mapper 语句文本须返回空 tokenizer（豁免）",
            SpellcheckingStrategy.EMPTY_TOKENIZER,
            strategy.getTokenizer(firstText()),
        )
    }

    @Test
    fun testW6修订_sql片段文本_同样豁免() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <sql id="frag">
    id, name
  </sql>
</mapper>""",
        )
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, strategy.getTokenizer(firstText()))
    }

    @Test
    fun testW6修订_非mapper文件文本_原样委托() {
        // 夹具须含**非空白文本节点**（配置文件的纯空白文本会被 firstText 的 isNotBlank 过滤掉，
        // 首跑真红实证：NoSuchElementException）——用 <properties> 里的一段普通文本承载。
        myFixture.configureByText(
            "mybatis-config.xml",
            """<configuration>
  <properties>
    plain text that the platform spellchecker may inspect
  </properties>
</configuration>""",
        )
        assertNotSame(
            "非 mapper 文件的文本不得被豁免（原样委托平台策略）",
            SpellcheckingStrategy.EMPTY_TOKENIZER,
            strategy.getTokenizer(firstText()),
        )
    }

    @Test
    fun testW6修订_mapper内属性值_不碰() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="a">SELECT 1</select></mapper>""",
        )
        val attrValue = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlAttributeValue::class.java)
            .first { it.value == "a" }
        assertNotSame(
            "属性值不豁免（本类只豁免语句文本，属性/注释按平台原语义）",
            SpellcheckingStrategy.EMPTY_TOKENIZER,
            strategy.getTokenizer(attrValue),
        )
    }

    /** EP 接线守卫：plugin.xml 须声明 language=XML 的 spellchecker.support。 */
    @Test
    fun testW6修订_pluginXml_XML级拼写策略接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "plugin.xml 须含 <spellchecker.support language=\"XML\" …SqlMapperXmlSpellcheckingStrategy>",
            Regex(
                "<spellchecker\\.support\\s+language=\"XML\"[^>]*implementationClass=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.inject\\.SqlMapperXmlSpellcheckingStrategy\"",
            ).containsMatchIn(xml),
        )
    }
}
