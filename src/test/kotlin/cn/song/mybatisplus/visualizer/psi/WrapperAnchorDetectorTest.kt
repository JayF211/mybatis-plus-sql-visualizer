package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiField
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

/**
 * WrapperAnchorDetector 单测（M10-01，gutter 锚点判定）。
 *
 * 在 LightPlatform 虚拟项目中用真实 Java PSI 验证：
 *  - 链式 / 分步 / 内联三种形态的锚点识别
 *  - 同行去重（变量声明 + 构造只标变量声明）
 *  - 非 Wrapper 变量 / 构造 / 普通叶子零成本负例
 */
class WrapperAnchorDetectorTest : BasePlatformTestCase() {

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    /** 文件内第一个局部变量。 */
    private fun firstVariable(): PsiLocalVariable =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!

    /** 文件内第一个 new 表达式。 */
    private fun firstNewExpression(): PsiNewExpression =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiNewExpression::class.java)!!

    fun testChainStyleAnchorsOnVariableDeclaration() {
        // 链式：变量初始化即 wrapper 构造（同行）→ 只标变量声明，new 关键字去重
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
        val variable = firstVariable()
        assertSame(
            "变量名 identifier 应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull("wrapper 构造应有 new 关键字", newKeyword)
        assertNull("new 关键字应被去重（与变量声明同行）", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testStepByStepAnchorsOnVariableDeclaration() {
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>();
                    w.eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(variable.nameIdentifier, WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
    }

    fun testInlineNewExpressionAnchorsOnNewKeyword() {
        // 内联：list(new MPJLambdaWrapper<X>().select(...)) 无变量 → new 关键字是锚点
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
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertSame("内联构造的 new 关键字应为锚点", newKeyword, WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testNonWrapperVariableIsNotAnchor() {
        configure(
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """,
        )
        val variable = firstVariable()
        assertNull(WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
    }

    fun testNonWrapperNewExpressionIsNotAnchor() {
        configure(
            """
            import java.util.ArrayList;

            public class Demo {
                public void query() {
                    ArrayList<String> list = new ArrayList<String>();
                }
            }
            """,
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertNull("非 Wrapper 构造不应是锚点", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testQualifiedEntityTypeAnchors() {
        // 回归：真实项目实体类带包名 + Wrappers.<T>lambdaQuery() 工厂写法。
        // detectWrapperType 曾用 canonicalText.substringAfterLast('.') 提取类名，
        // 泛型实参带包名时最后一个 '.' 落在泛型参数内导致判定失败（无图标）。
        // 用全限定泛型参数文本（canonicalText 含 '.'），无需真实实体类/import。
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void page() {
                    LambdaQueryWrapper<com.biz.entity.BikeDeviceProductInfoEntity> wrapper = Wrappers.<com.biz.entity.BikeDeviceProductInfoEntity>lambdaQuery()
                        .like(com.biz.entity.BikeDeviceProductInfoEntity::getName, "x");
                }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(
            "带包名实体 + 工厂写法的变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
    }

    fun testInlineWrappersFactoryCallAnchors() {
        // 内联 Wrappers.<T>lambdaQuery()（无变量、无 new）：工厂方法名 identifier 是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    list(Wrappers.<DeviceEntity>lambdaQuery().eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        val nameId = factoryCall.methodExpression.referenceNameElement!!
        assertSame("内联工厂调用的方法名应为锚点", nameId, WrapperAnchorDetector.anchorElement(nameId))
    }

    fun testInlineWrappersFactoryCallDedupWithVariable() {
        // 变量初始化即工厂调用（`w = Wrappers.lambdaQuery()...`）：只标变量声明，工厂方法名去重
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = Wrappers.<DeviceEntity>lambdaQuery().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame("变量名应为锚点", variable.nameIdentifier, WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertNull(
            "工厂方法名应与变量声明同行去重",
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testServiceChainCallAnchors() {
        // T-M15-01：service.lambdaQuery() / service.lambdaUpdate() 服务链（qualifier 任意引用，语法级匹配）
        configure(
            """
            public class Demo {
                public void query() {
                    service.lambdaQuery().eq(DeviceEntity::getId, 1L).one();
                }
            }
            """,
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "服务链 lambdaQuery 方法名应为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testServiceConsumerCallIsNotAnchor() {
        // 消费调用（service.update(wrapper) / service.count(wrapper)）不是链起点 → 不是锚点（仅 lambdaQuery/lambdaUpdate 放行）
        configure(
            """
            public class Demo {
                public void query() {
                    service.update(wrapper);
                    service.count(wrapper);
                }
            }
            """,
        )
        for (name in listOf("update", "count")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertNull(
                "消费调用 $name 不应是锚点",
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testBaseTypeVariableAnchors() {
        // 变量用抽象基类型声明（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`）→ 变量名仍是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.Wrapper;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Wrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(
            "基类型 Wrapper<X> 声明的变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertNull("基类型声明行 new 应去重（变量声明已标）", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testMethodParameterWrapperAnchors() {
        // wrapper 是方法参数（`void del(LambdaQueryWrapper<X> wrapper)`）→ 参数名应是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void del(LambdaQueryWrapper<DeviceEntity> wrapper) {
                    wrapper.eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val param = PsiTreeUtil.findChildOfType(myFixture.file, PsiParameter::class.java)!!
        assertSame(
            "方法参数名应为锚点",
            param.nameIdentifier,
            WrapperAnchorDetector.anchorElement(param.nameIdentifier),
        )
    }

    fun testBatchOperationCallAnchors() {
        // 批量操作调用（`service.updateBatchById(list)`）→ 方法名应是锚点
        configure(
            """
            import java.util.List;

            public class Demo {
                public void update(List<DeviceEntity> list) {
                    service.updateBatchById(list);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "updateBatchById" }
        assertSame(
            "批量操作方法名应为锚点",
            call.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
    }

    fun testFieldDeclarationIsAnchor() {        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                private LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>();

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val field = PsiTreeUtil.findChildOfType(myFixture.file, PsiField::class.java)!!
        assertSame("字段声明名也应识别为锚点", field.nameIdentifier, WrapperAnchorDetector.anchorElement(field.nameIdentifier))
    }

    fun testOrdinaryLeafIsNotAnchor() {
        // 普通叶子（字符串字面量）→ null，零成本负例
        configure(
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """,
        )
        val literal = PsiTreeUtil.findChildOfType(myFixture.file, PsiLiteralExpression::class.java)!!
        assertNull(WrapperAnchorDetector.anchorElement(literal))
    }
}
