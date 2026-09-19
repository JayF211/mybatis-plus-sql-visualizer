package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.util.IconLoader
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
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
 *  - T-PCP-02：「最近执行记录」锚点走 EDT 捕获快照 + 后台校验（失效即降级不跳转）
 */
class SqlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = SqlGutterLineMarkerProvider()

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    /** 文档整体重写（EDT 改文档标准形态：WriteCommandAction 内 setText）。 */
    private fun rewriteDocument(text: String) {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(text)
        }
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

    fun testShowGutterIconOffSuppressesMarkers() {
        // M14.7：配置关闭 gutter 图标 → 不生成 marker（gutterEnabled 快速路径兜底）
        val settings = PluginSettings.getInstance()
        val old = settings.state.showGutterIcon
        settings.state.showGutterIcon = false
        try {
            configure(
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                    }
                }
                """.trimIndent(),
            )
            assertNull("关闭开关后不应生成 marker", provider.getLineMarkerInfo(firstVariableId()))
        } finally {
            settings.state.showGutterIcon = old
        }
    }

    /**
     * T-PCP-02：「最近执行记录」锚点 = EDT 捕获快照（[PsiSnapshot.capture]）→ 后台 [validatedRecentAnchor] 校验解析。
     *
     * 断言三态：未变更 → 正常命中且行号正确（正常路径行为不变）；文档变更 → 判失效返回 null
     * （调用方按「无最近记录」如实降级，不跳到错位置、不抛异常）；快照缺失 → 同样降级。
     */
    fun test最近记录锚点_正常命中_文档变更后判失效降级() {
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
        val snapshot = PsiSnapshot.capture(firstVariableId())
        assertNotNull("EDT 捕获应产出快照（含虚拟文件 + 文档）", snapshot)
        // 生产侧传 project.basePath；BasePlatformTestCase 的 basePath 为 null，故用例显式给项目根
        val basePath = snapshot!!.filePath.substringBeforeLast('/')

        val anchor = SqlGutterLineMarkerProviderKt.validatedRecentAnchor(basePath, snapshot)
        assertNotNull("未变更时锚点应命中（正常路径行为逐字节不变）", anchor)
        assertEquals("锚点应为项目根相对路径", "Demo.java", anchor!!.first)
        assertEquals("锚点行号 = 元素所在行（1-based）", 5, anchor.second)

        rewriteDocument(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    int before = 1;
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )

        assertNull(
            "文档变更后快照判失效 → 返回 null（不跳转、不渲染错值，调用方按「无记录」降级）",
            SqlGutterLineMarkerProviderKt.validatedRecentAnchor(basePath, snapshot),
        )
        assertNull("快照缺失（元素无虚拟文件/文档）同样降级",
            SqlGutterLineMarkerProviderKt.validatedRecentAnchor(basePath, null))
        assertNull("项目根不可用（basePath null）时不得给出绝对路径锚点",
            SqlGutterLineMarkerProviderKt.validatedRecentAnchor(null, snapshot))
    }
}
