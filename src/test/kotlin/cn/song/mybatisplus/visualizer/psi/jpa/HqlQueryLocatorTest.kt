package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * HqlQueryLocator 单元测试（Wave2-P1）。
 */
class HqlQueryLocatorTest : BasePlatformTestCase() {

    fun testCreateQuery_literalHql() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List<UserEntity> findByUsername(Session session, String username) {
                    Query q = session.createQuery(
                        "from UserEntity u where u.username = :username",
                        UserEntity.class
                    );
                    q.setParameter("username", username);
                    return q.list();
                }
            }
            interface Session {
                Query createQuery(String hql, Class resultClass);
                Query createQuery(String hql);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List list();
            }
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("createQuery 应被识别", info)
        assertEquals("findByUsername", info!!.methodName)
        assertEquals("from UserEntity u where u.username = :username", info.hql)
        assertTrue("HQL 应为字面量", info.isLiteralHql)
        assertEquals("UserEntity", info.entityClassName)
        assertFalse("参数列表不应为空", info.parameters.isEmpty())
        assertEquals("username", info.parameters[0].paramName)
    }

    fun testCreateQuery_withoutEntityClass() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findAll(Session session) {
                    return session.createQuery("from UserEntity").list();
                }
            }
            interface Session {
                Query createQuery(String hql);
            }
            interface Query { List list(); }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findAll")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("createQuery(无实体类) 应被识别", info)
        assertEquals("from UserEntity", info!!.hql)
        // 无 Entity.class 二参时，fallback 从 FROM 子句提取实体类名
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testCreateQuery_sessionFactoryGetCurrentSession() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findByStatus(SessionFactory sf, int status) {
                    return sf.getCurrentSession()
                        .createQuery("from UserEntity u where u.status = :status")
                        .setParameter("status", status)
                        .list();
                }
            }
            interface SessionFactory {
                Session getCurrentSession();
            }
            interface Session {
                Query createQuery(String hql);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List list();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByStatus")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("sessionFactory.getCurrentSession().createQuery 应被识别", info)
        assertEquals("from UserEntity u where u.status = :status", info!!.hql)
    }

    fun testCreateQuery_withPositionalParameter() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findByUsername(Session session, String username) {
                    Query q = session.createQuery("from UserEntity u where u.username = ?1", UserEntity.class);
                    q.setString(0, username);
                    return q.list();
                }
            }
            interface Session {
                Query createQuery(String hql, Class resultClass);
            }
            interface Query {
                Query setString(int index, String value);
                List list();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull(info)
        assertTrue("应检测到位置参数", info!!.parameters.any { it.isPositional })
        val positionalParam = info.parameters.first { it.isPositional }
        assertEquals("0", positionalParam.paramName)
    }

    fun testCreateQuery_withMultipleParameters() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findByStatusAndAge(Session session, int status, int age) {
                    Query q = session.createQuery(
                        "from UserEntity u where u.status = :status and u.age > :age",
                        UserEntity.class
                    );
                    q.setInteger("status", status);
                    q.setInteger("age", age);
                    return q.list();
                }
            }
            interface Session {
                Query createQuery(String hql, Class resultClass);
            }
            interface Query {
                Query setInteger(String name, int value);
                List list();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByStatusAndAge")
        val info = HqlQueryLocator.locate(method)
        assertNotNull(info)
        assertEquals("应识别 2 个参数", 2, info!!.parameters.size)
    }

    fun testCreateQuery_nonHqlMethod_excluded() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public void updateUsername(Session session, Long id, String username) {
                    session.save(new UserEntity());
                }
            }
            interface Session {
                void save(Object entity);
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("updateUsername")
        val info = HqlQueryLocator.locate(method)
        assertNull("非 createQuery 调用应返回 null", info)
    }

    fun testCreateQuery_createNamedQuery_recognized() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findByNamed(Session session) {
                    return session.createNamedQuery("User.findByStatus")
                        .setParameter("status", 1)
                        .list();
                }
            }
            interface Session {
                Query createNamedQuery(String name);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List list();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByNamed")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("createNamedQuery 应被识别", info)
        assertEquals("User.findByStatus", info!!.hql)
    }

    fun testCreateQuery_sessionFactoryGetCurrentSessionChain() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List<UserEntity> findByUsername(SessionFactory sf, String username) {
                    Query q = sf.getCurrentSession().createQuery(
                        "from UserEntity u where u.username = :username",
                        UserEntity.class
                    );
                    q.setParameter("username", username);
                    return q.list();
                }
            }
            interface SessionFactory {
                Session getCurrentSession();
            }
            interface Session {
                Query createQuery(String hql, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List list();
            }
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("sessionFactory.getCurrentSession().createQuery 应被识别", info)
        assertEquals("from UserEntity u where u.username = :username", info!!.hql)
        assertEquals("UserEntity", info.entityClassName)
    }

    // ------------------------------------------------------------------
    // EntityManager.createQuery（JPA 标准 API）
    // ------------------------------------------------------------------

    fun testEntityManager_createQuery_namedParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByUsername(EntityManager em, String username) {
                    Query q = em.createQuery(
                        "SELECT u FROM UserEntity u WHERE u.username = :username",
                        UserEntity.class
                    );
                    q.setParameter("username", username);
                    return q.getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (JPQL) 应被识别", info)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", info!!.hql)
        assertEquals("UserEntity", info.entityClassName)
        assertTrue("参数列表不应为空", info.parameters.isNotEmpty())
        assertEquals("username", info.parameters[0].paramName)
    }

    fun testEntityManager_createQuery_positionalParam() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByUsername(EntityManager em, String username, Integer minAge) {
                    Query q = em.createQuery(
                        "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age >= ?2",
                        UserEntity.class
                    );
                    q.setParameter(1, username);
                    q.setParameter(2, minAge);
                    return q.getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                Query setParameter(int position, Object value);
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (位置参数 JPQL) 应被识别", info)
        assertTrue("HQL 应含 SELECT", info!!.hql.contains("SELECT"))
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testEntityManager_createQuery_joinFetch() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByDept(EntityManager em, String deptName) {
                    return em.createQuery(
                        "SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName",
                        UserEntity.class
                    ).setParameter("deptName", deptName).getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByDept")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (JOIN FETCH) 应被识别", info)
        assertTrue("HQL 应含 JOIN FETCH", info!!.hql.contains("JOIN FETCH"))
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testEntityManager_createQuery_newDto() {
        myFixture.configureByText(
            "OrderService.java",
            """
            public class OrderService {
                public List<Object[]> orderStat(EntityManager em) {
                    return em.createQuery(
                        "SELECT NEW cn.song.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
                        "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1"
                    ).getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                List getResultList();
            }
            class OrderEntity {}
            """.trimIndent(),
        )
        val method = findMethod("orderStat")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createQuery (NEW DTO) 应被识别", info)
        assertTrue("HQL 应含 NEW", info!!.hql.contains("NEW "))
        assertEquals("OrderEntity", info.entityClassName)
    }

    fun testEntityManager_createNamedQuery() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByNamed(EntityManager em) {
                    Query q = em.createNamedQuery("UserEntity.findByStatus");
                    q.setParameter("status", 1);
                    return q.getResultList();
                }
            }
            interface EntityManager {
                Query createNamedQuery(String name);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByNamed")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("em.createNamedQuery 应被识别", info)
        assertEquals("UserEntity.findByStatus", info!!.hql)
    }

    // ------------------------------------------------------------------
    // 变量引用场景（修复：提取为变量的 HQL 应展开为实际字符串）
    // ------------------------------------------------------------------

    fun testEntityManager_createQuery_variableLiteral() {
        // String hql = "..."; em.createQuery(hql) — 直接字面量变量
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByUsername(EntityManager em) {
                    String hql = "SELECT u FROM UserEntity u WHERE u.username = :username";
                    return em.createQuery(hql, UserEntity.class)
                        .setParameter("username", "test")
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("变量字面量 HQL 应被识别", info)
        assertEquals("应展开变量值为实际 HQL", "SELECT u FROM UserEntity u WHERE u.username = :username", info!!.hql)
        assertTrue("应标记为字面量", info.isLiteralHql)
    }

    fun testEntityManager_createQuery_variableConcat() {
        // String hql = "select" + " ..."; em.createQuery(hql) — 拼接变量
        myFixture.configureByText(
            "BookingService.java",
            """
            public class BookingService {
                public List<Object[]> test(EntityManager em) {
                    String hql = "select"
                        + " count(booking.id),"
                        + " count(case when booking.arrivalTime is not null"
                        + "   and booking.initialETA is not null"
                        + "   and booking.arrivalTime < booking.initialETA then 0 end),"
                        + " count(case when booking.status = 'F' then 0 end)"
                        + " from WmsDockBooking booking"
                        + " where booking.warehouseId = :warehouseId"
                        + " and booking.type = 'R'"
                        + " and booking.updateInfo.createdTime >= :startTime"
                        + " and booking.updateInfo.createdTime < :endTime";
                    return em.createQuery(hql).getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                List getResultList();
            }
            class WmsDockBooking {}
            """.trimIndent(),
        )
        val method = findMethod("test")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("拼接变量 HQL 应被识别", info)
        // 拼接结果应包含关键片段
        assertTrue("应展开拼接结果，非变量名", !info!!.hql.equals("hql", ignoreCase = true))
        assertTrue("应含 select 片段", info.hql.contains("select"))
        assertTrue("应含 from 片段", info.hql.contains("from WmsDockBooking"))
        assertTrue("应含 where 片段", info.hql.contains("where"))
        assertTrue("应标记为字面量", info.isLiteralHql)
    }

    fun testEntityManager_createQuery_finalConstantField() {
        // static final String HQL = "..."; em.createQuery(HQL) — final 常量字段
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                static final String HQL = "SELECT u FROM UserEntity u WHERE u.status = :status";
                public List<UserEntity> findByStatus(EntityManager em) {
                    return em.createQuery(HQL, UserEntity.class)
                        .setParameter("status", 1)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByStatus")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("final 常量字段 HQL 应被识别", info)
        assertEquals("应展开常量值为实际 HQL", "SELECT u FROM UserEntity u WHERE u.status = :status", info!!.hql)
        assertTrue("应标记为字面量", info.isLiteralHql)
    }

    fun testEntityManager_createQuery_methodParameter_gracefulDegradation() {
        // void foo(String hql) { em.createQuery(hql); } — 方法参数无法静态解析
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByHql(EntityManager em, String hql) {
                    return em.createQuery(hql, UserEntity.class).getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query {
                List getResultList();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByHql")
        val info = HqlQueryLocator.locate(method)
        // 方法参数无 initializer，应优雅降级：返回变量名 + isLiteralHql=false
        assertNotNull("方法参数场景不应崩溃", info)
        assertEquals("无法解析时应返回变量名", "hql", info!!.hql)
        assertFalse("非字面量应标记 false", info.isLiteralHql)
    }

    fun testEntityManager_createQuery_variableConcat_withParam() {
        // String hql = "..." + userName + "..."; em.createQuery(hql) — 拼接中含变量引用
        // collectStringLiterals 只收集 PsiLiteralExpression，变量引用部分会丢失
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findByName(EntityManager em, String userName) {
                    String hql = "select u from UserEntity u where u.name = '" + userName + "'";
                    return em.createQuery(hql, UserEntity.class).getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString, Class resultClass);
            }
            interface Query { List getResultList(); }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByName")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("含参数拼接变量不应崩溃", info)
        // 字面量部分能收集到，变量引用部分丢失：结果为 "select u from UserEntity u where u.name = ''"
        assertTrue("应展开字面量部分，非变量名", !info!!.hql.equals("hql", ignoreCase = true))
        assertTrue("应含 select 片段", info.hql.contains("select u from UserEntity u where u.name = '"))
        assertTrue("应标记为字面量（部分展开）", info.isLiteralHql)
    }

    // ------------------------------------------------------------------
    // T-J12：createSQLQuery（老 Hibernate3，native SQL 语义）
    // ------------------------------------------------------------------

    fun testTJ12_createSQLQuery_nativeSemantics() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List findByNative(Session session, String status) {
                    Query q = session.createSQLQuery("select * from t_user where status = :status");
                    q.setParameter("status", status);
                    return q.list();
                }
            }
            interface Session {
                Query createSQLQuery(String sql);
            }
            interface Query {
                Query setParameter(String name, Object value);
                List list();
            }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByNative")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("createSQLQuery 应被识别（T-J12）", info)
        assertEquals("createSQLQuery", info!!.createQueryMethodName)
        assertTrue("createSQLQuery 应按 native SQL 语义归类", info.isNativeQuery)
        assertEquals("select * from t_user where status = :status", info.hql)
        assertEquals("status", info.parameters[0].paramName)
    }

    fun testTJ12_createSQLQuery_nativeSemanticsnegative_nonStringArgExcluded() {
        // 负例：createSQLQuery 非字符串首参（如 Criteria 形态）不识别
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public void byCriteria(Session session, DetachedCriteria criteria) {
                    session.createSQLQuery(criteria);
                }
            }
            interface Session {
                Query createSQLQuery(Object criteria);
            }
            interface Query {}
            class DetachedCriteria {}
            """.trimIndent(),
        )
        val method = findMethod("byCriteria")
        val info = HqlQueryLocator.locate(method)
        assertNull("非字符串首参的 createSQLQuery 不应识别（T-J12 守卫）", info)
    }

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }

    /**
     * T-HqlUpdateEntityMap S1：无 FROM 子句的 HQL UPDATE（用户报障 demo 形态 17 同款）——
     * 实体类名按语句头提取，不再恒为 null（此前 FROM-only 正则提取不到，
     * 下游 resolveHqlTable 三路径全跳过、TableInfo 落空）。
     */
    fun testCreateQuery_updateHqlWithoutFrom_extractsEntityName() {
        myFixture.configureByText(
            "WmsDockBookingDao.java",
            """
            public class HibernateTemplateDemoService {
                public int updateEmbeddedCreatedTime(Session session, Long id, java.time.LocalDateTime createdTime) {
                    return session.createQuery(
                        "update WmsDockBooking booking set booking.updateInfo.createdTime = ?2 where booking.id = ?1")
                        .setParameter(1, id)
                        .setParameter(2, createdTime)
                        .executeUpdate();
                }
            }
            interface Session {
                Query createQuery(String hql);
            }
            interface Query {
                Query setParameter(int pos, Object value);
                int executeUpdate();
            }
            class WmsDockBooking {}
            """.trimIndent(),
        )
        val method = findMethod("updateEmbeddedCreatedTime")
        val info = HqlQueryLocator.locate(method)
        assertNotNull("无 FROM 的 update HQL 应被识别", info)
        assertEquals(
            "update WmsDockBooking booking set booking.updateInfo.createdTime = ?2 where booking.id = ?1",
            info!!.hql,
        )
        assertEquals("无 FROM 的 UPDATE 应按语句头提取实体类名", "WmsDockBooking", info.entityClassName)
    }

    // ------------------------------------------------------------------
    // T-HqlBufConcat：StringBuffer/StringBuilder 拼接串（与自定义 DAO 通道同族收敛）
    // ------------------------------------------------------------------

    fun testCreateQuery_stringBufferToString() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List<UserEntity> findByStatus(Session session) {
                    StringBuffer sb = new StringBuffer();
                    sb.append("from UserEntity u ");
                    sb.append("where u.status = 'A'");
                    return session.createQuery(sb.toString()).list();
                }
            }
            interface Session {
                Query createQuery(String hql);
            }
            interface Query { List list(); }
            class UserEntity { private String status; }
            """.trimIndent(),
        )
        val info = HqlQueryLocator.locate(findMethod("findByStatus"))
        assertNotNull("createQuery(sb.toString()) 应被识别", info)
        assertEquals("from UserEntity u where u.status = 'A'", info!!.hql)
        assertEquals("UserEntity", info.entityClassName)
    }

    fun testCreateQuery_stringBuilderViaStringVariable() {
        myFixture.configureByText(
            "UserDao.java",
            """
            public class UserDao {
                public List<UserEntity> findAllUsers(Session session) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("select u from UserEntity u ");
                    sb.append("order by u.id");
                    String hql = sb.toString();
                    return session.createQuery(hql).list();
                }
            }
            interface Session {
                Query createQuery(String hql);
            }
            interface Query { List list(); }
            class UserEntity {}
            """.trimIndent(),
        )
        val info = HqlQueryLocator.locate(findMethod("findAllUsers"))
        assertNotNull("String hql = sb.toString(); createQuery(hql) 应被识别", info)
        assertEquals("select u from UserEntity u order by u.id", info!!.hql)
    }
}
