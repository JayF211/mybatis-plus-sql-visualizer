package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewService JPA 预览测试（波次一）：验证 previewJpql / previewCriteriaQuery 链路。
 *
 * 手动构造 service（不依赖 plugin.xml 注册），聚焦服务本身的转换逻辑。
 */
class SqlPreviewServiceJpaTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    private fun findMethod(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    // ------------------------------------------------------------------
    // previewJpql：JPQL（非原生）
    // ------------------------------------------------------------------

    fun testPreviewJpql_jpqlQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.username = ?1")
                UserEntity findByUsername(String username);
            }

            @interface Query { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val result = newService().previewJpql(method)
        assertNotNull("JPQL 方法应产出预览结果", result)
        // JPQL 转换后应含 FROM 子句（实体类名 → 表名推断 → user_entity）
        assertTrue(
            "预览 SQL 应含 FROM 子句, 实际: ${result!!.sql}",
            result!!.sql.contains("FROM", ignoreCase = true),
        )
        assertEquals("findByUsername", method.name)
    }

    // ------------------------------------------------------------------
    // previewJpql：原生 SQL
    // ------------------------------------------------------------------

    fun testPreviewJpql_nativeQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(value = "SELECT * FROM users WHERE name = ?1", nativeQuery = true)
                UserEntity findByName(String name);
            }

            @interface Query {
                String value();
                boolean nativeQuery() default false;
            }
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByName")
        val result = newService().previewJpql(method)
        assertNotNull("原生查询应产出预览结果", result)
        // 原生 SQL 直接展示，不做 JPQL 转换
        assertTrue(
            "原生 SQL 应直接包含原始查询字符串, 实际: ${result!!.sql}",
            result!!.sql.contains("SELECT * FROM users"),
        )
    }

    // ------------------------------------------------------------------
    // previewCriteriaQuery
    // ------------------------------------------------------------------

    fun testPreviewCriteriaQuery_basic() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.where(cb.equal(root.get("status"), 1));
                    cq.select(root);
                }
            }

            class EntityManager {
                public CriteriaBuilder getCriteriaBuilder() { return null; }
            }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> x, Object y) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Predicate... predicates) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
            }
            class UserEntity {
                private Long id;
                private String username;
                private Integer status;
            }
            """.trimIndent(),
        )
        // 用 PsiMethodCallExpression 锚点（createQuery 在 CriteriaQuery 调用链上）
        val createQueryCall = PsiTreeUtil.collectElementsOfType(
            myFixture.file,
            com.intellij.psi.PsiMethodCallExpression::class.java,
        ).first { it.methodExpression.referenceName == "createQuery" }

        val result = newService().previewCriteriaQuery(createQueryCall)
        assertNotNull("CriteriaQuery 应产出预览结果", result)
        assertTrue(
            "预览 SQL 应含 SELECT 与 FROM, 实际: ${result!!.sql}",
            result!!.sql.contains("SELECT", ignoreCase = true) &&
                result.sql.contains("FROM", ignoreCase = true),
        )
    }
}
