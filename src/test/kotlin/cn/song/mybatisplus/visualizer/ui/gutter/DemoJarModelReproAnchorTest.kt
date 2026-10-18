package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-JarEmbedded：真实 demo 的实体与嵌入类来自依赖 JAR，宿主只有 Service/DAO。
 * 每个自定义 DAO 调用必须恰 1 枚 gutter 图标，覆盖拼接变量和 StringBuffer + toString 两种形态。
 * 同级 demo 载体已定位；不存在时明确失败而非用空集合假绿。
 */
class DemoJarModelReproAnchorTest : BasePlatformTestCase() {
    private val serviceDir = File("..",
        "mybatis-plus-sql-visualizer-demo/jpa-jar-model-demo/src/main/java/cn/song/jarmodeldemo/service")
    private val providers = listOf(CustomDaoHqlGutterLineMarkerProvider())

    private fun loadServiceFile(name: String) {
        val file = File(serviceDir, name)
        assertTrue("demo 复现文件应存在: $file", file.exists())
        myFixture.configureByText(file.name, file.readText())
    }

    fun testDemo_jarModelRepro_everyCall_singleIcon() {
        loadServiceFile("WmsInventoryJarQueryService.java")
        val leaves = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiReferenceExpression && it.text == "findByQueryMaxNum" }
        assertTrue("复现用例应含 wmsJarCommonDao.findByQueryMaxNum 调用", leaves.isNotEmpty())
        assertEquals("复现用例应备齐两种承载形态", 2, leaves.size)
        for (leaf in leaves) {
            assertEquals("wmsJarCommonDao.findByQueryMaxNum 应恰 1 枚预览图标（0 = 首参承载形态不被支持）",
                1, providers.count { it.getLineMarkerInfo(leaf) != null })
        }
    }
}
