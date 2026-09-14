package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * DerivedMethodLocator 单元测试（F-JPA-01）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - JpaRepository / CrudRepository 接口推导方法识别
 *  - 非 Repository 接口排除
 *  - @Query 注解方法排除
 *  - default 方法排除
 *  - 返回类型实体类解析
 *
 * 注意：测试环境无 Spring Data classpath，Repository 基类在测试代码中内联定义
 * （与 WrapperLocatorTest 内联 DeviceInfoService 同模式）。
 */
class DerivedMethodLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // JpaRepository 接口方法识别
    // ------------------------------------------------------------------

    fun testJpaRepository_findByDerivedQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity findByUsername(String username);
                List<UserEntity> findByEmailContaining(String email);
                Optional<UserEntity> findById(Long id);
                long countByStatus(Integer status);
                boolean existsByUsername(String username);
                void deleteByUsername(String username);
            }

            @interface MappedSuperclass {}
            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
                private String email;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertNotNull("应识别为推导方法", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("findByUsername", info.methodName)
        assertEquals("UserRepository", info.repositoryInterface.name)
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testJpaRepository_countByDerivedQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                long countByStatus(Integer status);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("countByStatus")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("countByStatus", info.methodName)
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testJpaRepository_existsByDerivedQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                boolean existsByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("existsByUsername")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("existsByUsername", info.methodName)
    }

    fun testJpaRepository_deleteByDerivedQuery() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                void deleteByUsername(String username);
                List<UserEntity> removeByStatus(Integer status);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
                private Integer status;
            }
            """.trimIndent(),
        )
        val deleteMethod = findMethod("deleteByUsername")
        assertNotNull(DerivedMethodLocator.locate(myFixture.file, deleteMethod.textRange.startOffset))
        val removeMethod = findMethod("removeByStatus")
        assertNotNull(DerivedMethodLocator.locate(myFixture.file, removeMethod.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // CrudRepository 接口方法识别
    // ------------------------------------------------------------------

    fun testCrudRepository_findByDerivedQuery() {
        myFixture.configureByText(
            "OrderRepository.java",
            """
            public interface OrderRepository extends CrudRepository<OrderEntity, Long> {
                OrderEntity findByOrderNo(String orderNo);
                List<OrderEntity> findByCustomerId(Long customerId);
            }

            class CrudRepository<T, ID> {}
            class OrderEntity {
                private Long id;
                private String orderNo;
                private Long customerId;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByOrderNo")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("findByOrderNo", info.methodName)
        assertEquals("OrderRepository", info.repositoryInterface.name)
        assertEquals("OrderEntity", info.entityClass?.name)
    }

    // ------------------------------------------------------------------
    // 启发式：接口名后缀 Repository 识别
    // ------------------------------------------------------------------

    fun testHeuristicRepositoryNameSuffix() {
        // 无 Spring Data 基类继承，仅靠接口名后缀 Repository 识别（置信度较低的启发式）
        myFixture.configureByText(
            "ProductRepository.java",
            """
            public interface ProductRepository {
                ProductEntity findByName(String name);
            }

            class ProductEntity {
                private Long id;
                private String name;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByName")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("findByName", info.methodName)
        assertEquals("ProductRepository", info.repositoryInterface.name)
    }

    // ------------------------------------------------------------------
    // 非 Repository 接口排除
    // ------------------------------------------------------------------

    fun testNonRepositoryInterfaceExcluded() {
        myFixture.configureByText(
            "RegularInterface.java",
            """
            public interface RegularInterface {
                UserEntity findByUsername(String username);
            }

            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertNull("非 Repository 接口应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    fun testClassNotInterfaceExcluded() {
        myFixture.configureByText(
            "RegularClass.java",
            """
            public abstract class RegularClass {
                public abstract UserEntity findByUsername(String username);
            }

            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertNull("非 interface 应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // @Query 注解方法排除
    // ------------------------------------------------------------------

    fun testQueryAnnotationExcluded() {
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
        assertNull("@Query 注解方法应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // default 方法排除
    // ------------------------------------------------------------------

    fun testDefaultMethodExcluded() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                default UserEntity findByUsername(String username) {
                    return null;
                }
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertNull("default 方法应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // static 方法排除
    // ------------------------------------------------------------------

    fun testStaticMethodExcluded() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                static UserEntity findByUsername(String username) {
                    return null;
                }
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertNull("static 方法应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // 方法名不含 By 排除
    // ------------------------------------------------------------------

    fun testMethodWithoutByExcluded() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity getUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("getUsername")
        assertNull("方法名不含 By 应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // 非 PartTree 前缀排除
    // ------------------------------------------------------------------

    fun testNonPartTreePrefixExcluded() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity fetchByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("fetchByUsername")
        assertNull("非 PartTree 前缀应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // T-J3：派生前缀断链补齐（query / search / stream）
    // ------------------------------------------------------------------

    fun testTJ3_queryPrefixRecognized() {
        // 旧实现 query 前缀缺失（门控断链），PartTreeParser 早已支持解析
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity queryByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("queryByUsername")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("query 前缀派生方法应识别（T-J3）", info)
        assertEquals("queryByUsername", info!!.methodName)
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testTJ3_searchPrefixRecognized() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                List<UserEntity> searchByStatus(Integer status);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("searchByStatus")
        assertNotNull("search 前缀派生方法应识别（T-J3）", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    fun testTJ3_streamPrefixRecognizedAsFind() {
        // streamBy 返回 Stream 的形态当 find 处理（PartTreeParser.QUERY_KEYWORDS 已含 stream）
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                java.util.stream.Stream<UserEntity> streamByStatus(Integer status);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("streamByStatus")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("streamBy 前缀派生方法应识别（T-J3，按 find 处理）", info)
        assertEquals("streamByStatus", info!!.methodName)
        assertEquals("UserEntity", info.entityClass?.name)
    }

    // ------------------------------------------------------------------
    // T-J3：无 By 形态放行（findAll / count / readAll / getAll 等，宿主须为 Repository）
    // ------------------------------------------------------------------

    fun testTJ3_findAllWithoutByRecognized() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                List<UserEntity> findAll();
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val method = findMethod("findAll")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("findAll() 无 By 形态应识别（T-J3）", info)
        assertEquals("findAll", info!!.methodName)
        assertEquals("UserEntity", info.entityClass?.name)
        assertTrue("findAll 应为可预览 Repository 方法", DerivedMethodLocator.isRepositoryMethod(method))
    }

    fun testTJ3_bareCountRecognized() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                long count();
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val method = findMethod("count")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)
        assertNotNull("count() 无 By 形态应识别（T-J3）", info)
        assertEquals("count", info!!.methodName)
    }

    fun testTJ3_allSuffixFormsRecognized() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                List<UserEntity> readAll();
                List<UserEntity> getAll();
                List<UserEntity> queryAll();
                List<UserEntity> searchAll();
                java.util.stream.Stream<UserEntity> streamAll();
                long countAll();
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        for (name in listOf("readAll", "getAll", "queryAll", "searchAll", "streamAll", "countAll")) {
            val method = findMethod(name)
            assertTrue("$name 应为派生查询（T-J3 All 后缀形态）", DerivedMethodLocator.isDerivedQuery(method))
            assertNotNull("$name 应可定位", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
        }
    }

    fun testTJ3_nonRepositoryClassFindAllExcluded() {
        // 守卫：非 Repository 类的同名方法绝不识别
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public List<UserEntity> findAll() { return null; }
                public long count() { return 0; }
                public List<UserEntity> getAll() { return null; }
            }

            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        for (name in listOf("findAll", "count", "getAll")) {
            val method = findMethod(name)
            assertFalse("$name 在非 Repository 类上不应识别为派生查询（T-J3 守卫）", DerivedMethodLocator.isDerivedQuery(method))
            assertFalse("$name 不应识别为 Repository 方法", DerivedMethodLocator.isRepositoryMethod(method))
        }
    }

    fun testTJ3_nonRepositoryInterfaceFindAllExcluded() {
        // 守卫：非 Repository 接口（无基类、名字不以 Repository 结尾）的同名方法不识别
        myFixture.configureByText(
            "GenericStore.java",
            """
            public interface GenericStore {
                List<UserEntity> findAll();
            }

            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        val method = findMethod("findAll")
        assertFalse("非 Repository 接口 findAll 不应识别（T-J3 守卫）", DerivedMethodLocator.isDerivedQuery(method))
        assertNull("locate 应返回 null", DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset))
    }

    // ------------------------------------------------------------------
    // isDerivedQuery 直接测试
    // ------------------------------------------------------------------

    fun testIsDerivedQuery_trueForFindBy() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity findByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertTrue("findByUsername 应为推导查询", DerivedMethodLocator.isDerivedQuery(method))
    }

    fun testIsDerivedQuery_falseForQueryAnnotation() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                @Query("SELECT u FROM User u")
                UserEntity findByUsername(String username);
            }

            @interface Query { String value(); }
            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertFalse("@Query 方法不应为推导查询", DerivedMethodLocator.isDerivedQuery(method))
    }

    fun testIsDerivedQuery_falseForDefaultMethod() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                default UserEntity findByUsername(String username) { return null; }
            }

            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        assertFalse("default 方法不应为推导查询", DerivedMethodLocator.isDerivedQuery(method))
    }

    fun testIsDerivedQuery_falseForNoBy() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository {
                UserEntity getOne(Long id);
            }

            class UserEntity {}
            """.trimIndent(),
        )
        val method = findMethod("getOne")
        assertFalse("不含 By 的方法不应为推导查询", DerivedMethodLocator.isDerivedQuery(method))
    }

    // ------------------------------------------------------------------
    // 返回类型实体类解析
    // ------------------------------------------------------------------

    fun testResolveEntityFromReturnType_directEntity() {
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
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testResolveEntityFromReturnType_listGeneric() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                List<UserEntity> findByEmailContaining(String email);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String email;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByEmailContaining")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testResolveEntityFromReturnType_optionalGeneric() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                Optional<UserEntity> findByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private String username;
            }
            """.trimIndent(),
        )
        val method = findMethod("findByUsername")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("UserEntity", info.entityClass?.name)
    }

    fun testResolveEntityFromReturnType_primitiveReturn() {
        // 返回基本类型（long countByXxx）时，实体类应从 Repository 接口泛型参数解析
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                long countByStatus(Integer status);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = findMethod("countByStatus")
        val info = DerivedMethodLocator.locate(myFixture.file, method.textRange.startOffset)!!
        assertEquals("UserEntity", info.entityClass?.name)
    }

    // ------------------------------------------------------------------
    // resolveEntityClass 直接测试
    // ------------------------------------------------------------------

    fun testResolveEntityClass_fromJpaRepository() {
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                UserEntity findByUsername(String username);
            }

            class JpaRepository<T, ID> {}
            class UserEntity {}
            """.trimIndent(),
        )
        val repositoryClass = findClass("UserRepository")
        val entityClass = DerivedMethodLocator.resolveEntityClass(repositoryClass)
        assertEquals("UserEntity", entityClass?.name)
    }

    fun testResolveEntityClass_fromCrudRepository() {
        myFixture.configureByText(
            "OrderRepository.java",
            """
            public interface OrderRepository extends CrudRepository<OrderEntity, Long> {
                OrderEntity findByOrderNo(String orderNo);
            }

            class CrudRepository<T, ID> {}
            class OrderEntity {}
            """.trimIndent(),
        )
        val repositoryClass = findClass("OrderRepository")
        val entityClass = DerivedMethodLocator.resolveEntityClass(repositoryClass)
        assertEquals("OrderEntity", entityClass?.name)
    }

    fun testResolveEntityClass_noGenericReturnsNull() {
        myFixture.configureByText(
            "PlainRepository.java",
            """
            public interface PlainRepository {
                Object findBySomething(String x);
            }
            """.trimIndent(),
        )
        val repositoryClass = findClass("PlainRepository")
        // 无 superType 泛型参数，resolveEntityClass 返回 null
        val entityClass = DerivedMethodLocator.resolveEntityClass(repositoryClass)
        assertNull("无泛型参数时 resolveEntityClass 应返回 null", entityClass)
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
