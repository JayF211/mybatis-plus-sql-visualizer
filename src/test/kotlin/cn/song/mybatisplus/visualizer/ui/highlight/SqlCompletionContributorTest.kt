package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.SqlClause
import cn.song.mybatisplus.visualizer.sql.detectClause
import cn.song.mybatisplus.visualizer.sql.detectClauseFromText
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SQL 补全基础设施测试（M9-03/04）：
 *  - 预览语言 PSI 文件可创建（ParserDefinition 生效）
 *  - CompletionContributor 已注册到语言
 *  - SqlCompletionProvider 提供关键字
 *  - M16.2：detectClause 边界防御（offset clamp/空文本）与上下文双载体读取（Editor userData 优先）
 *  - T-M27-01：detectClause 迁入 sql.SqlTextAnalysis 纯函数模块——下述 detectClause 用例改走公共 API
 *    （断言语义不变）
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
        // SELECT 列位、无法判定全量（T-M27-01：改走 SqlTextAnalysis 公共 API，语义不变）
        assertEquals(SqlClause.TABLE, detectClauseFromText("SELECT id FROM "))
        assertEquals(SqlClause.TABLE, detectClauseFromText("SELECT t.id FROM device t LEFT JOIN "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device WHERE "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device WHERE id = 1 AND "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device ORDER BY "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device GROUP BY "))
        assertEquals(SqlClause.COLUMN, detectClauseFromText("SELECT id FROM device t ON "))
        assertEquals(SqlClause.SELECT_COLUMN, detectClauseFromText("SELECT "))
        assertEquals(SqlClause.SELECT_COLUMN, detectClauseFromText("SELECT id, "))
        assertEquals(SqlClause.UNKNOWN, detectClauseFromText(""))
    }

    fun testDetectClauseOffsetClamped() {
        // T-M16.2-01：offset 越界 clamp 不抛异常（旧 PSI 未随编辑更新时 offset 可能超过新文档长度——
        // substring 越界异常被 IDE 吞掉 → 补全静默失效的回归点 C）（T-M27-01：改走 SqlTextAnalysis 公共 API）
        // offset 超过文本长度 → clamp 到末尾，按全量文本判定（不抛异常）
        assertEquals(SqlClause.TABLE, detectClause("SELECT id FROM t", 999))
        // offset 为负 → clamp 到 0 → 空前缀 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, detectClause("SELECT id FROM t", -1))
        // 空文本 → UNKNOWN
        assertEquals(SqlClause.UNKNOWN, detectClause("", 0))
        // 正常位置不受影响
        assertEquals(
            SqlClause.COLUMN,
            detectClause("SELECT id FROM t WHERE id = 1 ", "SELECT id FROM t WHERE id = 1 ".length),
        )
    }

    fun testContextEditorCarrierPreferredOverPsi() {
        // T-M16.2-01：上下文双载体读取——Editor userData 优先（嵌入式编辑器编辑后 PSI 重建丢失 userData，
        // 回归点 B）、PSI 文件 userData 兜底
        val contributor = SqlCompletionContributor()
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("preview.mpsql", SqlPreviewLanguage, "SELECT 1")
        val position = PsiTreeUtil.getDeepestFirst(file)

        // ① Editor 无上下文 → 回退 PSI userData
        val psiCtx = SqlCompletionContext("psi_tbl", listOf("id"), null)
        file.putUserData(SqlCompletionContext.KEY, psiCtx)
        val fallback = contributor.resolveContext(null, position)
        assertNotNull("Editor 无上下文时应回退 PSI userData", fallback)
        assertEquals("psi_tbl", fallback!!.tableName)

        // ② Editor 有上下文 → Editor 优先（与 PSI 不一致时取 Editor）
        val editorCtx = SqlCompletionContext("editor_tbl", listOf("name"), null)
        val preferred = contributor.resolveContext(editorCtx, position)
        assertNotNull("Editor 有上下文时应优先使用", preferred)
        assertEquals("editor_tbl", preferred!!.tableName)

        // ③ 双载体均无 → null（关键字补全不受影响，实体补全跳过）
        file.putUserData(SqlCompletionContext.KEY, null)
        val noCtx = contributor.resolveContext(null, PsiTreeUtil.getDeepestFirst(file))
        assertNull("无上下文应返回 null", noCtx)
    }

    fun testWithCommentCarriesTailTextAndLookupString() {
        // T-M16.3-02（用户实测「字段可补全但看不到注释」根因）：LookupElementBuilder 不可变——
        // withTailText/withLookupString 返回新实例，原实现丢弃返回值导致注释 tailText 从不展示。
        // 渲染验证：注释作为灰色尾缀展示 + 进入 lookup strings 支持注释关键词检索
        val contributor = SqlCompletionContributor()

        // 有注释：tailText 渲染可见 + 注释可检索
        val decorated = contributor.withComment(LookupElementBuilder.create("station_id"), "场站ID")
        val presentation = LookupElementPresentation()
        decorated.renderElement(presentation)
        assertEquals("  场站ID", presentation.tailText)
        assertTrue("注释应进入 lookup strings 供关键词检索", decorated.allLookupStrings.contains("场站ID"))

        // 无注释：原样（tailText 为 null，不加检索串）
        val plain = contributor.withComment(LookupElementBuilder.create("id"), null)
        val plainPresentation = LookupElementPresentation()
        plain.renderElement(plainPresentation)
        assertNull(plainPresentation.tailText)
    }
}
