package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewService 端到端测试：PSI 方法 → PreviewResult 各字段。
 *
 * 覆盖 demo 项目所有 @Query 方法：
 *  - JPQL @Query 方法
 *  - DELETE @Query 方法
 *  - 原生 SQL @Query 方法
 */
class SqlPreviewServiceEndToEndTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    private fun findMethod(name: String): PsiMethod =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == name }

    /**
     * 端到端：@Query 方法 → previewJpql → PreviewResult 各字段验证。
     * 覆盖 demo 项目所有 @Query 方法。
     */
    fun testE2eJpqlMethodProducesValidPreviewResultWithBackfillableParams() {
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
            class OrderEntity { private Long id; private String orderNo; private Long customerId; private BigDecimal totalAmount; }
            class BigDecimal {}
            """.trimIndent(),
        )
        val method = findMethod("findByMinAmount")
        val result = newService().previewJpql(method)

        assertNotNull("previewJpql 不应返回 null", result)
        // SQL 含 ?（面板回填前提）
        assertTrue("SQL 应含 ?: ${result!!.sql}", result.sql.contains("?"))
        // 参数名 == @Param 值
        assertEquals("参数名应为 minAmount", listOf("minAmount"), result.unresolvedParams)
        // paramTable 非 null
        assertNotNull("paramTable 不应为 null", result.paramTable)
        // 列信息非空
        assertTrue("columns 不应为空", result.columns.isNotEmpty())
        // 不应残留命名参数
        assertFalse("不应残留 :minAmount: ${result.sql}", result.sql.contains(":minAmount"))
    }

    /** 端到端：DELETE @Query 方法 */
    fun testE2eDeleteJpqlMethodProducesDeleteStatementWithWarning() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("DELETE FROM UserEntity u WHERE u.age < :maxAge")
                int deleteByAgeLessThanJpql(@Param("maxAge") Integer maxAge);
            }
            @interface Query { String value(); }
            @interface Param { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; private Integer age; }
            """.trimIndent(),
        )
        val method = findMethod("deleteByAgeLessThanJpql")
        val result = newService().previewJpql(method)

        assertNotNull(result)
        assertTrue("DELETE SQL 应含 DELETE: ${result!!.sql}", result.sql.contains("DELETE", ignoreCase = true))
        assertEquals("参数名应为 maxAge", listOf("maxAge"), result.unresolvedParams)
        // SQL 应含 ?
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertEquals("占位符数量应为 1", 1, result.sql.count { it == '?' })
    }

    /** 端到端：原生 SQL @Query 方法 */
    fun testE2eNativeSqlMethodConvertsNamedParamsToPlaceholders() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(value = "SELECT * FROM user WHERE username LIKE CONCAT('%', :keyword, '%') AND status = :status", nativeQuery = true)
                List<UserEntity> findByKeywordNative(@Param("keyword") String keyword, @Param("status") Integer status);
            }
            @interface Query { String value(); boolean nativeQuery() default false; }
            @interface Param { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val method = findMethod("findByKeywordNative")
        val result = newService().previewJpql(method)

        assertNotNull(result)
        // 命名参数应被转换为 ?
        assertFalse("SQL 不应含 :keyword: ${result!!.sql}", result.sql.contains(":keyword"))
        assertFalse("SQL 不应含 :status: ${result.sql}", result.sql.contains(":status"))
        assertTrue("SQL 应含 ?: ${result.sql}", result.sql.contains("?"))
        assertEquals("? 数量应为 2", 2, result.sql.count { it == '?' })
        // 参数名应从 @Param 提取
        assertEquals("参数名应为 keyword, status", listOf("keyword", "status"), result.unresolvedParams)
        // paramTable 非 null
        assertNotNull("paramTable 不应为 null", result.paramTable)
        // columns 非空
        assertTrue("columns 不应为空", result.columns.isNotEmpty())
    }

    /** 端到端：拼接 @Query 字符串应正确提取完整 JPQL */
    fun testE2eConcatenatedStringQueryExtractedCorrectly() {
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
        assertTrue("SQL 应含 SELECT: ${result!!.sql}", result.sql.contains("SELECT"))
        assertTrue("SQL 应含 GROUP BY: ${result.sql}", result.sql.contains("GROUP BY", ignoreCase = true))
    }
}
