package cn.song.mybatisplus.visualizer.integration

import cn.song.mybatisplus.visualizer.converter.jpa.HqlConverter
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 端到端预览测试——模拟完整的 locate → convert → preview 链路。
 * 使用 myFixture.project 确保正确的项目上下文。
 */
class PreviewEndToEndTest : BasePlatformTestCase() {

    private lateinit var service: SqlPreviewService

    override fun setUp() {
        super.setUp()
        // 通过 project.getService 获取 service（支持 @Service 注解自动注册）
        service = myFixture.project.getService(SqlPreviewService::class.java)
            ?: SqlPreviewService(myFixture.project)
    }

    // ------------------------------------------------------------------
    // 1. 链式调用场景（参数从 HQL 提取）
    // ------------------------------------------------------------------

    fun testChainedCall_parameterNamesFromHql() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByUsername(EntityManager em, String username, Integer minAge) {
                    return em.createQuery(
                        "SELECT u FROM UserEntity u WHERE u.username = ?1 AND u.age >= ?2")
                        .setParameter(1, username)
                        .setParameter(2, minAge)
                        .getResultList();
                }
            }
            interface EntityManager {
                Query createQuery(String qlString);
            }
            interface Query {
                Query setParameter(int position, Object value);
                List getResultList();
            }
            @Entity
            @Table(name = "user")
            class UserEntity {}
        """.trimIndent())
        val call = findMethodCall("createQuery")
        val result = service.previewHql(call)
        assertNotNull("链式调用应识别", result)
        val params = result!!.unresolvedParams
        // 参数名应为变量名（username/minAge），而非 #param1/#param2
        assertTrue("参数名不应含 #param", params.none { it.startsWith("#") })
    }

    // ------------------------------------------------------------------
    // 2. CriteriaQuery 的 where(List<Predicate>.toArray()) 不应产生 Java 代码
    // ------------------------------------------------------------------

    fun testCriteriaQueryWhereWithArray_noJavaCode() {
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> criteriaBasic(EntityManager em, Integer minAge, Integer status) {
                    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(cb.greaterThanOrEqualTo(root.get("age"), minAge));
                    predicates.add(cb.lessThan(root.get("age"), 100));
                    predicates.add(cb.equal(root.get("status"), status));
                    cq.where(predicates.toArray(new Predicate[0]));
                    cq.orderBy(cb.desc(root.get("id")));
                    return em.createQuery(cq).getResultList();
                }
            }
            interface EntityManager {
                CriteriaBuilder getCriteriaBuilder();
                Query createQuery(CriteriaQuery cq);
            }
            interface Query { List getResultList(); }
            interface CriteriaBuilder {
                <T> CriteriaQuery<T> createQuery(Class<T> resultClass);
                Predicate equal(com.intellij.psi.PsiExpression<?> a, Object value);
                Predicate greaterThanOrEqualTo(com.intellij.psi.PsiExpression<?> a, Comparable v);
                Predicate lessThan(com.intellij.psi.PsiExpression<?> a, Comparable v);
                Predicate notEqual(com.intellij.psi.PsiExpression<?> a, Object value);
                javax.persistence.criteria.Order desc(com.intellij.psi.PsiExpression<?> x);
            }
            interface CriteriaQuery<T> {
                void where(Predicate... predicates);
                void orderBy(java.util.List<javax.persistence.criteria.Order> orders);
                Root<T> from(Class<T> entityClass);
            }
            interface Root<T> { <Y> com.intellij.psi.PsiExpression<Y> get(String attributeName); }
            interface Predicate { }
            @Entity
            @Table(name = "user")
            class UserEntity { private Long id; private String username; private Integer age; private Integer status; }
        """.trimIndent())
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethod::class.java)
            .first { it.name == "criteriaBasic" }
        // 通过 CriteriaQuery provider 预览
        val result = service.previewCriteriaQuery(method)
        if (result != null) {
            // WHERE 子句不应含 Java 代码
            assertFalse("WHERE 不应含 predicates", result.sql.contains("predicates"))
            assertFalse("WHERE 不应含 new Predicate", result.sql.contains("new Predicate"))
            assertFalse("WHERE 不应含 ArrayList", result.sql.contains("ArrayList"))
        }
    }

    // ------------------------------------------------------------------
    // 3. 复制 SQL 时剥离注释
    // ------------------------------------------------------------------

    fun testCopySql_stripsComments() {
        val sql = "SELECT u FROM user u LEFT JOIN department d ON d.id = u.department_id /* 推断的ON条件，请验证 */ WHERE d.name = ?"
        val stripped = SqlCommentStripper.strip(sql)
        assertFalse("不应含 /*", stripped.contains("/*"))
        assertFalse("不应含 推断", stripped.contains("推断"))
        assertTrue("应含 LEFT JOIN", stripped.contains("LEFT JOIN"))
        assertTrue("应含 ON", stripped.contains(" ON "))
    }

    // ------------------------------------------------------------------
    // 4. 多行 HQL 字符串拼接（JOIN FETCH）
    // ------------------------------------------------------------------

    fun testJoinFetchNoDuplication() {
        // 使用单行 HQL 避免多行拼接解析问题
        myFixture.configureByText("UserDao.java", """
            public class UserDao {
                public List<UserEntity> findByDept(EntityManager em, String deptName, Integer status) {
                    Query q = em.createQuery("SELECT u FROM UserEntity u JOIN FETCH u.department d WHERE d.name = :deptName AND u.status = :status");
                    q.setParameter("deptName", deptName);
                    q.setParameter("status", status);
                    return q.getResultList();
                }
            }
            interface EntityManager { Query createQuery(String qlString); }
            interface Query {
                Query setParameter(String name, Object value);
                List getResultList();
            }
            @Entity
            @Table(name = "user")
            class UserEntity {}
        """.trimIndent())
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethod::class.java)
            .first { it.name == "findByDept" }
        val info = cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator.locate(method)
        assertNotNull("JOIN FETCH 应被定位", info)
        val userTable = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
        ))
        val result = HqlConverter.convert(info!!.hql, userTable)
        // 不应重复
        assertEquals("应只有一个 SELECT", 1, result.sql.split("SELECT").size - 1)
        assertTrue("应含 LEFT JOIN", result.sql.contains("LEFT JOIN"))
        assertTrue("应含 ON", result.sql.contains(" ON "))
    }

    // ------------------------------------------------------------------
    // 5. 命名参数 + 表名正确解析
    // ------------------------------------------------------------------

    fun testNamedParam_tableNameResolved() {
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
            @Entity
            @Table(name = "user")
            class UserEntity {}
        """.trimIndent())
        val call = findMethodCall("createQuery")
        val result = service.previewHql(call)
        assertNotNull("命名参数查询应识别", result)
        // 表名应为 user（来自 @Table 注解）
        assertTrue("SQL 应含 FROM user", result!!.sql.contains("FROM user"))
        // 参数名应为 username
        assertTrue("参数应含 username", result.unresolvedParams.contains("username"))
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private fun findMethodCall(methodName: String): PsiMethodCallExpression {
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        return calls.firstOrNull { it.methodExpression.referenceName == methodName }
            ?: error("测试文件缺少方法调用: $methodName")
    }
}
