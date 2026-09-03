package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [SqlStringConcatenator] PSI 集成测试（BasePlatformTestCase，真实 Java PSI 解析）。
 *
 * 覆盖：字面量 `+` 拼接、collectStringLiterals 收集、StringBuilder 聚合（含链式 append）。
 */
class SqlStringConcatenatorTest : BasePlatformTestCase() {

    /** 从当前文件内查找第一个匹配类型的表达式。 */
    private fun <T : PsiExpression> findFirstExpr(type: Class<T>): T? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, type).firstOrNull()
    }

    // ------------------------------------------------------------------
    // 路径 A — 字面量 `+` 拼接
    // ------------------------------------------------------------------

    fun testConcat_literalLiteral() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    String sql = "SELECT " + "FROM t";
                }
            }
        """.trimIndent())
        val first = findFirstExpr(PsiLiteralExpression::class.java)
        assertNotNull(first)
        // 直接字面量求值
        val result = SqlStringConcatenator.concat(first!!)
        assertNotNull(result)
        assertEquals("SELECT ", result!!.sql)
        assertTrue(result.complete)
    }

    fun testCollectStringLiterals_nestedConcatenation() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    String sql = "SELECT " + "a, " + "b FROM t";
                }
            }
        """.trimIndent())
        val file = myFixture.file as PsiJavaFile
        val codeBlock = PsiTreeUtil.findChildrenOfType(file, PsiCodeBlock::class.java).first()
        val literals = SqlStringConcatenator.collectStringLiterals(codeBlock)
        assertEquals(3, literals.size)
        assertEquals("SELECT ", literals[0])
        assertEquals("a, ", literals[1])
        assertEquals("b FROM t", literals[2])
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合（含链式 append）
    // ------------------------------------------------------------------

    fun testConcat_stringBuilderWithAppends() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT ");
                    sql.append("FROM t");
                    jdbcTemplate.queryForList(sql.toString(), null);
                }
            }
        """.trimIndent())
        // 找 StringBuilder 变量引用，通过公共 concat() 聚合
        val file = myFixture.file as? PsiJavaFile ?: error("not java file")
        val ref = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .first { it.referenceName == "sql" }
        val result = SqlStringConcatenator.concat(ref)
        assertNotNull(result)
        assertEquals("SELECT FROM t", result!!.sql)
        assertTrue(result.complete)
    }

    fun testConcat_chainedAppendWithRuntimeValues_marksIncomplete() {
        // 用户真实场景：链式 append 中混有运行时参数（方法参数）
        // sql.append("AND u.username LIKE '%").append(username).append("%' ");
        // 期望：字符串字面量保留，运行时值标记为 /*运行时值*/，complete = false
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(String username) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT * FROM user WHERE 1=1 ");
                    sql.append("AND u.username LIKE '%").append(username).append("%' ");
                    sql.append("ORDER BY u.id DESC");
                }
            }
        """.trimIndent())
        // 找 StringBuilder 变量引用，通过公共 concat() 聚合
        val file = myFixture.file as? PsiJavaFile ?: error("not java file")
        val ref = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .first { it.referenceName == "sql" }
        val result = SqlStringConcatenator.concat(ref)
        assertNotNull(result)
        // 应包含静态字符串片段
        assertTrue("应包含 SELECT 片段", result!!.sql.contains("SELECT * FROM user WHERE 1=1 "))
        assertTrue("应包含 LIKE 片段", result.sql.contains("AND u.username LIKE '%"))
        assertTrue("应包含 ORDER BY 片段", result.sql.contains("ORDER BY u.id DESC"))
        // 运行时值应被标记为占位
        assertTrue("应标记运行时值 username", result.sql.contains("username"))
        // 含运行时值 → complete = false
        assertFalse("含运行时值应标记 incomplete", result.complete)
    }

    // ------------------------------------------------------------------
    // collectStringLiterals 工具
    // ------------------------------------------------------------------

    fun testCollectStringLiterals_singleLiteral() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                String s = "hello";
            }
        """.trimIndent())
        val literal = findFirstExpr(PsiLiteralExpression::class.java)!!
        val result = SqlStringConcatenator.collectStringLiterals(literal)
        assertEquals(1, result.size)
        assertEquals("hello", result[0])
    }

    fun testCollectStringLiterals_intLiteralReturnsEmpty() {
        // int 字面量（非 String）→ collectStringLiterals 只收集字符串字面量，返回空
        myFixture.configureByText("Demo.java", """
            public class Demo {
                int x = 42;
            }
        """.trimIndent())
        val literal = findFirstExpr(PsiLiteralExpression::class.java)
        assertNotNull(literal)
        // 42 是 int 字面量（非 String）→ collectStringLiterals 返回空
        val result = SqlStringConcatenator.collectStringLiterals(literal!!)
        assertTrue("非字符串字面量应返回空", result.isEmpty())
    }

    fun testConcat_nullInputReturnsNull() {
        assertNull(SqlStringConcatenator.concat(null))
    }
}
