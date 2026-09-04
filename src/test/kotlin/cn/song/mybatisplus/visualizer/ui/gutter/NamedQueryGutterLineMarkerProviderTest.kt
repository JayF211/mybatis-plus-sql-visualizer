package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * NamedQueryGutterLineMarkerProvider 测试（T-PreviewShortcut）：
 *  - 实体类独立 `@NamedQuery` 注解的 `@` token 产生 marker
 *  - `@NamedQueries` 容器内每个 `@NamedQuery` 子注解各产生 marker（容器本身跳过）
 *  - 非 `@NamedQuery` 注解零 marker
 */
class NamedQueryGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = NamedQueryGutterLineMarkerProvider()

    /** 收集文件中全部 `@` token。 */
    private fun atTokens(): List<PsiJavaToken> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiJavaToken::class.java)
            .filter { it.tokenType == JavaTokenType.AT }

    fun testStandaloneNamedQueryProducesMarker() {
        myFixture.configureByText("UserEntity.java", """
            @NamedQuery(name = "UserEntity.findByUsername", query = "SELECT u FROM UserEntity u WHERE u.username = :username")
            public class UserEntity {
                private Long id;
                private String username;
            }

            @interface NamedQuery { String name(); String query(); }
        """.trimIndent())
        // atTokens 含注解使用 + @interface 声明处的 @；marker 仅来自 @NamedQuery 注解使用
        assertEquals("@NamedQuery 注解应产生 1 个 gutter marker", 1, markers().size)
    }

    fun testNamedQueriesContainerEachInnerProducesMarker() {
        myFixture.configureByText("ProductEntity.java", """
            @NamedQueries({
                @NamedQuery(name = "ProductEntity.findByName", query = "SELECT p FROM ProductEntity p WHERE p.name = :name"),
                @NamedQuery(name = "ProductEntity.findByPrice", query = "SELECT p FROM ProductEntity p WHERE p.price > :price")
            })
            public class ProductEntity {
                private Long id;
                private String name;
                private Double price;
            }

            @interface NamedQueries { NamedQuery[] value(); }
            @interface NamedQuery { String name(); String query(); }
        """.trimIndent())
        // 仅 2 个内部 @NamedQuery 产生 marker（容器 @NamedQueries 与 @interface 声明不产生）
        assertEquals("仅 2 个内部 @NamedQuery 应产生 marker（容器跳过）", 2, markers().size)
    }

    fun testNonNamedQueryAnnotationZeroMarker() {
        myFixture.configureByText("PlainEntity.java", """
            @Entity
            public class PlainEntity {
                private Long id;
            }
            @interface Entity {}
        """.trimIndent())
        assertEquals("非 @NamedQuery 注解不应产生 marker", 0, markers().size)
    }

    /** 文件全部 @ token 中产生 marker 的个数。 */
    private fun markers(): List<com.intellij.codeInsight.daemon.LineMarkerInfo<*>> =
        atTokens().mapNotNull { provider.getLineMarkerInfo(it) }
}