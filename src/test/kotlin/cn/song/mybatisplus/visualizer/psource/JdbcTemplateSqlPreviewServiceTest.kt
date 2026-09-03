package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [JdbcTemplateSqlPreviewService] PSI 集成测试（BasePlatformTestCase）。
 *
 * 覆盖：PreviewResult 组装（命名参数收集、引号提示、namedParamHints、末尾分号）。
 */
class JdbcTemplateSqlPreviewServiceTest : BasePlatformTestCase() {

    private fun findFirstCall(methodName: String): PsiMethodCallExpression? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == methodName }
    }

    private fun previewOfCall(call: PsiMethodCallExpression): PreviewResult? {
        val locatorCall = JdbcTemplateSqlLocator.locateFromCall(call) ?: return null
        return JdbcTemplateSqlPreviewService.preview(locatorCall, call)
    }

    // ------------------------------------------------------------------
    // PreviewResult 组装
    // ------------------------------------------------------------------

    fun testPreview_singleNamedParam_producesNamedParamHints() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM users WHERE id = :id");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 命名参数识别
        assertTrue(result!!.namedParamHints.isNotEmpty())
        assertTrue(result.namedParamHints.containsKey("id"))
        // unresolvedParams 包含命名参数名
        assertTrue(result.unresolvedParams.contains("id"))
        // wrapperType = null（非 Wrapper 预览）
        assertNull(result.wrapperType)
    }

    fun testPreview_multipleNamedParams() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = :CUR_USER AND b = :flowKey");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        assertEquals(2, result!!.namedParamHints.size)
        assertTrue(result.namedParamHints.containsKey("CUR_USER"))
        assertTrue(result.namedParamHints.containsKey("flowKey"))
    }

    fun testPreview_duplicateParam_deduplicated() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = :CUR_USER OR b = :CUR_USER");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 同名参数去重 → 1 条 namedParamHint
        assertEquals(1, result!!.namedParamHints.size)
        assertEquals(1, result.unresolvedParams.size)
    }

    fun testPreview_questionMarkPlaceholders() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = ? AND b = ?", new Object[]{1, 2});
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 无命名参数 → namedParamHints 空
        assertTrue(result!!.namedParamHints.isEmpty())
        // `?` 占位符按序（param1, param2）
        assertTrue(result.unresolvedParams.contains("param1"))
        assertTrue(result.unresolvedParams.contains("param2"))
    }

    fun testPreview_sqlTerminatesWithSemicolon() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        assertTrue(result!!.sql.trimEnd().endsWith(";"))
    }

    fun testPreview_nonJdbcCall_returnsNull() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q() {
                    session.createQuery("from User");
                }
            }
        """.trimIndent())
        val call = findFirstCall("createQuery")!!
        val result = previewOfCall(call)
        assertNull(result)
    }
}
