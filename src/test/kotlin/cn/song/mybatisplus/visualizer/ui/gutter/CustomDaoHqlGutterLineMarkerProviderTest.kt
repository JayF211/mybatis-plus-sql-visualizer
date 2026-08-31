package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNull

/**
 * 自定义 DAO HQL gutter provider 确定性测试（F-44 续 · 补用例大波次）：
 * 与 [HqlGutterLineMarkerProviderTest] 同策略——只覆盖确定性快路径守卫，解析器命中的正向注册
 * 由 [cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator] 自身测试覆盖。覆盖：
 *   - 非 PsiIdentifier leaf → null
 *   - 变量名标识符（非方法调用）→ null
 *   - 标准 JPA 方法（createQuery）被显式排除，避免与 HqlGutterLineMarkerProvider 同行双图标
 *   - 自定义 DAO 调用走 locator 分支但 locator 无法解析假 dao → null（验证自定义分支不崩）
 *   - showGutterIcon=false 总开关 → null
 */
class CustomDaoHqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

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
        assertNull(CustomDaoHqlGutterLineMarkerProvider().getLineMarkerInfo(ws))
    }

    fun testGetLineMarkerInfo_变量名标识符返回null() {
        val file = javaFile("class A { int x = 1; }")
        assertNull(CustomDaoHqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "x")))
    }

    fun testGetLineMarkerInfo_标准JPA方法被排除避免双图标() {
        // session.createQuery("from X") 是标准 JPA 调用，本 provider 显式排除 → null（确定性，不经 locator）
        val file = javaFile("class A { void m() { Object s; s.createQuery(\"from X\"); } }")
        assertNull(CustomDaoHqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "createQuery")))
    }

    fun testGetLineMarkerInfo_自定义DAO调用被识别注册() {
        // dao.findByQuery("...") 进入自定义分支：CustomDaoHqlLocator 按方法名 + 首参字符串匹配，
        // 与接收者实际类型无关（命名约定驱动）→ 命中并注册图标（确定性正向行为）
        val file = javaFile("class A { void m() { Object dao; dao.findByQuery(\"from X\"); } }")
        assertNotNull(
            "自定义 DAO HQL 调用应被识别并注册 gutter 图标",
            CustomDaoHqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "findByQuery")),
        )
    }

    fun testGetLineMarkerInfo_关闭gutter开关返回null() {
        PluginSettings.getInstance().state.showGutterIcon = false
        val file = javaFile("class A { void m() { Object dao; dao.findByQuery(\"from X\"); } }")
        assertNull(CustomDaoHqlGutterLineMarkerProvider().getLineMarkerInfo(id(file, "findByQuery")))
    }
}
