package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * JpqlGutterLineMarkerProvider 测试（波次一）：
 *  - @Query 注解方法的 `@` token 产生 marker
 *  - 非 @Query 注解（如自定义 @Query 短名但方法无注解 / @Override）零 marker
 *  - 普通标识符零 marker
 */
class JpqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = JpqlGutterLineMarkerProvider()

    private val repository = """
        public interface UserRepository extends JpaRepository<UserEntity, Long> {
            @Query("SELECT u FROM UserEntity u WHERE u.username = ?1")
            UserEntity findByUsername(String username);

            @Query(value = "SELECT * FROM users WHERE name = ?1", nativeQuery = true)
            UserEntity findByName(String name);

            UserEntity findByEmail(String email);
        }

        @interface Query {
            String value();
            boolean nativeQuery() default false;
        }
        class JpaRepository<T, ID> {}
        class UserEntity {
            private Long id;
            private String username;
            private String name;
            private String email;
        }
    """.trimIndent()

    /** 指定名称注解的 `@` token（leaf 锚点）。 */
    private fun atTokenOfAnnotation(annotationName: String): PsiJavaToken =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiJavaToken::class.java)
            .first {
                it.tokenType == JavaTokenType.AT &&
                    PsiTreeUtil.getParentOfType(it, PsiAnnotation::class.java, false)
                        ?.nameReferenceElement?.referenceName == annotationName
            }

    fun testQueryAnnotationProducesMarker() {
        myFixture.configureByText("UserRepository.java", repository)
        val queryAt = atTokenOfAnnotation("Query")
        assertNotNull("@Query 注解应产生 gutter marker", provider.getLineMarkerInfo(queryAt))
    }

    fun testMethodWithoutQueryAnnotationZeroMarker() {
        myFixture.configureByText("UserRepository.java", repository)
        // 找 findByEmail 方法名的标识符 leaf —— 该方法无 @Query 注解
        val identifier = PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "findByEmail" }
        assertNull("无 @Query 注解的方法标识符不应产生 marker", provider.getLineMarkerInfo(identifier))
    }

    fun testNonQueryAnnotationZeroMarker() {
        // 使用真实注解名（末段非 "Query"）+ 所属方法无 @Query 注解 → 零 marker
        myFixture.configureByText(
            "Other.java",
            """
            public class Other {
                @Override
                public String toString() { return ""; }

                @Deprecated
                void old() {}
            }

            @interface Override { String value(); }
            @interface Deprecated { String value(); }
            """.trimIndent(),
        )
        val overrideAt = atTokenOfAnnotation("Override")
        assertNull("@Override 注解不应产生 JPQL marker", provider.getLineMarkerInfo(overrideAt))
        val deprecatedAt = atTokenOfAnnotation("Deprecated")
        assertNull("@Deprecated 注解不应产生 JPQL marker", provider.getLineMarkerInfo(deprecatedAt))
    }
}
