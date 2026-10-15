package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * KtWrapperDetector 单测（F-4，Kotlin Wrapper 链检测 MVP）。
 *
 * 在 LightPlatform 虚拟项目中用真实 Kotlin PSI 验证：
 *  - ktQuery() / ktUpdate() 扩展函数链检测
 *  - LambdaQueryWrapper 构造链检测
 *  - 非 Wrapper 调用返回空
 *
 * MVP 策略：按方法名模式匹配（与 Java 一致），不做深度 PSI resolve。
 */
class KtWrapperDetectorTest : BasePlatformTestCase() {

    private fun configureKt(code: String) {
        myFixture.configureByText("Demo.kt", code.trimIndent())
    }

    fun testDetectKtQueryChain() {
        configureKt(
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").like(User::email, "@").list()
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertFalse("应检测到 ktQuery 链", results.isEmpty())
        val chain = results.first()
        assertEquals(WrapperType.LAMBDA_QUERY, chain.wrapperType)
        // 方法调用顺序：eq → like（list 是终结点，不入链）
        val methodNames = chain.calls.map { it.methodName }
        assertTrue("应包含 eq 调用", methodNames.contains("eq"))
        assertTrue("应包含 like 调用", methodNames.contains("like"))
    }

    fun testDetectKtUpdateChain() {
        configureKt(
            """
            fun update() {
                service.ktUpdate().set(User::name, "y").eq(User::id, 1).update()
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertFalse("应检测到 ktUpdate 链", results.isEmpty())
        val chain = results.first()
        assertEquals(WrapperType.LAMBDA_UPDATE, chain.wrapperType)
        val methodNames = chain.calls.map { it.methodName }
        assertTrue("应包含 set 调用", methodNames.contains("set"))
        assertTrue("应包含 eq 调用", methodNames.contains("eq"))
    }

    fun testDetectLambdaQueryWrapperConstructor() {
        configureKt(
            """
            fun query() {
                LambdaQueryWrapper<User>().eq(User::name, "x").list()
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertFalse("应检测到 LambdaQueryWrapper 构造链", results.isEmpty())
        val chain = results.first()
        assertEquals(WrapperType.LAMBDA_QUERY, chain.wrapperType)
        val methodNames = chain.calls.map { it.methodName }
        assertTrue("应包含 eq 调用", methodNames.contains("eq"))
    }

    fun testNonWrapperCallReturnsEmpty() {
        configureKt(
            """
            fun normal() {
                println("hello")
                val list = listOf(1, 2, 3)
                list.map { it * 2 }
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertTrue("非 Wrapper 调用应返回空", results.isEmpty())
    }

    fun testKtQueryChainWithEntityClass() {
        configureKt(
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").list()
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertFalse("应检测到链", results.isEmpty())
        val chain = results.first()
        // MVP 阶段可能无法解析实体类（需要深度 PSI resolve），但至少应检测到链
        assertNotNull("chain 不应为 null", chain)
        assertEquals(WrapperType.LAMBDA_QUERY, chain.wrapperType)
    }

    fun testMultipleChainsInSameFile() {
        configureKt(
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").list()
                service.ktUpdate().set(User::name, "y").eq(User::id, 1).update()
            }
            """,
        )
        val results = KtWrapperDetector.detect(myFixture.file)
        assertEquals("应检测到 2 条链", 2, results.size)
        val types = results.map { it.wrapperType }.toSet()
        assertTrue("应包含 LAMBDA_QUERY", types.contains(WrapperType.LAMBDA_QUERY))
        assertTrue("应包含 LAMBDA_UPDATE", types.contains(WrapperType.LAMBDA_UPDATE))
    }

    fun test主动入口只认光标或选区命中的KotlinWrapper行() {
        configureKt(
            """
            fun query() {
                println("not wrapper")
                service.ktQuery().eq(User::name, "x").list()
                service.ktUpdate().set(User::name, "y").update()
            }
            """,
        )
        val text = myFixture.file.text
        val plain = text.indexOf("println")
        val query = text.indexOf("ktQuery")
        val update = text.indexOf("ktUpdate")
        assertFalse(KtWrapperDetector.detectAt(myFixture.file, plain))
        assertTrue(KtWrapperDetector.detectAt(myFixture.file, query))
        assertTrue(KtWrapperDetector.detectInRange(myFixture.file, query, update + "ktUpdate".length))
        assertFalse(KtWrapperDetector.detectInRange(myFixture.file, plain, plain + "println".length))
    }

    fun test两条Kotlin链未能生成表列时不亮不可用图标也不冒认首链() {
        configureKt(
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").list()
                service.ktUpdate().set(User::name, "y").eq(User::id, 1).update()
            }
            """,
        )
        val file = myFixture.file
        assertEquals(2, KtWrapperDetector.detect(file).size)
        for (method in listOf("ktQuery", "ktUpdate")) {
            val anchor = file.findElementAt(file.text.indexOf(method))!!
            assertNull("$method 尚无可信表和实参，不得亮可点击图标", WrapperAnchorDetector.anchorElement(anchor))
            assertNull("$method 不得回退认领文件里的第一条链", WrapperLocator.locateWithTable(anchor))
        }
    }
}
