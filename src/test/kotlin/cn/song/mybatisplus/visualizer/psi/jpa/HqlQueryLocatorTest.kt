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
        assertNull("无实体类参数时 entityClassName 应为 null", info.entityClassName)
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

    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }
}
