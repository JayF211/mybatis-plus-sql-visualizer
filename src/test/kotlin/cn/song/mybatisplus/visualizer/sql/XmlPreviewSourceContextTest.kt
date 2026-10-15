package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.context.ActualSqlPsiResolver
import cn.song.mybatisplus.visualizer.context.PreviewSourceOrigin
import cn.song.mybatisplus.visualizer.context.SqlPsiResolution
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperInjector
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** O-145 W1/W2：XML 真 PSI/注入宿主 + 作用域数据源的端到端回归。 */
class XmlPreviewSourceContextTest : BasePlatformTestCase() {
    private val originalLookup = ActualSqlPsiResolver.sqlFileClassLookup
    private lateinit var settings: PluginSettings.State

    override fun setUp() {
        super.setUp()
        settings = PluginSettings.getInstance().state.copy()
        PluginSettings.getInstance().state.dialectAuto = true
        ActualSqlPsiResolver.sqlFileClassLookup = { com.intellij.psi.PsiFile::class.java }
        IdeaSqlContextBridge.actualSqlFileForTest = { true }
        DataSourceUrlProbe.probeCandidatesForTest = { emptyList() }
        DataSourceUrlProbe.invalidate(project)
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(XmlSqlInjector(), testRootDisposable)
    }

    override fun tearDown() {
        ActualSqlPsiResolver.sqlFileClassLookup = originalLookup
        IdeaSqlContextBridge.actualSqlFileForTest = null
        IdeaSqlContextBridge.rawFactsForTest = null
        DataSourceUrlProbe.probeCandidatesForTest = null
        DataSourceUrlProbe.invalidate(project)
        PluginSettings.getInstance().loadState(settings)
        super.tearDown()
    }

    fun test两条语句各自携带宿主与准确注入_不误取文件首个() {
        val file = mapper("""<mapper namespace="n"><select id="first">SQL:SELECT 1</select><select id="second">SQL:SELECT 2</select></mapper>""")
        val second = statement(file, "second")
        val offset = file.text.indexOf("SELECT 2") + 2
        val actual = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)
        assertNotNull("必须有真实 XML 宿主注入，禁止空绿", actual)
        val context = XmlPreviewService.resolveSourceContext(file, second, offset)
        assertEquals(PreviewSourceOrigin.XML, context.origin)
        assertSame(file, context.hostPsiFile)
        assertSame(actual!!.containingFile, context.sqlPsiFile)
        assertTrue(context.sqlPsiResolution is SqlPsiResolution.Exact)
        assertEquals("second", XmlPreviewService.preview(file.text, "second", project, sourceContext = context).xmlModel?.statementId)
        assertEquals(
            file.virtualFile?.let { cn.song.mybatisplus.visualizer.context.ModuleIdentityResolver.resolve(project, it) }
                ?: cn.song.mybatisplus.visualizer.context.ModuleIdentityResolution.None,
            context.moduleResolution,
        )
    }

    fun test语句owner限定_多注入不取第一个且越界光标不借用他语句() {
        val file = mapper("""<mapper namespace="n"><select id="first">SQL:SELECT 1</select><select id="second">SQL:SELECT 2<if test="ok">SQL:FROM t</if></select></mapper>""")
        val second = statement(file, "second")
        val context = XmlPreviewService.resolveSourceContext(file, second)
        assertTrue("同一语句内两个注入须为歧义: ${context.sqlPsiResolution}", context.sqlPsiResolution is SqlPsiResolution.Ambiguous)
        assertNull(context.sqlPsiFile)
        val otherOffset = file.text.indexOf("SELECT 1") + 2
        val outside = XmlPreviewService.resolveSourceContext(file, second, otherOffset)
        assertTrue("越界光标不得把第一条注入当第二条的精确命中", outside.sqlPsiResolution is SqlPsiResolution.Ambiguous)
        assertNull(outside.sqlPsiFile)
    }

    fun test自研MpSqlMapper注入不可冒充平台SQL() {
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(SqlMapperInjector(), testRootDisposable)
        val file = mapper("""<mapper namespace="n"><select id="only">select * from t</select></mapper>""")
        val tag = statement(file, "only")
        val offset = file.text.indexOf("select *") + 2
        val injection = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)
        assertNotNull("反例必须实际产生自研注入", injection)
        assertEquals("MpSqlMapper", injection!!.containingFile.language.id)
        val context = XmlPreviewService.resolveSourceContext(file, tag, offset)
        assertNull(context.sqlPsiFile)
        assertEquals(SqlPsiResolution.None, context.sqlPsiResolution)
    }

    fun test源作用域PG与Oracle胜过项目候选并写入渲染方言() {
        val file = mapper("""<mapper namespace="n"><select id="pg">SQL:SELECT 1</select><select id="oracle">SQL:SELECT 2</select></mapper>""")
        IdeaSqlContextBridge.rawFactsForTest = { _, sqlFile, _ ->
            val pg = sqlFile.text.contains("SELECT 1")
            val name = if (pg) "PostgreSQL" else "Oracle"
            val url = if (pg) "jdbc:postgresql://localhost/test" else "jdbc:oracle:thin:@localhost:1521/test"
            IdeaSqlContextBridge.RawFacts(
                provided = listOf(IdeaSqlContextBridge.RawCandidate(name, name, url, name, true)),
                finalCandidates = listOf(IdeaSqlContextBridge.RawCandidate(name, name, url, name, true)),
                configuredScopePresent = true,
                effectiveDialectId = name,
            )
        }
        for ((id, dialect) in listOf("pg" to "PostgreSQL", "oracle" to "Oracle")) {
            val context = XmlPreviewService.resolveSourceContext(file, statement(file, id))
            assertNotNull("$id 应命中当前语句的实际注入", context.sqlPsiFile)
            assertEquals(DialectDecisionEngine.Source.IDEA_PROVIDER, DialectRegistry.decision(context).source)
            val result = XmlPreviewService.preview(file.text, id, project, sourceContext = context)
            assertEquals(dialect, result.renderedDialect?.name)
            assertEquals(DialectDecisionEngine.Source.IDEA_PROVIDER, result.dialectDecision?.source)
            assertEquals(listOf(dialect), result.dialectDecision?.dataSourceIds)
            assertFalse("XML 证据快照不得持久化 JDBC URL", result.dialectDecision.toString().contains("jdbc:"))
        }
    }

    fun test分页模拟使用来源方言而非项目默认方言() {
        myFixture.addFileToProject("com/example/UserMapper.java", """
            package com.example;
            interface UserMapper { Object page(Page page); }
        """.trimIndent())
        val file = mapper("""<mapper namespace="com.example.UserMapper"><select id="page">SQL:SELECT 1 FROM t</select></mapper>""")
        IdeaSqlContextBridge.rawFactsForTest = { _, _, _ ->
            IdeaSqlContextBridge.RawFacts(
                provided = listOf(IdeaSqlContextBridge.RawCandidate("oracle", "oracle", null, "Oracle", true)),
                finalCandidates = listOf(IdeaSqlContextBridge.RawCandidate("oracle", "oracle", null, "Oracle", true)),
                effectiveDialectId = "Oracle",
            )
        }
        val context = XmlPreviewService.resolveSourceContext(file, statement(file, "page"))
        assertNotNull("分页用的来源注入必须真实存在", context.sqlPsiFile)
        val result = XmlPreviewService.preview(file.text, "page", project, sourceContext = context)
        assertEquals("Oracle", result.renderedDialect?.name)
        assertEquals("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", result.paginationLimitLine)
        assertTrue(result.sql.endsWith("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"))
    }

    fun test生产动作与gutter均传入瞬态上下文并显示() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "src/main/kotlin").isDirectory }
        val action = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt").readText()
        val gutter = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlSqlGutterLineMarkerProvider.kt").readText()
        for (entry in listOf(action, gutter)) {
            assertTrue(entry.contains("XmlPreviewService.resolveSourceContext(file, tag"))
            assertTrue(entry.contains("sourceContext = context"))
            assertTrue(entry.contains("sourceLocation, contextRef.get()") || entry.contains("sourceContext = contextRef.get()"))
        }
    }

    private fun mapper(text: String): XmlFile {
        val file = myFixture.configureByText("Mapper.xml", text) as XmlFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return file
    }

    private fun statement(file: XmlFile, id: String): XmlTag =
        PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java).first { it.getAttributeValue("id") == id }

    private class XmlSqlInjector : MultiHostInjector {
        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)

        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val text = context as? XmlText ?: return
            if (!text.text.contains("SQL:")) return
            val host = text as? PsiLanguageInjectionHost ?: return
            registrar.startInjecting(PlainTextLanguage.INSTANCE)
                .addPlace(null, null, host, TextRange(0, text.textLength))
                .doneInjecting()
        }
    }
}
