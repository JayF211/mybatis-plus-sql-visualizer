package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * orderStat 和 nativeQuery 端到端测试。
 */
class PreviewOrderStatTest : BasePlatformTestCase() {

    private lateinit var service: SqlPreviewService

    override fun setUp() {
        super.setUp()
        service = SqlPreviewService(myFixture.project)
    }

    fun testOrderStat_preview() {
        myFixture.configureByText("OrderDao.java", """
            public class OrderDao {
                public List<Object[]> orderStat(EntityManager em) {
                    return em.createQuery(
                        "SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
                        "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1")
                        .getResultList();
                }
            }
            interface EntityManager { Query createQuery(String qlString); }
            interface Query { List getResultList(); }
            @Entity
            @Table(name = "t_order")
            class OrderEntity {}
        """.trimIndent())
        val call = findMethodCall("createQuery")
        val result = service.previewHql(call)
        assertNotNull("orderStat 应被识别", result)
        // NEW DTO 应被剥离
        assertFalse("不应含 NEW", result!!.sql.contains("NEW "))
        assertFalse("不应含 OrderStatDTO", result.sql.contains("OrderStatDTO"))
        // 应为干净的 SQL，不含 Java 字符串拼接符号
        assertFalse("不应含 +", result.sql.contains("+"))
        // 应含正确的结构
        assertTrue("应含 FROM", result.sql.contains("FROM"))
        assertTrue("应含 GROUP BY", result.sql.contains("GROUP BY"))
        assertTrue("应含 HAVING", result.sql.contains("HAVING"))
    }

    fun testNativeQuery_preview() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> nativeQuery(EntityManager em, String keyword, Integer status) {
                    return em.createNativeQuery(
                        "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status",
                        UserEntity.class)
                        .setParameter("keyword", keyword)
                        .setParameter("status", status)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createNativeQuery(String sql, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            @Entity
            @Table(name = "user")
            class UserEntity {}
        """.trimIndent())
        val call = findMethodCall("createNativeQuery")
        val result = service.previewHql(call)
        assertNotNull("nativeQuery 应被识别", result)
        println("NATIVE SQL: ${result!!.sql}")
        println("NATIVE PARAMS: ${result.unresolvedParams}")
        println("NATIVE TABLE: ${result.tableName}")
        // 原生 SQL 应保留原始表名 user（不做实体→表名映射）
        assertTrue("应含 FROM user", result.sql.contains("FROM user"))
        assertFalse("不应含 unknown_table", result.sql.contains("unknown_table"))
        // 参数应为 ? 占位符
        assertTrue("应含 ?", result.sql.contains("?"))
    }

    private fun findMethodCall(methodName: String): PsiMethodCallExpression {
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        return calls.firstOrNull { it.methodExpression.referenceName == methodName }
            ?: error("测试文件缺少方法调用: $methodName")
    }
}
