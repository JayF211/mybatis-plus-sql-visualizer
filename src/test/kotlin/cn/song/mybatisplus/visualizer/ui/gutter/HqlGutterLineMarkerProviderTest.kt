package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNull

/**
 * Hibernate HQL gutter provider 确定性测试（F-44 续 · 补用例大波次）：
 * 仅覆盖**不依赖 HQL 解析器成功**的快路径守卫（这些才是易回归且确定性可验的行为），
 * 解析器命中的「正向注册」需 JPA 实体/持久化单元，在 BasePlatformTestCase 下非确定性，交由
 * [cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator] 自身测试覆盖。覆盖：
 *   - 非 PsiIdentifier leaf → null
 *   - 变量名标识符（非方法调用）→ null
 *   - cb.createQuery(SomeClass.class) 首参为 Class 字面量 → 排除（回归 M36.5.79 多行拼接 HQL 无图标）
 *   - showGutterIcon=false 总开关 → null
 */
class HqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private var savedGutter: Boolean? = null

    override fun setUp() {
        super.setUp()
        savedGutter = PluginSettings.getInstance().state.showGutterIcon
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.showGutterIcon = savedGutter ?: true
        super.tearDown()
    }

    private fun javaFile(text: String): PsiJavaFile {
        myFixture.configureByText("A.java", text)
        return myFixture.file as PsiJavaFile
    }

    private fun id(file: PsiJavaFile, name: String): PsiIdentifier =
        PsiTreeUtil.collectElementsOfType(file, PsiIdentifier::class.java).first { it.text == name }

    fun testGetLineMarkerInfo_非PsiIdentifierLeaf返回null() {
        val file = javaFile("class A { int x = 1; }")
        val ws = PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java).first()
        assertNull(HqlGutterLineMarkerProvider().getLineMarkerInfo(ws))
    }

    fun testGetLineMarkerInfo_变量名标识符返回null() {
        val file = javaFile("class A { int x = 1; }")
        assertNull(HqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "x")))
    }

    fun testGetLineMarkerInfo_CriteriaBuilderCreateQuery排除_回归M36_5_79() {
        // cb.createQuery(SomeClass.class) 首参为 Class 字面量 → 不识别为 HQL 字符串 → 不应注册图标
        val file = javaFile("class A { void m() { Object cb; cb.createQuery(String.class); } }")
        assertNull(HqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "createQuery")))
    }

    fun testGetLineMarkerInfo_关闭gutter开关返回null() {
        PluginSettings.getInstance().state.showGutterIcon = false
        val file = javaFile("class A { void m() { Object s; s.createQuery(\"from X\"); } }")
        assertNull(HqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "createQuery")))
    }

    fun testGetLineMarkerInfo_createQuery字符串参数_识别注册() {
        // session.createQuery("from X")：方法名匹配 + 首参为字符串字面量 → HqlQueryLocator 按命名约定命中 → 注册
        val file = javaFile("class A { void m() { Object s; s.createQuery(\"from X\"); } }")
        assertNotNull(
            "createQuery 字符串参数 HQL 调用应被识别并注册 gutter 图标",
            HqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "createQuery")),
        )
    }
}
