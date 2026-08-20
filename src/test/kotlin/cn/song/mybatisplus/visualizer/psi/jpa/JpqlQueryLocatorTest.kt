package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JpqlQueryLocator 单元测试（F-JPA-02）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - @Query 注解方法识别
 *  - JPQL 字符串提取
 *  - nativeQuery 属性检测
 *  - name 属性提取
 *  - 实体类解析
 *  - 非 @Query 方法排除
 *
 * 注意：测试环境无 Spring Data classpath，@Query 注解在测试代码中内联定义
 * （与 DerivedMethodLocatorTest 内联 @Query 注解同模式）。
 */
class JpqlQueryLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // @Query 注解方法识别
    // ------------------------------------------------------------------

    fun testQueryAnnotation_methodIdentified() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.username = ?1")
                UserEntity findByUsername(String username);
            }

            @interface Query {
                String value();
            }
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("@Query 方法应被识别", info)
        assertEquals("findByUsername", info!!.methodName)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = ?1", info.jpql)
        assertFalse("JPQL 查询不是原生 SQL", info.isNative)
    }

    fun testQueryAnnotation_withNamedParameter() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.username = :username")
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
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = :username", info.jpql)
    }

    fun testQueryAnnotation_nativeQuery() {
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
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertTrue("nativeQuery = true 应被识别", info.isNative)
        assertEquals("SELECT * FROM users WHERE name = ?1", info.jpql)
    }

    fun testQueryAnnotation_nonNativeQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(value = "SELECT u FROM UserEntity u", nativeQuery = false)
                List<UserEntity> findAllCustom();
            }

            @interface Query {
                String value();
                boolean nativeQuery() default false;
            }
            class JpaRepository<T, ID> {}
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findAllCustom")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertFalse("nativeQuery = false 不应标记为原生", info.isNative)
    }

    // ------------------------------------------------------------------
    // name 属性提取
    // ------------------------------------------------------------------

    fun testQueryAnnotation_withNameAttribute() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query(name = "User.findByUsername", value = "SELECT u FROM UserEntity u WHERE u.username = ?1")
                UserEntity findByUsername(String username);
            }

            @interface Query {
                String value();
                String name() default "";
            }
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("User.findByUsername", info.queryName)
    }

    fun testQueryAnnotation_withoutNameAttribute() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u")
                List<UserEntity> findAllCustom();
            }

            @interface Query { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findAllCustom")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertNull("未指定 name 时应为 null", info.queryName)
    }

    // ------------------------------------------------------------------
    // 实体类解析
    // ------------------------------------------------------------------

    fun testQueryAnnotation_entityClassResolvedFromJpaRepository() {
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
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testQueryAnnotation_entityClassResolvedFromCrudRepository() {
        myFixture.configureByText(
            "OrderRepository.java",
            """
            public interface OrderRepository extends CrudRepository<OrderEntity, Long> {
                @Query("SELECT o FROM OrderEntity o WHERE o.orderNo = ?1")
                OrderEntity findByOrderNo(String orderNo);
            }

            @interface Query { String value(); }
            class CrudRepository<T, ID> {}
            class OrderEntity {
                private Long id;
                private String orderNo;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByOrderNo")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("OrderEntity", info.entityClass?.name)
    }

    // ------------------------------------------------------------------
    // 非 @Query 方法排除
    // ------------------------------------------------------------------

    fun testNonQueryMethod_excluded() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity findByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNull("非 @Query 方法应返回 null", info)
    }

    fun testDefaultMethod_withQueryAnnotation_stillIdentified() {
        // default 方法带 @Query 注解仍应被识别（@Query 优先级高于方法形态检查）
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                @Query("SELECT u FROM UserEntity u WHERE u.username = ?1")
                default UserEntity findByUsername(String username) {
                    return null;
                }
            }

            @interface Query { String value(); }
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = JpqlQueryLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("@Query default 方法应被识别", info)
    }

    // ------------------------------------------------------------------
    // hasQueryAnnotation 直接测试
    // ------------------------------------------------------------------

    fun testHasQueryAnnotation_true() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                @Query("SELECT u FROM UserEntity u")
                UserEntity findByUsername(String username);
            }

            @interface Query { String value(); }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertTrue("@Query 方法应返回 true", JpqlQueryLocator.hasQueryAnnotation(method))
    }

    fun testHasQueryAnnotation_false() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                UserEntity findByUsername(String username);
            }

            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertFalse("非 @Query 方法应返回 false", JpqlQueryLocator.hasQueryAnnotation(method))
    }

    // ------------------------------------------------------------------
    // 多方法场景测试
    // ------------------------------------------------------------------

    fun testMultipleMethods_onlyQueryMethodIdentified() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                @Query("SELECT u FROM UserEntity u WHERE u.username = ?1")
                UserEntity findByUsername(String username);

                List<UserEntity> findByEmailContaining(String email);

                @Query("SELECT COUNT(u) FROM UserEntity u")
                long countAll();

                boolean existsByUsername(String username);
            }

            @interface Query { String value(); }
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
                private String email;
            }
            """.trimIndent(),
        )

        // findByUsername 应识别为 @Query
        val findByUsername = findMethod("findByUsername")
        val queryInfo = JpqlQueryLocator.locate(myFixture.file, findByUsername.textRange.startOffset)
        assertNotNull("findByUsername 应识别为 @Query", queryInfo)
        assertEquals("SELECT u FROM UserEntity u WHERE u.username = ?1", queryInfo!!.jpql)

        // findByEmailContaining 不应识别
        val findByEmail = findMethod("findByEmailContaining")
        assertNull(
            "findByEmailContaining 不应识别为 @Query",
            JpqlQueryLocator.locate(myFixture.file, findByEmail.textRange.startOffset),
        )

        // countAll 应识别为 @Query
        val countAll = findMethod("countAll")
        val countInfo = JpqlQueryLocator.locate(myFixture.file, countAll.textRange.startOffset)
        assertNotNull("countAll 应识别为 @Query", countInfo)
        assertEquals("SELECT COUNT(u) FROM UserEntity u", countInfo!!.jpql)

        // existsByUsername 不应识别
        val existsByUsername = findMethod("existsByUsername")
        assertNull(
            "existsByUsername 不应识别为 @Query",
            JpqlQueryLocator.locate(myFixture.file, existsByUsername.textRange.startOffset),
        )
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 按方法名查找 PsiMethod（未找到时抛异常）。 */
    private fun findMethod(methodName: String): PsiMethod {
        val methods = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
        return methods.firstOrNull { it.name == methodName }
            ?: error("测试文件缺少方法: $methodName")
    }

    /** 按类名查找 PsiClass（未找到时抛异常）。 */
    private fun findClass(className: String): PsiClass {
        val classes = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
        return classes.firstOrNull { it.name == className }
            ?: error("测试文件缺少类: $className")
    }
}
