package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * SQL 补全基础设施测试（M9-03/04）：
 *  - 预览语言 PSI 文件可创建（ParserDefinition 生效）
 *  - CompletionContributor 已注册到语言
 *  - SqlCompletionProvider 提供关键字
 */
class SqlCompletionContributorTest : BasePlatformTestCase() {

    fun testPsiFileCreatedForPreviewLanguage() {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("preview.mpsql", SqlPreviewLanguage, "SELECT 1 FROM t")
        assertNotNull("预览语言应能生成 PSI 文件", file)
        assertEquals(SqlPreviewLanguage, file.language)
    }

    fun testCompletionContributorRegistered() {
        val contributors = CompletionContributor.forLanguage(SqlPreviewLanguage)
        assertTrue("应注册 SqlCompletionContributor", contributors.any { it is SqlCompletionContributor })
    }

    fun testProviderKeywords() {
        val keywords = SqlCompletionProvider.instance.keywords()
        assertTrue("关键字应含 SELECT", keywords.contains("SELECT"))
        assertTrue("关键字应含 FROM", keywords.contains("FROM"))
        assertTrue("关键字应含 WHERE", keywords.contains("WHERE"))
    }

    fun testDefaultProviderUsesContext() {
        val ctx = SqlCompletionContext("merchant_entity", listOf("id", "phone"), null)
        assertEquals("merchant_entity", SqlCompletionProvider.instance.tableName(ctx))
        assertEquals(listOf("id", "phone"), SqlCompletionProvider.instance.columns(ctx))
    }
}
