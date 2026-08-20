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

            // 非派生查询方法（方法名不含 By）→ 零 marker
            List<UserEntity> findAll();

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
        // findAll 不是派生查询（方法名不含 By）→ 零 marker
        val identifier = findIdentifier("findAll")
        assertNotNull("应找到 findAll 标识符", identifier)
        assertNull(
            "非派生查询方法名不应产生 JPA marker",
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
}
