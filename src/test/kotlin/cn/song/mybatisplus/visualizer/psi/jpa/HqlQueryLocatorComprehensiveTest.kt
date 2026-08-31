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
        // 使用单行 HQL 避免多行拼接解析问题
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByStatuses(EntityManager em, List<Integer> statuses) {
                    Query q = em.createQuery("SELECT DISTINCT u FROM UserEntity u WHERE u.status IN :statuses AND u.age BETWEEN :minAge AND :maxAge AND u.username LIKE CONCAT('%', :keyword, '%') ORDER BY u.id");
                    q.setParameter("statuses", statuses);
                    q.setParameter("minAge", 18);
                    q.setParameter("maxAge", 60);
                    q.setParameter("keyword", "test");
                    return q.getResultList();
                }
            }
            interface EntityManager { Query createQuery(String qlString); }
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
        // createNativeQuery 现在被 HqlQueryLocator 识别（原生 SQL 路径）
        assertNotNull("createNativeQuery 应被识别", info)
        assertTrue("应为原生查询", info!!.isNativeQuery)
        assertEquals("SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status", info.hql)
    }

    // ------------------------------------------------------------------
    // 多行字符串拼接（含 JOIN FETCH）
    // ------------------------------------------------------------------

    fun testMultiLineConcatWithJoinFetch() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> jpqlJoinFetch(EntityManager em, String deptName) {
                    return em.createQuery(
                            "SELECT u FROM UserEntity u " +
                                    "JOIN FETCH u.department d " +
                                    "WHERE d.name = :deptName")
                            .setParameter("deptName", deptName)
                            .getResultList();
                }
            }
            interface EntityManager { Query createQuery(String qlString); }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("jpqlJoinFetch")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("多行拼接 em.createQuery (含 JOIN FETCH) 应被识别", info)
        val expected = "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName"
        assertEquals("多行拼接应正确合并", expected, info!!.hql)
        assertTrue("HQL 应为字面量", info.isLiteralHql)
        // 链式 setParameter 在 HqlQueryLocator 不被追踪（仅变量赋值场景），参数提取由
        // SqlPreviewService.previewHql 的 info.parameters.ifEmpty { extractParamNamesFromHql(...) } 兜底。
    }

    /** M36.5.79 用户场景精确复现：变量赋值 + 链式 setParameter + 多行 `+` 拼接（无图标根因回归）。 */
    fun testUserScenarioVariableAssignmentChainedConcat() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> jpqlJoinFetch(String deptName) {
                    javax.persistence.Query query = entityManager.createQuery(
                            "SELECT u FROM UserEntity u " +
                                    "JOIN FETCH u.department d " +
                                    "WHERE d.name = :deptName")
                            .setParameter("deptName", deptName);
                    return query.getResultList();
                }
            }
            interface EntityManager { Query createQuery(String qlString); }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
        """.trimIndent())
        val method = findMethod("jpqlJoinFetch")
        // gutter 锚点路径：anchor 为 createQuery 的 PsiMethodCallExpression（路径 1）
        val call = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            method.body, com.intellij.psi.PsiMethodCallExpression::class.java,
        ).firstOrNull { it.methodExpression.referenceName == "createQuery" }
        assertNotNull("应找到 createQuery 调用", call)
        val info = HqlQueryLocator.locate(call!!)
        assertNotNull("变量赋值 + 链式 + 多行拼接应被识别（gutter 图标判定非 null）", info)
        assertEquals(
            "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
            info!!.hql,
        )
        // 变量赋值场景：queryVars 追踪应捕获链上 setParameter
        System.err.println("[DEBUG] parameters=" + info.parameters.map { "${it.paramName}:${it.valueExpr}:${it.isPositional}" })
        assertTrue("变量赋值场景参数应被提取", info.parameters.any { it.paramName == "deptName" })
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
