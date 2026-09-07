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

    // ------------------------------------------------------------------
    // T-DaoSql-01/02：自研 DAO select 族 + SQL 实参位置泛化
    // ------------------------------------------------------------------

    fun testLocate_selectList_classFirstArg_sqlSecondArg_hits() {
        // 用户反馈形态一：`beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId)`——
        // 首参为 Class 字面量（非字符串形态，跳过），SQL 在第 2 参（拼接局部变量）
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q(java.util.List list) {
                    String sql = "SELECT wp.PROC_NAME, count(1) as task_number " +
                        "FROM WKFL_TASK WT " +
                        "WHERE WT.OWNER = :CUR_USER ";
                    beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", 1L);
                }

                static class ScheduleVO { private String taskName; }
            }
        """.trimIndent())
        val call = findFirstCall("selectList")!!
        val anchor = anchorOfCall(call)!!
        val result = JdbcTemplateSqlLocator.locate(anchor)
        assertNotNull(result)
        assertEquals("selectList", result!!.methodName)
        assertTrue(result.sqlString.contains("SELECT wp.PROC_NAME"))
        assertTrue(result.sqlString.contains("FROM WKFL_TASK"))
        assertTrue(result.sqlString.contains(":CUR_USER"))
        assertTrue(result.isLiteral)
        // 参数实参 = SQL 实参之后的一个（"CUR_USER"，text 为源码文本含引号）
        assertEquals("\"CUR_USER\"", result.parameterExpression?.text)
    }

    fun testLocate_selectListPagination_hits() {
        // 用户反馈形态二包装层：`beanQuery.selectListPagination(classType, sql, paramMap, index, size)`
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q() {
                    String sql = "SELECT * FROM WKFL_TASK WT WHERE WT.FINISH_TYPE IS NULL";
                    beanQuery.selectListPagination(ScheduleVO.class, sql, paramMap, 1, 10);
                }

                private java.util.Map<String, Object> paramMap;

                static class ScheduleVO { private String taskName; }
            }
        """.trimIndent())
        val call = findFirstCall("selectListPagination")!!
        val result = JdbcTemplateSqlLocator.locateFromCall(call)
        assertNotNull(result)
        assertEquals("selectListPagination", result!!.methodName)
        assertTrue(result.sqlString.contains("SELECT * FROM WKFL_TASK"))
    }

    fun testLocate_selectList_wrapperArg_rejected() {
        // MP BaseMapper 同名形态反向守卫：实参为 Wrapper（非字符串形态）→ 不定位
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q(com.baomidou.mybatisplus.core.conditions.Wrapper w) {
                    userService.selectList(w);
                }
            }
        """.trimIndent())
        val call = findFirstCall("selectList")!!
        assertNull(JdbcTemplateSqlLocator.locateFromCall(call))
    }

    fun testLocate_firstArgStringVariable_regression_unchanged() {
        // 回归：Spring JDBC 形态首参即 SQL（String 变量），行为不变
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q(org.springframework.jdbc.core.JdbcTemplate jt) {
                    String sql = "SELECT * FROM t WHERE id = :id";
                    jt.queryForList(sql);
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = JdbcTemplateSqlLocator.locateFromCall(call)
        assertNotNull(result)
        assertEquals("SELECT * FROM t WHERE id = :id", result!!.sqlString)
    }
}
