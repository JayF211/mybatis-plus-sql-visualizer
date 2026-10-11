package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperInjector
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage
import cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import java.io.File

/**
 * T-XmlSynHi 注入平价守卫（20261010 用户真机现象驱动）：同一 mapper 内，
 * `<select>` 语句体内**含 `<include refid=.../>` 与不含**的两块，直属文本节点的
 * MpSqlMapper 注入位必须等价（用户报「含 include 的语句块整块无着色也无补全」）。
 *
 * 注入器契约：逐 [XmlText] 独立注入（SqlMapperInjector D2），守卫链（设置/per-host 让位/
 * mapper 祖先/长度熔断）均不含 include/resultMap 维度——本用例把「同文件双语句」钉成回归
 * 基线：任何让某个 text 节点因相邻 `<include>` 而掉位的改动在此显形。
 *
 * 探针缝固定「平台不注入」（wiring/injected-chain 套件同口径）；物理临时文件（注入 enumerate
 * 管线对非物理文件短路，同 XmlSqlCompletionInjectedChainTest 口径）。
 */
class XmlMapperInjectorIncludeParityTest : BasePlatformTestCase() {

    private val originalProbe = XmlSynHiGate.foreignInjectionProbe

    override fun setUp() {
        super.setUp()
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(SqlMapperInjector(), testRootDisposable)
    }

    override fun tearDown() {
        XmlSynHiGate.foreignInjectionProbe = originalProbe
        super.tearDown()
    }

    private fun openMapper(content: String): XmlFile {
        val dir = FileUtil.createTempDirectory("xmlinclparity", "", true)
        val ioFile = File(dir, "mapper.xml").apply { writeText(content) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: throw IllegalStateException("temp file not in VFS")
        myFixture.configureFromExistingVirtualFile(vf)
        val file = myFixture.file as XmlFile
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.getDocument(file)?.let { doc -> if (!pdm.isCommitted(doc)) pdm.commitDocument(doc) }
        return file
    }

    /** text 节点 → 是否被注入 MpSqlMapper（enumerate 预热后按 host 查；语言取自注入文件身份）。 */
    private fun injectedLanguagesOf(file: XmlFile): Map<XmlText, Set<String>> {
        val manager = InjectedLanguageManager.getInstance(project)
        val hosts = PsiTreeUtil.collectElements(file) { it is XmlText }.filterIsInstance<XmlText>()
        val result = linkedMapOf<XmlText, Set<String>>()
        for (host in hosts) {
            val langs = mutableSetOf<String>()
            manager.enumerateEx(host, file, false) { injectedPsi, places ->
                if (places.isNotEmpty()) langs.add(injectedPsi.language.id)
            }
            result[host] = langs
        }
        return result
    }

    fun test含include与不含include的语句文本注入位等价() {
        val file = openMapper(
            """<mapper namespace="com.example.UserMapper">
  <sql id="Base_Column_List">id, username</sql>
  <select id="withInclude" resultMap="BaseResultMap">
    select
    <include refid="Base_Column_List"/>
    from user
  </select>
  <select id="noInclude" resultMap="BaseResultMap">
    select id, username
    from user
  </select>
</mapper>""",
        )

        val map = injectedLanguagesOf(file)
        fun textsOf(selectId: String): List<Pair<String, Set<String>>> =
            PsiTreeUtil.collectElements(file) {
                it is XmlText && (it.parent as? com.intellij.psi.xml.XmlTag)?.getAttributeValue("id") == selectId
            }.filterIsInstance<XmlText>()
                .filter { StringUtil.trim(it.text).isNotEmpty() }
                .map { StringUtil.trim(it.text) to (map[it] ?: emptySet()) }

        val withInclude = textsOf("withInclude")
        val noInclude = textsOf("noInclude")

        assertTrue("夹具应含 include 块的 text 节点：$withInclude", withInclude.isNotEmpty())
        assertTrue("夹具应含对照块的 text 节点：$noInclude", noInclude.isNotEmpty())

        for ((text, langs) in withInclude) {
            assertTrue(
                "含 include 块的文本「$text」应注入 MpSqlMapper（与对照块平价，实际注入: $langs）",
                langs.any { it == SqlMapperLanguage.INSTANCE.id || it == "MpSqlMapper" },
            )
        }
        for ((text, langs) in noInclude) {
            assertTrue(
                "对照块文本「$text」应注入 MpSqlMapper（实际注入: $langs）",
                langs.any { it == SqlMapperLanguage.INSTANCE.id || it == "MpSqlMapper" },
            )
        }
    }
}
