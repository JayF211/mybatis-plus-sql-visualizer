package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 全 provider gutter **公共负例**回归（M38.1 / P-立项-06 A1）。
 *
 * Flex-Helper 真实差评的教训：锚点图标出现在不该出现的位置（import 行等）直接变成低分评价。
 * 本测试把全部 9 个 lineMarkerProvider 的判定入口拉平，对同一份「关键字全部出现在
 * import / 注释 / 字符串」的真实源码文件逐叶子断言零图标——一个文件覆盖全 provider，
 * 防止单个 provider 回归时漏检。
 *
 * XML 族 provider（XML_NAME token）在 Java 文件天然无锚点，一并纳入作为防回归哨兵。
 */
class GutterProvidersNegativeTest : BasePlatformTestCase() {

    private val providers: List<Any> = listOf(
        SqlGutterLineMarkerProvider(),
        AnnotationSqlGutterLineMarkerProvider(),
        XmlSqlGutterLineMarkerProvider(),
        JpaGutterLineMarkerProvider(),
        JpqlGutterLineMarkerProvider(),
        CriteriaQueryGutterLineMarkerProvider(),
        HqlGutterLineMarkerProvider(),
        CustomDaoHqlGutterLineMarkerProvider(),
        PagesXmlGutterLineMarkerProvider(),
    )

    fun testAllProvidersSilentOnKeywordTextInImportCommentAndString() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            /**
             * Javadoc 提及：findByUserIdAndStatus 与 new LambdaQueryWrapper<User>() 仅供参考。
             */
            public class Demo {
                private static final String SQL_TEXT =
                    "Wrappers.<User>lambdaQuery().eq(User::getId, 1L)";
                private static final String JPQL_TEXT =
                    "@Query(value = \"select u from User u where u.id = :id\")";
                private static final String XML_TEXT =
                    "<select id=\"findUser\">SELECT * FROM user</select>";

                public void helperCalls() {
                    new Helper().createQuery();
                    new Helper().findByQuery("x");
                }

                static class Helper {
                    public Object createQuery() { return this; }
                    public Object findByQuery(String q) { return q; }
                }
            }
            """,
        )
        val offenders = mutableListOf<String>()
        PsiTreeUtil.collectElements(myFixture.file) { element: PsiElement -> true }
            .forEach { leaf ->
                providers.forEach { provider ->
                    val marker = try {
                        @Suppress("UNCHECKED_CAST")
                        (provider as com.intellij.codeInsight.daemon.LineMarkerProvider)
                            .getLineMarkerInfo(leaf)
                    } catch (e: Exception) {
                        null
                    }
                    if (marker != null) offenders += "${provider::class.simpleName}@${leaf.text.take(40)}"
                }
            }
        assertTrue(
            "关键字仅出现在 import/注释/字符串时，全部 provider 应零图标。误报: $offenders",
            offenders.isEmpty(),
        )
    }
}
