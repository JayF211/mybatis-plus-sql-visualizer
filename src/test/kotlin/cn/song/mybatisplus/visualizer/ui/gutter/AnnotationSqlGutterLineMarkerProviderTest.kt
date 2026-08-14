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
 * AnnotationSqlGutterLineMarkerProvider 测试（T-M25-03）：
 *  - SQL 注解（@Select/@Update/Provider 族）的 `@` token 产生 marker（O(1) 判定）
 *  - 非 SQL 注解（@Param/@Override）零 marker
 */
class AnnotationSqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = AnnotationSqlGutterLineMarkerProvider()

    private val mapper = """
        public interface UserMapper {
            @Select("SELECT * FROM t_user WHERE id = #{id}")
            User selectById(@Param("id") Long id);

            @SelectProvider(type = SqlProvider.class, method = "sql")
            User selectByProvider(Long id);

            @Override
            String toString();
        }
    """.trimIndent()

    /** 指定名称注解的 `@` token（leaf 锚点；qualifiedName 末段匹配，短名 import 亦可）。 */
    private fun atTokenOfAnnotation(annotationName: String): PsiJavaToken =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiJavaToken::class.java)
            .first {
                it.tokenType == JavaTokenType.AT &&
                    PsiTreeUtil.getParentOfType(it, PsiAnnotation::class.java, false)
                        ?.qualifiedName?.substringAfterLast('.') == annotationName
            }

    fun testSqlAnnotationProducesMarker() {
        myFixture.configureByText("UserMapper.java", mapper)
        assertNotNull("@Select 注解应产生 gutter marker", provider.getLineMarkerInfo(atTokenOfAnnotation("Select")))
        assertNotNull("@SelectProvider 注解应产生 gutter marker", provider.getLineMarkerInfo(atTokenOfAnnotation("SelectProvider")))
    }

    fun testNonSqlAnnotationZeroMarker() {
        myFixture.configureByText("UserMapper.java", mapper)
        assertNull("@Param 注解不应产生 marker", provider.getLineMarkerInfo(atTokenOfAnnotation("Param")))
        assertNull("@Override 注解不应产生 marker", provider.getLineMarkerInfo(atTokenOfAnnotation("Override")))
    }

    fun testNonAnnotationLeafZeroMarker() {
        myFixture.configureByText("UserMapper.java", mapper)
        val identifier = PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiIdentifier::class.java).first()
        assertNull("普通标识符不应产生 marker", provider.getLineMarkerInfo(identifier))
    }
}
