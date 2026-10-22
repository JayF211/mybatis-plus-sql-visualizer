package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PresetMethodScope：声明作用域的提取口径。
 *
 * 关键判据是「同一文件里两个方法必须是两个作用域」——报障形态正是两个方法写同一段 HQL，
 * 旧键区分不开。反向验证：把 [PreviewOriginScope.of] 的方法签名段摘掉（只留文件路径），
 * 同文件两方法会退化成同一个 scope，本类首个用例即红。
 */
class PreviewOriginScopeTest : BasePlatformTestCase() {

    private val source = """
        public class ProbeService {
            public Object first(String warehouseId) {
                String hql = "from WmsInventoryEntity inv where inv.warehouseId = :warehouseId";
                return hql;
            }

            public Object second(String warehouseId) {
                StringBuffer sb = new StringBuffer();
                sb.append("from WmsInventoryEntity inv where inv.warehouseId = :warehouseId");
                return sb.toString();
            }

            public Object first(Object other) {
                return other;
            }
        }
    """.trimIndent()

    private fun methods(): List<PsiMethod> {
        val file = myFixture.configureByText("ProbeService.java", source)
        return PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).toList()
    }

    fun testSameFileTwoMethodsGetDifferentScope() {
        val methods = methods()
        assertEquals("语料应有三个方法", 3, methods.size)
        val first = PreviewOriginScope.of(methods[0], project)
        val second = PreviewOriginScope.of(methods[1], project)

        assertTrue("作用域不应为空", first.isNotEmpty())
        assertTrue("作用域应是 16 位 hex 短哈希", first.matches(Regex("[0-9a-f]{16}")))
        assertDifferent("同一文件的两个方法必须不同作用域", first, second)
        assertEquals("同一方法重复取值应稳定", first, PreviewOriginScope.of(methods[0], project))
    }

    fun testOverloadsAreDistinguished() {
        val methods = methods()
        val overloads = methods.filter { it.name == "first" }
        assertEquals("语料里应有两个 first 重载", 2, overloads.size)
        assertEquals(
            "两个重载的参数类型应不同",
            2,
            overloads.map { it.parameterList.parameters[0].type.canonicalText }.toSet().size,
        )
        assertDifferent(
            "同名重载方法应可区分",
            PreviewOriginScope.of(overloads[0], project),
            PreviewOriginScope.of(overloads[1], project),
        )
    }

    fun testLabelIsReadable() {
        val methods = methods()
        val method = methods[0]
        // 不硬编码 java.lang.String：light fixture 未挂 JDK 时 canonicalText 可能是简单名。
        // 可读标签的契约是「与方法签名同源」，不是某个具体类型文本。
        val paramType = method.parameterList.parameters[0].type.canonicalText
        val label = PreviewOriginScope.label(method, project)
        assertTrue("可读标签应含方法名与参数类型（实际：$label）", label.contains("first($paramType)"))
        assertTrue("可读标签应含文件名（实际：$label）", label.contains("ProbeService.java"))
    }

    fun testNoMethodContextIsEmpty() {
        assertEquals("无方法上下文必须为空串（日志/历史双击链路）", "", PreviewOriginScope.of(null, project))
        assertEquals("", PreviewOriginScope.label(null, project))
    }

    private fun assertDifferent(message: String, unexpected: String, actual: String) {
        if (unexpected == actual) fail(message + "（实际相同：$actual）")
    }
}
