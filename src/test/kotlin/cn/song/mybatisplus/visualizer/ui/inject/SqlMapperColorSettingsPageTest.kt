package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W6 颜色设置页用例。
 *
 * 核心判据（**覆盖守卫**）：高亮器对**任一** token 类型返回的每个键，都必须在颜色页有描述符——
 * 这样 W2/W3 之后若再新增键却忘了加描述符，用例必红（防「新键在设置页里调不到」的静默形态）。
 * token 类型经反射枚举 `SqlMapperTT` 的 `@JvmField` 字段，未来加 token 无需改本用例。
 */
class SqlMapperColorSettingsPageTest {

    private val page = SqlMapperColorSettingsPage()

    @Test
    fun testW6_描述符覆盖全部高亮键() {
        val highlighter = page.getHighlighter()
        val described = page.getAttributeDescriptors().map { it.key }.toSet()
        assertTrue("颜色页描述符不得为空", described.isNotEmpty())

        val missing = mutableListOf<String>()
        val tokenFields = SqlMapperTT::class.java.fields
        assertTrue("SqlMapperTT 应有 token 字段", tokenFields.isNotEmpty())
        for (f in tokenFields) {
            val tokenType = f.get(null) as? IElementType ?: continue
            for (key in highlighter.getTokenHighlights(tokenType)) {
                if (key !in described) missing += "${f.name} -> ${key.externalName}"
            }
        }
        assertTrue("每个高亮键都要有颜色页描述符（缺：$missing）", missing.isEmpty())
    }

    @Test
    fun testW6_两个占位符各自可调且键不同() {
        val keys = page.getAttributeDescriptors().map { it.key }
        assertTrue("PARAM_HASH 须在描述符内", SqlMapperSyntaxHighlighter.PARAM_HASH in keys)
        assertTrue("PARAM_DOLLAR 须在描述符内", SqlMapperSyntaxHighlighter.PARAM_DOLLAR in keys)
        assertTrue(
            "`#{}` 与 \${} 必须是两个不同的键（W2 分色的设置页侧保证）",
            SqlMapperSyntaxHighlighter.PARAM_HASH != SqlMapperSyntaxHighlighter.PARAM_DOLLAR,
        )
    }

    @Test
    fun testW6_显示名与预览样例在场() {
        assertTrue("显示名不得为空", page.getDisplayName().isNotBlank())
        val demo = page.getDemoText()
        assertTrue("预览样例不得为空", demo.isNotBlank())
        assertTrue("预览须含 #{} 占位", demo.contains("#{"))
        assertTrue("预览须含 \${} 占位", demo.contains("\${"))
        assertTrue("预览须含注释（覆盖注释键）", demo.contains("--"))
    }

    @Test
    fun testW6_颜色描述符为空数组() {
        assertTrue("本页不含自有 ColorKey（全部是 TextAttributesKey）", page.getColorDescriptors().isEmpty())
    }

    /** EP 接线守卫：plugin.xml 须声明 colorSettingsPage。 */
    @Test
    fun testW6_pluginXml_颜色页接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "plugin.xml 须含 <colorSettingsPage implementation=…SqlMapperColorSettingsPage>",
            Regex("""<colorSettingsPage\s+implementation="cn\.song\.mybatisplus\.visualizer\.ui\.inject\.SqlMapperColorSettingsPage"\s*/>""")
                .containsMatchIn(xml),
        )
    }
}
