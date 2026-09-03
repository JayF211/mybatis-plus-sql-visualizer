package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [SqlStringConcatenator] PSI 集成测试（BasePlatformTestCase，真实 Java PSI 解析）。
 *
 * 覆盖：字面量 `+` 拼接、collectStringLiterals 收集、StringBuilder 聚合。
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
        val file = myFixture.file as? PsiJavaFile
        val codeBlock = PsiTreeUtil.findChildrenOfType(file, PsiCodeBlock::class.java).first()
        val literals = SqlStringConcatenator.collectStringLiterals(codeBlock)
        assertEquals(3, literals.size)
        assertEquals("SELECT ", literals[0])
        assertEquals("a, ", literals[1])
        assertEquals("b FROM t", literals[2])
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合
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
        // 找第一个 append 调用
        val appendCall = PsiTreeUtil.findChildrenOfType(
            myFixture.file as? PsiJavaFile, PsiMethodCallExpression::class.java,
        ).first { it.methodExpression.referenceName == "append" }
        val result = SqlStringConcatenator.concat(appendCall.argumentList.expressions[0])
        assertNotNull(result)
        assertEquals("SELECT ", result!!.sql)
        assertTrue(result.complete)
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
