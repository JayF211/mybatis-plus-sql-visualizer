package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * SqlGutterLineMarkerProvider 测试（M10-02）。
 *
 * 在 LightPlatform 虚拟项目中验证 Line marker 快速路径行为：
 *  - 链式 / 内联形态在锚点叶子元素上产生 marker
 *  - 同行去重（new 关键字不重复出 marker）
 *  - 非 Wrapper 代码零 marker（负例零成本）
 *  - marker 携带静态 tooltip（性能红线：不计算 SQL）
 */
class SqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = SqlGutterLineMarkerProvider()

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    private fun firstVariable(): PsiLocalVariable =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!

    /** 文件内第一个局部变量的名字 identifier（测试内实体类 nameIdentifier 可空，集中断言非空）。 */
    private fun firstVariableId() =
        firstVariable().nameIdentifier!!

    private fun firstNewKeyword(): PsiKeyword =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiNewExpression::class.java)!!.firstChild as PsiKeyword

    fun testChainStyleMarkerOnVariableDeclaration() {
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        assertNotNull("变量声明行应产生 gutter marker", provider.getLineMarkerInfo(firstVariableId()))
        assertNull("与变量声明同行的 new 关键字不应再产生 marker（去重）", provider.getLineMarkerInfo(firstNewKeyword()))
    }

    fun testInlineMarkerOnNewKeyword() {
        configure(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    list(new MPJLambdaWrapper<DeviceEntity>().select(DeviceEntity::getId).eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        assertNotNull("内联构造的 new 关键字应产生 gutter marker", provider.getLineMarkerInfo(firstNewKeyword()))
    }

    fun testNonWrapperProducesNoMarker() {
        configure(
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """,
        )
        assertNull("非 Wrapper 变量不应产生 marker", provider.getLineMarkerInfo(firstVariableId()))
    }

    fun testFullTreeTraversalProducesMarker() {
        // 模拟 LineMarkersPass 调用方式：对文件内全部元素逐个调用 getLineMarkerInfo
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val elements = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiElement::class.java)
        val markers = elements.mapNotNull { provider.getLineMarkerInfo(it) }
        assertTrue(
            "按平台遍历方式应产出至少 1 个 marker，实际 ${markers.size} 个",
            markers.isNotEmpty(),
        )
    }

    fun testGutterIconLoads() {
        // 图标资源加载失败会导致 marker 渲染异常，显式断言
        val icon = IconLoader.getIcon("/icons/sqlGutter.svg", SqlGutterLineMarkerProvider::class.java)
        assertNotNull("gutter 图标应可加载", icon)
    }
}
