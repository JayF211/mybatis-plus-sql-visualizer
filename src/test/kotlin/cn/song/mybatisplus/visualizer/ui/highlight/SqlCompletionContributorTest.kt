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

    fun testColumnsWithCommentsMergesContext() {
        // T-M16-03：columnsWithComments 合并 columns 与 columnComments（无注释列返回 null）
        val ctx = SqlCompletionContext(
            "merchant_entity",
            listOf("id", "name"),
            null,
            columnComments = mapOf("name" to "设备名称"),
        )
        val merged = SqlCompletionProvider.instance.columnsWithComments(ctx)
        assertEquals(listOf("id" to null, "name" to "设备名称"), merged)
        // 无注释上下文向后兼容
        val plain = SqlCompletionContext("merchant_entity", listOf("id"), null)
        assertEquals(listOf("id" to null), SqlCompletionProvider.instance.columnsWithComments(plain))
    }

    fun testTableCommentFromContext() {
        // T-M16-05：表注释经 context 传递
        val ctx = SqlCompletionContext("merchant_entity", emptyList(), null, tableComment = "商户表")
        assertEquals("商户表", SqlCompletionProvider.instance.tableComment(ctx))
    }

    fun testDetectClausePositionAware() {
        // T-M16-04：位置感知——FROM/JOIN 后表名位、WHERE/ON/HAVING/GROUP BY/ORDER BY 后列名位、
        // SELECT 列位、无法判定全量
        val contributor = SqlCompletionContributor()
        assertEquals(SqlCompletionContributor.SqlClause.TABLE, contributor.detectClauseFromText("SELECT id FROM "))
        assertEquals(SqlCompletionContributor.SqlClause.TABLE, contributor.detectClauseFromText("SELECT t.id FROM device t LEFT JOIN "))
        assertEquals(SqlCompletionContributor.SqlClause.COLUMN, contributor.detectClauseFromText("SELECT id FROM device WHERE "))
        assertEquals(SqlCompletionContributor.SqlClause.COLUMN, contributor.detectClauseFromText("SELECT id FROM device WHERE id = 1 AND "))
        assertEquals(SqlCompletionContributor.SqlClause.COLUMN, contributor.detectClauseFromText("SELECT id FROM device ORDER BY "))
        assertEquals(SqlCompletionContributor.SqlClause.COLUMN, contributor.detectClauseFromText("SELECT id FROM device GROUP BY "))
        assertEquals(SqlCompletionContributor.SqlClause.COLUMN, contributor.detectClauseFromText("SELECT id FROM device t ON "))
        assertEquals(SqlCompletionContributor.SqlClause.SELECT_COLUMN, contributor.detectClauseFromText("SELECT "))
        assertEquals(SqlCompletionContributor.SqlClause.SELECT_COLUMN, contributor.detectClauseFromText("SELECT id, "))
        assertEquals(SqlCompletionContributor.SqlClause.UNKNOWN, contributor.detectClauseFromText(""))
    }
}
