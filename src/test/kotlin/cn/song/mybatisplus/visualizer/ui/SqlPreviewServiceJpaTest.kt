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
    // previewJpql：原生 SQL 参数转换（P2 修复）
    // ------------------------------------------------------------------

    fun testPreviewJpql_nativeQuery_parameterConversion() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(value = "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status", nativeQuery = true)
                List<UserEntity> findByKeywordNative(@org.springframework.data.repository.query.Param("keyword") String keyword, @org.springframework.data.repository.query.Param("status") Integer status);
            }

            @interface Query {
                String value();
                boolean nativeQuery() default false;
            }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val method = findMethod("findByKeywordNative")
        val result = newService().previewJpql(method)
        assertNotNull("原生 SQL 应产出预览结果", result)
        // 原生 SQL 的命名参数应被转换为 ?
        assertFalse("原生 SQL 不应包含 :keyword, 实际: ${result!!.sql}", result!!.sql.contains(":keyword"))
        assertFalse("原生 SQL 不应包含 :status, 实际: ${result!!.sql}", result!!.sql.contains(":status"))
        assertTrue("原生 SQL 应包含 ?", result!!.sql.contains("?"))
        // 参数名应从 @Param 注解提取
        assertEquals("参数名应为 [@Param] keyword, status", listOf("keyword", "status"), result.unresolvedParams)
    }

    // ------------------------------------------------------------------
    // previewJpql：参数名从方法参数提取（P2 修复）
    // ------------------------------------------------------------------

    fun testPreviewJpql_parameterNamesFromMethod() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("DELETE FROM UserEntity u WHERE u.age < :maxAge")
                int deleteByAgeLessThanJpql(@org.springframework.data.repository.query.Param("maxAge") Integer maxAge);
            }

            @interface Query { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; private Integer age; }
            """.trimIndent(),
        )
        val method = findMethod("deleteByAgeLessThanJpql")
        val result = newService().previewJpql(method)
        assertNotNull("DELETE JPQL 应产出预览结果", result)
        // DELETE JPQL 应生成 DELETE SQL
        assertTrue("应生成 DELETE SQL, 实际: ${result!!.sql}", result!!.sql.contains("DELETE", ignoreCase = true))
        // 参数名应从 @Param 注解提取
        assertEquals("参数名应为 [@Param] maxAge", listOf("maxAge"), result.unresolvedParams)
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

    // ------------------------------------------------------------------
    // 路由正确性：@Query + 派生查询方法名 → JPQL 路径（P2 修复）
    // ------------------------------------------------------------------

    /**
     * findByMinAmount 同时满足：方法名匹配派生查询（find + By）且有 @Query 注解。
     * 应走 JPQL 路径（@Query 优先），生成的 SQL 应含 @Query 注解中的 WHERE 条件。
     */
    fun testPreviewJpql_routingQueryTakesPrecedenceOverDerived() {
        myFixture.configureByText(
            "OrderRepository.java",
            """
            public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
                @Query("SELECT o FROM OrderEntity o WHERE o.totalAmount >= :minAmount ORDER BY o.totalAmount DESC")
                List<OrderEntity> findByMinAmount(@Param("minAmount") BigDecimal minAmount);
            }
            @interface Query { String value(); }
            @interface Param { String value(); }
            class JpaRepository<T, ID> {}
            class OrderEntity { private Long id; private BigDecimal totalAmount; }
            class BigDecimal {}
            """.trimIndent(),
        )
        val method = findMethod("findByMinAmount")
        val result = newService().previewJpql(method)
        assertNotNull("@Query 方法应产出预览结果", result)
        // SQL 应含 @Query 注解的 WHERE 条件（JPQL 路径），而非派生查询的 min_amount = ?
        assertTrue("SQL 应含 total_amount >= ?: ${result!!.sql}",
            result.sql.contains("total_amount") && result.sql.contains(">="))
        // 参数名应从 @Param 提取
        assertEquals("参数名应为 minAmount", listOf("minAmount"), result.unresolvedParams)
    }

    /**
     * 字符串拼接 @Query("..." + "...") 应正确提取完整 JPQL。
     * statByCustomer 用例：NEW DTO + GROUP BY + HAVING。
     */
    fun testPreviewJpql_concatenatedStringValueExtracted() {
        myFixture.configureByText(
            "OrderRepository.java",
            """
            public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
                @Query("SELECT NEW cn.song.jpademo.dto.OrderStatDTO(o.customerId, COUNT(o), SUM(o.totalAmount)) " +
                        "FROM OrderEntity o GROUP BY o.customerId HAVING COUNT(o) > 1")
                List<Object[]> statByCustomer();
            }
            @interface Query { String value(); }
            class JpaRepository<T, ID> {}
            class OrderEntity { private Long id; private Long customerId; }
            """.trimIndent(),
        )
        val method = findMethod("statByCustomer")
        val result = newService().previewJpql(method)
        assertNotNull("拼接 @Query 应产出预览结果", result)
        // 两段 JPQL 应被拼接
        assertTrue("SQL 应含 SELECT NEW: ${result!!.sql}", result.sql.contains("SELECT"))
        assertTrue("SQL 应含 GROUP BY: ${result.sql}", result.sql.contains("GROUP BY", ignoreCase = true))
    }
}
