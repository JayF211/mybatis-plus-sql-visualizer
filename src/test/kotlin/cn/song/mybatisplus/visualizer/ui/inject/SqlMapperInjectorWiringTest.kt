package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep16 F37 接线级测试（批次 7 教训：lexer/纯函数级测试探不到接线断裂）——
 * 注入器经**真实平台管线**（elementsToInjectIn 键匹配 → 元素级喂入 → startInjecting/addPlace/
 * doneInjecting 落地含 ParserDefinition）收集注入位：
 *  - mapper 文件：语句文本 + 白名单属性注入位均收集（按 [SqlMapperLanguage] 过滤计数，
 *    免受同环境其他 EP 注入器——Spring/Angular 族——对同文件的注入噪声）；
 *  - 非 mapper XML：零注入位；
 *  - shouldYield 让位态（置设置开关关）：零注入位。
 *
 * 形态注（两坑实证）：
 *  1. 文件须**物理临时文件**（LocalFileSystem）——enumerate 管线对非物理文件
 *     （configureByText 的 LightVirtualFile）按「非物理不注入」契约直接短路（243
 *     InjectedLanguageUtilBase.enumerate 实证）；
 *  2. enumerate 前须文档已提交——VFS refresh 刚落的文件 document 未提交时管线静默空转
 *     （首跑真红 0 位实证），显式 commitDocument 后稳定。
 */
class SqlMapperInjectorWiringTest : BasePlatformTestCase() {

    private val mapper = """<mapper namespace="n">
  <select id="a">SELECT * FROM t WHERE a = 1
    <if test="a &gt; 1">AND b = 2</if>
  </select>
</mapper>"""

    override fun setUp() {
        super.setUp()
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(SqlMapperInjector(), testRootDisposable)
    }

    /** 物理临时文件形态打开 mapper XML（enumerate 管线只对物理文件跑注入探针）。 */
    private fun openPhysicalFile(content: String): XmlFile {
        val dir = FileUtil.createTempDirectory("ps16wiring", "", true)
        val ioFile = File(dir, "mapper.xml").apply { writeText(content) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: throw IllegalStateException("temp file not in VFS")
        myFixture.configureFromExistingVirtualFile(vf)
        val file = myFixture.file as XmlFile
        // 首跑真红实证：VFS refresh 刚落的文件 document 未提交 → enumerate 管线静默零位
        PsiDocumentManager.getInstance(project).getDocument(file)?.let { doc ->
            if (!PsiDocumentManager.getInstance(project).isCommitted(doc)) {
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
        // dumb 态说明：物理文件入 VFS 触发索引 → 测试运行于 dumb 态。注入器已声明 dumb-aware
        // （纯词法动作不依赖索引），dumb 态下管线照常喂入——无需等 smart。
        return file
    }

    /**
     * 注入 [SqlMapperLanguage] 的注入位总数：对每个喂入候选宿主（XmlText/XmlAttributeValue）
     * 走真实 enumerate 管线计数；按语言过滤——同环境其他 EP 注入器对同文件的注入不计入。
     */
    private fun myLanguagePlaceCount(file: XmlFile): Int {
        val manager = InjectedLanguageManager.getInstance(project)
        val hosts = PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }
        var count = 0
        for (host in hosts) {
            manager.enumerateEx(host, file, false) { injectedPsi, places ->
                if (injectedPsi.language == SqlMapperLanguage) count += places.size
            }
        }
        return count
    }

    @Test
    fun testF37_mapper文件_语句文本与白名单属性注入位均收集() {
        val file = openPhysicalFile(mapper)
        // 语句文本（select 直属文本 + if 内嵌文本）+ test 属性值各 1 位；namespace/id 属性不在
        // 白名单、语句尾部纯空白文本段去首尾后为空 → 均零位
        assertEquals("mapper 文件应收集语句文本与 test 属性注入位", 3, myLanguagePlaceCount(file))
    }

    @Test
    fun testF37_非mapper文件_零注入位() {
        val notMapper = mapper.replace("<mapper namespace=\"n\">", "<configuration>")
        val file = openPhysicalFile(notMapper)
        assertEquals("非 mapper 文件零注入位（根守卫）", 0, myLanguagePlaceCount(file))
    }

    @Test
    fun testF37_让位态_零注入位() {
        val file = openPhysicalFile(mapper)
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        // F47 起闸门读快照（loadState 唯一刷新漏斗）——测试按生产契约经 loadState 切换
        settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
        try {
            assertEquals("设置开关关闭 → shouldYield 让位 → 零注入位（反向验证）", 0, myLanguagePlaceCount(file))
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    @Test
    fun testF37_pluginXml_multiHostInjector_EP接线在场() {
        // EP 声明冻结但接线不可丢——钉住 multiHostInjector 声明（同 typedHandler EP 测试口径）
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        org.junit.Assert.assertTrue(
            "plugin.xml 须含 <multiHostInjector implementation=…SqlMapperInjector>",
            Regex("<multiHostInjector\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.inject\\.SqlMapperInjector\"")
                .containsMatchIn(xml),
        )
    }
}
