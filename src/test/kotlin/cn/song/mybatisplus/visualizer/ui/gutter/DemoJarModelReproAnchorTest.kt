package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-JarEmbedded：demo 复现模块 `jpa-jar-model-demo` 的预览锚点门禁。
 *
 * 语料前提＝**实体与嵌入类都来自依赖 JAR**（`jpa-jar-model-lib`，无源码），宿主只写 Service/DAO。
 * 本门禁加载宿主服务源码，断言每个自定义 DAO 调用恰 1 枚 gutter 图标——与
 * [DemoEmbeddedPrefixReproAnchorTest] 同款手法（那次教训：复现用例自己不可预览，只有把锚点数
 * 钉死在测试里才拦得住）。
 *
 * 覆盖两种承载形态：拼接变量、StringBuffer + `sb.toString()`。
 */
class DemoJarModelReproAnchorTest : BasePlatformTestCase() {

    private val serviceDir = File(
        "..",
        "mybatis-plus-sql-visualizer-demo/jpa-jar-model-demo/src/main/java/cn/song/jarmodeldemo/service",
    )

    /** 复现用例只该由自定义 DAO HQL 通道认领（HQL 承载 + DAO qualifier）。 */
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
            assertEquals(
                "wmsJarCommonDao.findByQueryMaxNum 应恰 1 枚预览图标（0 = 首参承载形态不被支持）",
                1,
                providers.count { it.getLineMarkerInfo(leaf) != null },
            )
        }
    }
}
