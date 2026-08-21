package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * HqlQueryLocator 全方位测试——覆盖 demo 项目所有 createQuery 调用模式。
 */
class HqlQueryLocatorComprehensiveTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 基本查询
    // ------------------------------------------------------------------

    fun testBasicNamedParamViaEntityManager() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByUsername(EntityManager em, String username) {
                    return em.createQuery(
                        "SELECT u FROM UserEntity u WHERE u.username = :username")
                        .setParameter("username", username)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (命名参数) 应被识别", info)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", info!!.hql)
        assertTrue("HQL 应为字面量", info.isLiteralHql)
    }

    fun testBasicNamedParamViaSession() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByUsername(Session session, String username) {
                    Query<UserEntity> q = session.createQuery(
                        "SELECT u FROM UserEntity u WHERE u.username = :username", UserEntity.class);
                    q.setParameter("username", username);
                    return q.getResultList();
                }
            }
            interface Session {
                <T> Query<T> createQuery(String hql, Class<T> resultClass);
            }
            interface Query<T> {
                Query<T> setParameter(String name, Object value);
                List<T> getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("session.createQuery (命名参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        assertTrue("参数列表不应为空", info.parameters.isNotEmpty())
        assertEquals("username", info.parameters[0].paramName)
    }

    // ------------------------------------------------------------------
    // JOIN FETCH
    // ------------------------------------------------------------------

    fun testJoinFetchViaEntityManager() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByDept(EntityManager em, String deptName) {
                    return em.createQuery(
                        "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName")
                        .setParameter("deptName", deptName)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("findByDept")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (JOIN FETCH) 应被识别", info)
        assertTrue("HQL 应含 JOIN FETCH", info!!.hql.contains("JOIN FETCH"))
    }

    fun testJoinFetchWithEntityClass() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByDept(Session session, String deptName, Integer status) {
                    return session.createQuery(
                        "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = ?1 AND u.status = ?2",
                        UserEntity.class)
                        .setParameter(1, deptName)
                        .setParameter(2, status)
                        .getResultList();
                }
            }
            interface Session {
                <T> Query<T> createQuery(String hql, Class<T> resultClass);
            }
            interface Query<T> {
                Query<T> setParameter(int position, Object value);
                List<T> getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("findByDept")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("session.createQuery (JOIN FETCH + 位置参数) 应被识别", info)
        assertEquals("UserEntity", info!!.entityClassName)
        // 链式调用场景：locator 不追踪参数（无变量赋值），但 previewHql() 会从 HQL 提取
        assertTrue("HQL 应含 JOIN FETCH", info.hql.contains("JOIN FETCH"))
    }

    // ------------------------------------------------------------------
    // 聚合查询
    // ------------------------------------------------------------------

    fun testAggregateQuery() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public Map<String, Object> aggregate(Session session, Integer status) {
                    Query<Object[]> q = session.createQuery(
                        "SELECT COUNT(u), MAX(u.age), AVG(u.age) FROM UserEntity u WHERE u.status = :status",
                        Object[].class);
                    q.setParameter("status", status);
                    Object[] row = q.uniqueResult();
                    return new java.util.HashMap<>();
                }
            }
            interface Session {
                <T> Query<T> createQuery(String hql, Class<T> resultClass);
            }
            interface Query<T> {
                Query<T> setParameter(String name, Object value);
                T uniqueResult();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("aggregate")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("聚合查询应被识别", info)
        assertTrue("HQL 应含 COUNT", info!!.hql.contains("COUNT"))
    }

    // ------------------------------------------------------------------
    // UPDATE
    // ------------------------------------------------------------------

    fun testUpdateQuery() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public int updateStatus(Session session, Integer oldStatus, Integer newStatus) {
                    Query<?> q = session.createQuery(
                        "UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus");
                    q.setParameter("newStatus", newStatus);
                    q.setParameter("oldStatus", oldStatus);
                    return q.executeUpdate();
                }
            }
            interface Session {
                <T> Query<T> createQuery(String hql, Class<T> resultClass);
                Query createQuery(String hql);
            }
            interface Query<T> {
                Query<T> setParameter(String name, Object value);
                int executeUpdate();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("updateStatus")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("UPDATE 查询应被识别", info)
        assertTrue("HQL 应含 UPDATE", info!!.hql.contains("UPDATE"))
    }

    // ------------------------------------------------------------------
    // IN 子句
    // ------------------------------------------------------------------

    fun testInClauseQuery() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByStatuses(EntityManager em, List<Integer> statuses) {
                    return em.createQuery(
                        "SELECT DISTINCT u FROM UserEntity u WHERE u.status IN :statuses " +
                        "AND u.age BETWEEN :minAge AND :maxAge " +
                        "AND u.username LIKE CONCAT('%', :keyword, '%') " +
                        "ORDER BY u.id")
                        .setParameter("statuses", statuses)
                        .setParameter("minAge", 18)
                        .setParameter("maxAge", 60)
                        .setParameter("keyword", "test")
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("findByStatuses")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("IN + BETWEEN + LIKE 组合查询应被识别", info)
        assertTrue("HQL 应含 IN", info!!.hql.contains("IN "))
        assertTrue("HQL 应含 BETWEEN", info.hql.contains("BETWEEN"))
        assertTrue("HQL 应含 LIKE", info.hql.contains("LIKE"))
    }

    // ------------------------------------------------------------------
    // NEW DTO
    // ------------------------------------------------------------------

    fun testNewDtoQuery() {
        myFixture.configureByText("OrderDao.java", """
            public class OrderDao {
                public List<Object[]> orderStat(EntityManager em) {
                    return em.createQuery(
                        "SELECT NEW cn.song.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
                        "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1")
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                List getResultList();
            }
            class OrderEntity {}
        """.trimIndent())
        val method = findMethod("orderStat")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("NEW DTO 查询应被识别", info)
        assertTrue("HQL 应含 NEW", info!!.hql.contains("NEW "))
    }

    // ------------------------------------------------------------------
    // EXISTS 子查询
    // ------------------------------------------------------------------

    fun testExistsSubquery() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findWithOrders(EntityManager em, java.math.BigDecimal minAmount) {
                    return em.createQuery(
                        "SELECT u FROM UserEntity u WHERE EXISTS " +
                        "(SELECT o FROM OrderEntity o WHERE o.customerId = u.id AND o.totalAmount >= :minAmount)")
                        .setParameter("minAmount", minAmount)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
            class OrderEntity {}
        """.trimIndent())
        val method = findMethod("findWithOrders")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("EXISTS 子查询应被识别", info)
        assertTrue("HQL 应含 EXISTS", info!!.hql.contains("EXISTS"))
    }

    // ------------------------------------------------------------------
    // 原生 SQL
    // ------------------------------------------------------------------

    fun testNativeQuery() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByKeyword(EntityManager em, String keyword, Integer status) {
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
        val method = findMethod("findByKeyword")
        val info = HqlQueryLocator.locate(method)
        // createNativeQuery 不在 CREATE_QUERY_METHODS 中，应返回 null
        assertNull("createNativeQuery 不应被 HqlQueryLocator 识别（由 JpqlGutterLineMarkerProvider 处理）", info)
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
