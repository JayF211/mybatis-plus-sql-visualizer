package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * JpaGutterLineMarkerProvider 测试（波次一）：
 *  - 派生查询方法名 PsiIdentifier 产生 marker
 *  - 方法体内其他 PsiIdentifier 不产生重复 marker（去重验证）
 *  - 非派生查询方法零 marker
 */
class JpaGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = JpaGutterLineMarkerProvider()

    private val repository = """
        public interface UserRepository extends JpaRepository<UserEntity, Long> {
            // 派生查询方法 → JPA provider 产生 marker
            UserEntity findByLastName(String lastName);

            // 非派生查询方法（无 By 且非 findAll/count 等全量形态）→ 零 marker
            UserEntity getUsername(String username);

            // 派生查询 + @Query → JPA provider 跳过（isDerivedQuery 排除 @Query 方法）
            @Query("SELECT u FROM UserEntity u WHERE u.email = ?1")
            UserEntity findByEmail(String email);
        }

        @interface Query { String value(); }
        class JpaRepository<T, ID> {}
        class UserEntity {
            private Long id;
            private String username;
            private String email;
            private String lastName;
        }
    """.trimIndent()

    /** 查找指定名称的 PsiIdentifier leaf（方法名）。 */
    private fun findIdentifier(name: String): PsiIdentifier? =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .firstOrNull { it.text == name }

    fun testDerivedQueryMethodProducesMarker() {
        myFixture.configureByText("UserRepository.java", repository)
        // findByLastName 是派生查询 → JPA provider 产生 marker
        val methodId = findIdentifier("findByLastName")
        assertNotNull("应找到 findByLastName 标识符", methodId)
        assertNotNull(
            "派生查询方法名应产生 gutter marker",
            provider.getLineMarkerInfo(methodId!!),
        )
    }

    fun testMethodBodyLeavesProduceNoDuplicateMarker() {
        myFixture.configureByText("UserRepository.java", repository)
        // 去重验证：方法体内每个 PsiIdentifier 都不应产生第二个 marker
        val methodId = findIdentifier("findByLastName")
        assertNotNull("应找到 findByLastName 标识符", methodId)
        // 方法名产生 marker
        assertNotNull("方法名应产生 marker", provider.getLineMarkerInfo(methodId!!))

        // 方法体内的其他标识符（参数名等）不应产生重复 marker
        val allMethodLeaves = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
        val markers = allMethodLeaves.mapNotNull { provider.getLineMarkerInfo(it) }
        // 一个方法应只有一个 gutter marker（仅方法名产生）
        assertEquals("一个方法应只有一个 gutter marker", 1, markers.size)
    }

    fun testNonDerivedQueryMethodZeroMarker() {
        myFixture.configureByText("UserRepository.java", repository)
        // getUsername 无 By 且非 T-J3 全量形态（findAll/count 等）→ 不是派生查询 → 零 marker
        val identifier = findIdentifier("getUsername")
        assertNotNull("应找到 getUsername 标识符", identifier)
        assertNull(
            "非派生查询方法名不应产生 JPA marker",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testTJ3_findAllOnRepositoryProducesMarker() {
        // T-J3：无 By 门控放行后，Repository 接口的 findAll() 即派生查询 → 产生 JPA marker。
        // 独立 fixture：共享 fixture 须保持「仅 1 个派生方法」约束（全文件 marker 计数测试依赖此不变量）。
        myFixture.configureByText(
            "UserRepository.java",
            """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                List<UserEntity> findAll();
            }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; }
            """.trimIndent(),
        )
        val identifier = findIdentifier("findAll")
        assertNotNull("应找到 findAll 标识符", identifier)
        assertNotNull(
            "Repository 接口 findAll() 应产生 JPA marker（T-J3）",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testDerivedQueryWithQueryAnnotationSkipped() {
        myFixture.configureByText("UserRepository.java", repository)
        // findByEmail 是派生查询 + @Query → JPA provider 跳过（isDerivedQuery 排除 @Query 方法）
        val identifier = findIdentifier("findByEmail")
        assertNotNull("应找到 findByEmail 标识符", identifier)
        assertNull(
            "派生查询 + @Query 方法不应产生 JPA marker（由 JPQL provider 负责）",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testNonRepositoryMethodWithDeleteByNotMisjudged() {
        // 回归：普通类方法名 deleteByDbHelper（前缀 delete + 含 By）不应被误判为 JPA 派生查询。
        // 用户实测：DAO 手写 SQL 方法 `deleteByDbHelper` 曾因 isDerivedQuery 只查方法名、
        // 不检查 containing class（Repository 接口）而在方法签名行显示错误 JPA 预览图标。
        myFixture.configureByText("DbDemo.java", """
            public class DbDemo {
                // 普通 DAO 方法名以 delete 开头 + 含 By → 不应显示 JPA gutter（非 Repository 接口）
                public int deleteByDbHelper(Integer id) { return 0; }
            }
        """.trimIndent())
        val identifier = findIdentifier("deleteByDbHelper")
        assertNotNull("应找到 deleteByDbHelper 标识符", identifier)
        assertNull(
            "普通类方法 deleteByDbHelper 不应产生 JPA marker",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testRepositoryDeleteByStillProducesMarker() {
        // 保证修复不破坏真实 Repository 派生查询：JpaRepository 接口内 deleteByXxx → 产生 marker
        myFixture.configureByText("UserRepository.java", """
            public interface UserRepository extends JpaRepository<UserEntity, Long> {
                void deleteById(Long id);
            }
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; }
        """.trimIndent())
        val identifier = findIdentifier("deleteById")
        assertNotNull("应找到 deleteById 标识符", identifier)
        assertNotNull(
            "Repository 接口内的 deleteByXxx 派生查询应产生 marker",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testPlainClassPartTreeMethodZeroMarker() {
        // M38.1（负例回归，Flex-Helper「图标误显示」差评源）：普通类（非接口）里 PartTree 形态的
        // 方法名 findByXxx 不应标 JPA gutter——派生查询只存在于 Repository 接口。
        // 守卫 isInRepositoryInterface（文本级 O(1)：接口 + extends 类型名含 Repository）拦截。
        myFixture.configureByText("PlainDemo.java", """
            public class PlainDemo {
                // 普通类方法名 PartTree 形态（find 前缀 + 含 By）→ 不应显示 JPA gutter
                public UserEntity findByLastName(String lastName) { return null; }
            }
            class UserEntity { private String lastName; }
        """.trimIndent())
        val identifier = findIdentifier("findByLastName")
        assertNotNull("应找到 findByLastName 标识符", identifier)
        assertNull(
            "普通类的 findByXxx 方法名不应产生 JPA marker",
            provider.getLineMarkerInfo(identifier!!),
        )
    }

    fun testInterfaceWithoutRepositoryExtendsZeroMarker() {
        // M38.1 负例变体（守卫独有拦截面）：接口名以 Repository 结尾会被 DerivedMethodLocator
        // 的启发式路径 2（类名后缀）放行，但该接口未 extends 任何 Repository 基类型——
        // isInRepositoryInterface 文本守卫（extends 列表类型名含 Repository）拦截，不标 JPA gutter
        myFixture.configureByText("UserRepository.java", """
            public interface UserRepository {
                UserEntity findByLastName(String lastName);
            }
            class UserEntity { private String lastName; }
        """.trimIndent())
        val identifier = findIdentifier("findByLastName")
        assertNotNull("应找到 findByLastName 标识符", identifier)
        assertNull(
            "未 extends Repository 基类型的接口 findByXxx 不应产生 JPA marker",
            provider.getLineMarkerInfo(identifier!!),
        )
    }
}
