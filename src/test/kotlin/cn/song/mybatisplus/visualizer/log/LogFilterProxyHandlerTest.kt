package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ConsoleView.Filter 代理处理器单元测试（T-M31-03）。
 *
 * 用测试自建的 FakeFilter（签名对齐 `com.intellij.execution.ui.ConsoleView$Filter.applyFilter`）
 * 验证分发逻辑——真实 Filter 接口不在编译 classpath，运行期由 [SqlLogConsoleBridge] 反射加载。
 */
class LogFilterProxyHandlerTest {

    /** 签名与真实 ConsoleView.Filter.applyFilter(String, int) 一致。 */
    private interface FakeFilter {
        fun applyFilter(line: String, entireLength: Int): Any?
    }

    private fun proxyOf(onLine: (String) -> Unit): FakeFilter =
        Proxy.newProxyInstance(
            FakeFilter::class.java.classLoader,
            arrayOf(FakeFilter::class.java),
            LogFilterProxyHandler(onLine),
        ) as FakeFilter

    @Test
    fun `applyFilter 转发行文本并返回 null（不修改输出）`() {
        val lines = mutableListOf<String>()
        val filter = proxyOf { lines += it }
        assertNull(filter.applyFilter("==>  Preparing: SELECT 1", 30))
        assertNull(filter.applyFilter("==> Parameters: 1(Long)", 27))
        assertEquals(listOf("==>  Preparing: SELECT 1", "==> Parameters: 1(Long)"), lines)
    }

    @Test
    fun `代理通用方法按约定处理`() {
        val filter = proxyOf { }
        assertTrue(filter.toString().contains("MyBatisLog"))
        assertTrue(filter.hashCode() != 0)
        assertEquals(filter, filter)
    }
}
