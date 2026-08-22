package cn.song.mybatisplus.visualizer.ui.completion

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * ChainStateDetector 单元测试（F-5，阶段 A1 测试先行）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - 链首检测（new 构造 / 工厂调用）
 *  - 已调用方法收集（链中回溯）
 *  - 非 Wrapper 链返回 null
 *  - 当前位置判定（链首 / 链中 / 方法实参内）
 *
 * 锚点通过 [caretAnchor] 从文件内定位（插入标记 → 取 offset 处 PSI 元素），
 * 比 WrapperLocatorTest 的 anchor() 更精确地控制补全触发位置。
 *
 * 语义约定：锚点所在方法 = "当前正在输入的方法"（不计入已调用）；
 * 链首到当前方法之间的方法 = 已调用方法（含链首——链首是 Wrapper 操作时）。
 */
class ChainStateDetectorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 链首检测
    // ------------------------------------------------------------------

    fun testDetectsChainHeadAtNewExpression() {
        // 链首：new LambdaQueryWrapper 构造后紧跟方法调用
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .like(DeviceInfoEntity::getName, "test");
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        // 锚点：like 调用（链中第二个方法，eq 已被调用）
        val state = ChainStateDetector.detect(caretAnchor(".like("))
        assertNotNull("应在 Wrapper 链中检测到状态", state)
        assertEquals(WrapperType.LAMBDA_QUERY, state!!.wrapperType)
        // like 是当前方法，之前已调用 eq
        assertTrue("已调用方法应含 eq", state.calledMethods.contains("eq"))
        assertEquals("eq", state.lastMethod)
    }

    fun testDetectsFactoryChainHead() {
        // 链首：Wrappers.<X>lambdaQuery() 工厂调用
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    var w = Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        // 锚点：eq 调用（工厂链中第一个 Wrapper 操作）
        val state = ChainStateDetector.detect(caretAnchor(".eq("))
        assertNotNull("工厂链首应检测到状态", state)
        assertEquals(WrapperType.LAMBDA_QUERY, state!!.wrapperType)
        // eq 是当前方法，工厂调用（lambdaQuery）非 Wrapper 操作，不计入已调用
        assertFalse("工厂调用不应计入已调用", state.calledMethods.contains("lambdaQuery"))
    }

    fun testDetectsServiceChainHead() {
        // 链首：service.lambdaQuery() 服务链
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    service.lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1L)
                        .isNotNull(DeviceInfoEntity::getStationId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        // 锚点：isNotNull（第三个方法，之前已调用 eq）
        val state = ChainStateDetector.detect(caretAnchor(".isNotNull("))
        assertNotNull("服务链应检测到状态", state)
        assertEquals(WrapperType.LAMBDA_QUERY, state!!.wrapperType)
        // isNotNull 是当前方法，之前已调用 eq
        assertTrue("已调用方法应含 eq", state.calledMethods.contains("eq"))
    }

    // ------------------------------------------------------------------
    // 已调用方法收集
    // ------------------------------------------------------------------

    fun testCollectsCalledMethodsInOrder() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1L)
                        .isNotNull(DeviceInfoEntity::getStationId)
                        .orderByDesc(DeviceInfoEntity::getSort);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Integer sort;
                }
            }
            """.trimIndent(),
        )
        // 锚定在 orderByDesc（链尾）
        val state = ChainStateDetector.detect(caretAnchor(".orderByDesc("))
        assertNotNull(state)
        // orderByDesc 之前已调用 eq + isNotNull
        assertTrue("应含 eq", state!!.calledMethods.contains("eq"))
        assertTrue("应含 isNotNull", state.calledMethods.contains("isNotNull"))
        // 顺序：eq 在前，isNotNull 在后
        val eqIdx = state.calledMethods.indexOf("eq")
        val notNullIdx = state.calledMethods.indexOf("isNotNull")
        assertTrue("eq 应在 isNotNull 之前", eqIdx < notNullIdx)
    }

    fun testLastMethodIsMostRecent() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1L)
                        .like(DeviceInfoEntity::getName, "test");
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        // 锚定在 like（当前方法），之前已调用 eq
        val state = ChainStateDetector.detect(caretAnchor(".like("))
        assertNotNull(state)
        assertEquals("eq", state!!.lastMethod)
    }

    fun testFirstMethodHasEmptyCalledMethods() {
        // 锚定在链首方法（eq）时，无已调用方法
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val state = ChainStateDetector.detect(caretAnchor(".eq("))
        assertNotNull(state)
        // eq 是当前方法（用户正在输入），不在已调用列表中
        assertTrue("链首方法时 calledMethods 应为空", state!!.calledMethods.isEmpty())
        assertNull("链首方法时 lastMethod 应为 null", state.lastMethod)
    }

    // ------------------------------------------------------------------
    // 非 Wrapper 链返回 null
    // ------------------------------------------------------------------

    fun testNonWrapperChainReturnsNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                    s.substring(1);
                }
            }
            """.trimIndent(),
        )
        val state = ChainStateDetector.detect(caretAnchor(".substring("))
        assertNull("非 Wrapper 链应返回 null", state)
    }

    fun testPlainMethodChainReturnsNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    StringBuilder sb = new StringBuilder()
                        .append("a")
                        .append("b");
                }
            }
            """.trimIndent(),
        )
        val state = ChainStateDetector.detect(caretAnchor(".append(\"b\")"))
        assertNull("普通 StringBuilder 链应返回 null", state)
    }

    // ------------------------------------------------------------------
    // 当前位置判定
    // ------------------------------------------------------------------

    fun testDetectsPositionInChain() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1L)
                        .like(DeviceInfoEntity::getName, "test");
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        // 在 like 方法调用上（非实参内）
        val state = ChainStateDetector.detect(caretAnchor(".like("))
        assertNotNull(state)
        assertFalse("在方法名位置不应判定为实参内", state!!.isInsideMethodArgs)
        assertEquals("like", state.currentMethodName)
    }

    fun testDetectsInsideMethodArgs() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        // 锚定在 eq 的实参位置（1001L 字面量上）
        val state = ChainStateDetector.detect(caretAnchor("1001L"))
        assertNotNull("在 Wrapper 方法实参内应检测到状态", state)
        assertTrue("在实参内应判定为 isInsideMethodArgs", state!!.isInsideMethodArgs)
        assertEquals("eq", state.currentMethodName)
    }

    // ------------------------------------------------------------------
    // MPJ / 多种 Wrapper 类型识别
    // ------------------------------------------------------------------

    fun testDetectsMpjLambdaWrapper() {
        // MPJ 工厂链首：JoinWrappers.lambda(Class) → MPJ_LAMBDA（独立变量，非嵌套实参）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.extension.toolkit.JoinWrappers;

            public class Demo {
                public void query() {
                    var w = JoinWrappers.lambda(UserEntity.class)
                        .selectAll()
                        .eq(UserEntity::getId, 1L);
                }

                static class UserEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        // 锚定在 eq（MPJ 链中，之前已调用 selectAll）
        val state = ChainStateDetector.detect(caretAnchor(".eq("))
        assertNotNull("MPJ 链应检测到状态", state)
        assertEquals(WrapperType.MPJ_LAMBDA, state!!.wrapperType)
        assertTrue("MPJ 链应含 selectAll", state.calledMethods.contains("selectAll"))
    }

    fun testDetectsUpdateWrapper() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaUpdateWrapper;

            public class Demo {
                public void query() {
                    LambdaUpdateWrapper<DeviceInfoEntity> w = new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .set(DeviceInfoEntity::getId, 1L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        // 锚定在 set（当前方法）
        val state = ChainStateDetector.detect(caretAnchor(".set("))
        assertNotNull("UpdateWrapper 链应检测到状态", state)
        assertEquals(WrapperType.LAMBDA_UPDATE, state!!.wrapperType)
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 在文本中查找标记字符串，返回其末尾位置的 PSI 元素（模拟补全触发位置）。
     * 标记字符串以 "." 开头，确保锚定在方法名前（模拟输入 `.` 后触发补全）。
     * 对于实参内锚点（如 "1001L"），直接定位到该文本位置。
     */
    private fun caretAnchor(marker: String): PsiElement {
        val text = myFixture.file.text
        val markerOffset = text.indexOf(marker)
        assertTrue("测试文件应含标记: $marker", markerOffset >= 0)
        // 取标记起始位置的 PSI 元素（方法名开始处）
        return myFixture.file.findAt(markerOffset + 1)
            ?: error("标记位置无 PSI 元素: $marker")
    }

    /** 在 offset 处查找最深 PSI 元素（兼容 LightPlatform）。 */
    private fun PsiElement.findAt(offset: Int): PsiElement? {
        var current: PsiElement = this
        while (true) {
            val child = current.children.firstOrNull { it.textRange.contains(offset) } ?: return current
            current = child
        }
    }
}
