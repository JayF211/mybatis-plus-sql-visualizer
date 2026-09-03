package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [JdbcTemplateSqlLocator] PSI 集成测试（BasePlatformTestCase）。
 *
 * 覆盖：方法名命中/排除、实参结构解析、字符串表达式定位。
 */
class JdbcTemplateSqlLocatorTest : BasePlatformTestCase() {

    private fun findFirstCall(methodName: String): PsiMethodCallExpression? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == methodName }
    }

    private fun anchorOfCall(call: PsiMethodCallExpression): PsiElement? {
        // 锚定在方法名 identifier 上（与 gutter 一致）
        return call.methodExpression.referenceNameElement
    }

    // ------------------------------------------------------------------
    // 方法名命中
    // ------------------------------------------------------------------

    fun testLocate_queryForListWithStringArg_hits() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void query(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE id = :id");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val anchor = anchorOfCall(call)!!
        val result = JdbcTemplateSqlLocator.locate(anchor)
        assertNotNull(result)
        assertEquals("queryForList", result!!.methodName)
        assertEquals("SELECT * FROM t WHERE id = :id", result.sqlString)
        assertTrue(result.isLiteral)
    }

    fun testLocate_updateWithStringArg_hits() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.update("UPDATE t SET name = :name WHERE id = :id");
                }
            }
        """.trimIndent())
        val call = findFirstCall("update")!!
        val result = JdbcTemplateSqlLocator.locateFromCall(call)
        assertNotNull(result)
        assertEquals("update", result!!.methodName)
        assertTrue(result.sqlString.contains("UPDATE t"))
    }

    fun testLocate_createQuery_isExcluded() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q() {
                    session.createQuery("from User");
                }
            }
        """.trimIndent())
        val call = findFirstCall("createQuery")!!
        val anchor = anchorOfCall(call)!!
        val result = JdbcTemplateSqlLocator.locate(anchor)
        assertNull(result)  // createQuery 不是 JDBC 模板方法
    }

    fun testLocate_findByQuery_isExcluded() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q() {
                    commonDao.findByQuery("from User");
                }
            }
        """.trimIndent())
        val call = findFirstCall("findByQuery")!!
        val anchor = anchorOfCall(call)!!
        val result = JdbcTemplateSqlLocator.locate(anchor)
        assertNull(result)  // findByQuery 是自定义 DAO，不是 JDBC 模板
    }
}
