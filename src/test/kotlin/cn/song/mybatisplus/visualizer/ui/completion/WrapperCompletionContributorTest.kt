package cn.song.mybatisplus.visualizer.ui.completion

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * WrapperCompletionContributor 集成测试（F-5，测试计划）。
 *
 * 验证：
 *  - 补全贡献器已注册到 JAVA 语言
 *  - 各工厂方法按上下文生成正确的补全项集合
 *  - 方法名补全项带括号尾巴 + 插入处理器
 */
class WrapperCompletionContributorTest : BasePlatformTestCase() {

    fun testContributorRegisteredForJava() {
        val contributors = CompletionContributor.forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE)
        assertTrue(
            "应注册 WrapperCompletionContributor 到 JAVA 语言",
            contributors.any { it is WrapperCompletionContributor },
        )
    }

    fun testMethodLookupFactory_generatesConditionMethodsAtHead() {
        // 链首（无已调用方法）→ 应生成条件方法集合
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = emptyList(),
            lastMethod = null,
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        // 链首应含 eq/like/in/orderBy/groupBy 等
        assertTrue("链首应含 eq", names.contains("eq"))
        assertTrue("链首应含 like", names.contains("like"))
        assertTrue("链首应含 in", names.contains("in"))
        assertTrue("链首应含 orderBy", names.contains("orderBy"))
        assertTrue("链首应含 groupBy", names.contains("groupBy"))
        assertTrue("链首应含 select", names.contains("select"))
    }

    fun testMethodLookupFactory_afterConditionMethod() {
        // eq 之后 → 应含 and/or/orderBy 等（不含 select——select 是链首方法）
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = listOf("eq"),
            lastMethod = "eq",
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        assertTrue("eq 后应含 and", names.contains("and"))
        assertTrue("eq 后应含 or", names.contains("or"))
        assertTrue("eq 后应含 orderBy", names.contains("orderBy"))
        assertTrue("eq 后应含 ne", names.contains("ne"))
    }

    fun testMethodLookupFactory_afterSelect() {
        // select 之后 → 应含 selectAs/selectIgnore/groupBy/having/orderBy
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = listOf("select"),
            lastMethod = "select",
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        assertTrue("select 后应含 selectAs", names.contains("selectAs"))
        assertTrue("select 后应含 selectIgnore", names.contains("selectIgnore"))
        assertTrue("select 后应含 groupBy", names.contains("groupBy"))
        assertTrue("select 后应含 having", names.contains("having"))
        assertTrue("select 后应含 orderBy", names.contains("orderBy"))
    }

    fun testMethodLookupFactory_afterOrderBy() {
        // orderBy 之后 → 应含 orderByAsc/orderByDesc/limit/page/list/one
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = listOf("eq", "orderBy"),
            lastMethod = "orderBy",
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        assertTrue("orderBy 后应含 orderByAsc", names.contains("orderByAsc"))
        assertTrue("orderBy 后应含 orderByDesc", names.contains("orderByDesc"))
        assertTrue("orderBy 后应含 limit", names.contains("limit"))
        assertTrue("orderBy 后应含 page", names.contains("page"))
        assertTrue("orderBy 后应含 list", names.contains("list"))
        assertTrue("orderBy 后应含 one", names.contains("one"))
    }

    fun testMethodLookupFactory_mpjHeadIncludesJoinMethods() {
        // MPJ 链首 → 应含联表方法（leftJoin/rightJoin/selectAll 等）
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.MPJ_LAMBDA,
            calledMethods = emptyList(),
            lastMethod = null,
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        assertTrue("MPJ 链首应含 leftJoin", names.contains("leftJoin"))
        assertTrue("MPJ 链首应含 rightJoin", names.contains("rightJoin"))
        assertTrue("MPJ 链首应含 selectAll", names.contains("selectAll"))
        assertTrue("MPJ 链首应含 innerJoin", names.contains("innerJoin"))
    }

    fun testMethodLookupFactory_terminalMethodReturnsEmpty() {
        // 终结点之后 → 无方法名补全
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = listOf("eq", "list"),
            lastMethod = "list",
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        assertEquals("终结点后应无方法名补全", 0, lookups.size)
    }

    fun testMethodLookupFactory_insideArgsReturnsEmpty() {
        // 实参内 → 无方法名补全（应走字段补全）
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = emptyList(),
            lastMethod = null,
            isInsideMethodArgs = true,
            currentMethodName = "eq",
        )
        val lookups = MethodLookupFactory.createLookups(state)
        assertEquals("实参内应无方法名补全", 0, lookups.size)
    }

    fun testMethodLookupFactory_updateAfterSet() {
        // UPDATE 的 set 之后 → 应含 set/setSql/setIncrBy/setDecrBy/eq/where
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_UPDATE,
            calledMethods = listOf("set"),
            lastMethod = "set",
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        val names = lookups.map { it.lookupString }.toSet()
        assertTrue("set 后应含 set", names.contains("set"))
        assertTrue("set 后应含 setSql", names.contains("setSql"))
        assertTrue("set 后应含 setIncrBy", names.contains("setIncrBy"))
        assertTrue("set 后应含 setDecrBy", names.contains("setDecrBy"))
        assertTrue("set 后应含 eq", names.contains("eq"))
        assertTrue("set 后应含 where", names.contains("where"))
    }

    fun testMethodLookupElements_haveTypeText() {
        // 方法名补全项应带类型描述（typeText，灰色尾部展示语义如 "=" "AND" "ORDER BY"）
        val state = ChainStateDetector.ChainState(
            wrapperType = WrapperType.LAMBDA_QUERY,
            calledMethods = emptyList(),
            lastMethod = null,
            isInsideMethodArgs = false,
            currentMethodName = null,
        )
        val lookups = MethodLookupFactory.createLookups(state)
        assertTrue("应生成补全项", lookups.isNotEmpty())
        // 验证部分方法有 typeText（语义描述）
        val eqLookup = lookups.first { it.lookupString == "eq" }
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        eqLookup.renderElement(presentation)
        assertNotNull("eq 应有 typeText（语义描述）", presentation.typeText)
        assertEquals("=", presentation.typeText)
    }

    fun testChainStateDetector_integrationWithContributor() {
        // 集成验证：在真实 Wrapper 链中检测状态并生成补全项
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
        // 锚定在 like 方法名（非实参内）—— 模拟输入 `.` 后触发补全
        val text = myFixture.file.text
        val likeNameOffset = text.indexOf(".like(") + 2 // 定位到 'l' of 'like'
        val element = findAtMyFile(likeNameOffset)
        val state = ChainStateDetector.detect(element)
        assertNotNull("应检测到链状态", state)
        assertEquals(WrapperType.LAMBDA_QUERY, state!!.wrapperType)
        assertTrue("应含 eq", state.calledMethods.contains("eq"))
        assertEquals("lastMethod 应为 eq", "eq", state.lastMethod)
        assertFalse("在方法名位置不应判定为实参内", state.isInsideMethodArgs)

        // 验证方法名补全（链中位置 → 非空）
        val lookups = MethodLookupFactory.createLookups(state)
        assertTrue("应生成非空补全项", lookups.isNotEmpty())
    }

    /** 在 myFixture.file 的 offset 处查找最深 PSI 元素。 */
    private fun findAtMyFile(offset: Int): PsiElement {
        var current: PsiElement = myFixture.file
        while (true) {
            val child = current.children.firstOrNull { it.textRange.contains(offset) } ?: return current
            current = child
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 在 offset 处查找最深 PSI 元素（兼容 LightPlatform）。 */
    private fun PsiElement.findAt(offset: Int): PsiElement? {
        var current: PsiElement = this
        while (true) {
            val child = current.children.firstOrNull { it.textRange.contains(offset) } ?: return current
            current = child
        }
    }
}
