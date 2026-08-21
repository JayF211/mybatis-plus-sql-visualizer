package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * HqlQueryLocator NEW DTO 测试。
 */
class HqlQueryLocatorNewDtoTest : BasePlatformTestCase() {

    fun testNewDtoQuery_located() {
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
            class OrderEntity {}
        """.trimIndent())
        val method = findMethod("orderStat")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("NEW DTO 查询应被识别", info)
        assertTrue("HQL 应含 NEW", info!!.hql.contains("NEW "))
        assertTrue("HQL 应含 OrderStatDTO", info.hql.contains("OrderStatDTO"))
    }

    fun testNativeQuery_located() {
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
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("nativeQuery")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("createNativeQuery 应被识别", info)
        assertTrue("应为原生查询", info!!.isNativeQuery)
        assertEquals("SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status", info.hql)
    }

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
