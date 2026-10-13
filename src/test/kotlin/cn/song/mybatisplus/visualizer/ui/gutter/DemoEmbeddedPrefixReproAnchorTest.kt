package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-EmbeddedColPrefix / T-HqlHelperReturn：demo 复现模块 `jpa-embedded-prefix-demo` 的预览锚点门禁。
 *
 * 直接加载真实 demo 复现工程的服务源码，断言「每个可预览的自定义 DAO 调用恰 1 枚 gutter 图标」。
 * 存在理由是一次真实失误：复现用例最初把 HQL 抽到 `buildAvailableInventoryHql()` 方法里、调用处
 * 首参成了**方法调用**，当时的插件既不解包方法返回值也不认该形态，`locate` 返回 null——
 * **图标与动作同时失效**（用户真机实测报障）。这种「复现用例自己不可预览」的错，只有把锚点数
 * 钉死在测试里才拦得住。
 *
 * 该报障随后转化成了新能力 T-HqlHelperReturn（跟随源码可见方法的 return 表达式），
 * 故本类现在覆盖第三种承载形态，并保留一条「返回非 String → 仍不认领」的反向断言。
 */
class DemoEmbeddedPrefixReproAnchorTest : BasePlatformTestCase() {

    private val serviceDir = File(
        "..",
        "mybatis-plus-sql-visualizer-demo/jpa-embedded-prefix-demo/src/main/java/cn/song/embedprefixdemo/service",
    )

    /** 复现用例只该由自定义 DAO HQL 通道认领（HQL 字面量 + DAO qualifier）。 */
    private val providers = listOf(CustomDaoHqlGutterLineMarkerProvider())

    private fun loadServiceFile(name: String) {
        val file = File(serviceDir, name)
        assertTrue("demo 复现文件应存在: $file", file.exists())
        myFixture.configureByText(file.name, file.readText())
    }

    private fun callLeaves(methodName: String): List<PsiIdentifier> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiReferenceExpression && it.text == methodName }

    private fun iconCount(leaf: PsiIdentifier): Int = providers.count { it.getLineMarkerInfo(leaf) != null }

    /**
     * demo 复现用例里的每个调用都必须恰 1 枚图标——覆盖三种承载形态：
     *  - 拼接变量（`String hql = "…" + "…"`，`findAvailableInventory`）；
     *  - StringBuffer + `sb.toString()`（`findAvailableInventoryByBuffer`）；
     *  - **方法返回**（`commonDao.findByQueryMaxNum(buildAvailableInventoryHql(), …)`，
     *    `findAvailableInventoryViaHelper`，T-HqlHelperReturn）。
     */
    fun testDemo_embeddedPrefixRepro_everyCall_singleIcon() {
        loadServiceFile("WmsInventoryQueryService.java")

        val leaves = callLeaves("findByQueryMaxNum")
        assertTrue("复现用例应含 commonDao.findByQueryMaxNum 调用", leaves.isNotEmpty())
        assertEquals("复现用例应备齐三种承载形态", 3, leaves.size)

        for (leaf in leaves) {
            assertEquals(
                "commonDao.findByQueryMaxNum 应恰 1 枚预览图标（0 = 首参承载形态不被支持，" +
                    "图标与快捷键预览都会失效）：${leaf.text}",
                1,
                iconCount(leaf),
            )
        }
    }

    /**
     * 反向断言：首参是**返回非 String 的方法调用**（如 MP wrapper 壳）时仍不得亮图标——
     * 防「放行方法调用」把 S1 修过的「wrapper 链被当成 HQL」误判带回来。
     */
    fun testDemo_embeddedPrefixRepro_nonStringArgCall_zeroIcon() {
        myFixture.configureByText(
            "UserService.java",
            """
            public class UserService {
                public java.util.List<?> list(UserMapper userMapper) {
                    return userMapper.selectList(buildWrapper());
                }

                public Object buildWrapper() {
                    return new Object();
                }
            }
            interface UserMapper {
                java.util.List<?> selectList(Object wrapper);
            }
            """.trimIndent(),
        )

        val leaves = callLeaves("selectList")
        assertTrue("夹具应含 userMapper.selectList 调用", leaves.isNotEmpty())
        for (leaf in leaves) {
            assertEquals("首参是返回非 String 的方法调用 → 不应认领（防 wrapper 链误判回归）", 0, iconCount(leaf))
        }
    }
}
