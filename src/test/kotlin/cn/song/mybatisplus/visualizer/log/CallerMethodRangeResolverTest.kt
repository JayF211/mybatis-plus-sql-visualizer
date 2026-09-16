package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File

/**
 * [CallerMethodRangeResolver] 方法区间解析测试（T-M55-03 静态行号区间近似版）：
 * 方法内行号 / 方法外行号 / 嵌套类 / 重载 / Kotlin 已知边界 / 缺行号守卫。
 */
class CallerMethodRangeResolverTest : BasePlatformTestCase() {

    private val javaSource = """package cn.demo;

public class OrderService {

    public void createOrder() {
        System.out.println("a");
    }

    public void createOrder(int n) {
        System.out.println(n);
    }

    public void cancel() {
        System.out.println("c");
    }

    public static class Inner {
        public void innerMethod() {
            System.out.println("i");
        }
    }
}
"""

    private fun addJava(relativePath: String, content: String) {
        val f = File(project.basePath!!, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        VfsUtil.markDirtyAndRefresh(false, true, true, f)
    }

    private fun resolve(relativePath: String, line: Int): CallerMethodRangeResolver.CallerMethodRange? =
        CallerMethodRangeResolver.resolve(project, relativePath, line)

    fun test方法内行号_解析出区间与命名() {
        addJava("src/main/java/cn/demo/OrderService.java", javaSource)
        val r = resolve("src/main/java/cn/demo/OrderService.java", 6) // createOrder() 方法体行
        assertNotNull(r)
        assertEquals("OrderService", r!!.className)
        assertEquals("createOrder", r.methodName)
        assertEquals("OrderService#createOrder", r.label)
        assertEquals("区间起点 = 方法声明行（1-based）", 5, r.startLine)
        assertEquals("区间终点 = 方法收尾行（1-based 含）", 7, r.endLine)
        assertTrue("调用点行号落在区间内", 6 in r.startLine..r.endLine)
    }

    fun test重载方法_按行号取所在重载() {
        addJava("src/main/java/cn/demo/OrderService.java", javaSource)
        val r = resolve("src/main/java/cn/demo/OrderService.java", 10) // createOrder(int) 方法体行
        assertNotNull(r)
        assertEquals("createOrder", r!!.methodName)
        assertEquals("重载区间从第二处声明起（含）", 9, r.startLine)
        assertEquals(11, r.endLine)
    }

    fun test嵌套类方法_取内层方法区间() {
        addJava("src/main/java/cn/demo/OrderService.java", javaSource)
        val r = resolve("src/main/java/cn/demo/OrderService.java", 19) // Inner.innerMethod 方法体行
        assertNotNull(r)
        assertEquals("innerMethod", r!!.methodName)
        assertEquals("嵌套类展示名取具名内层类", "Inner", r.className)
        assertEquals("Inner#innerMethod", r.label)
        assertTrue(19 in r.startLine..r.endLine)
    }

    fun test方法外行号_返回null() {
        addJava("src/main/java/cn/demo/OrderService.java", javaSource)
        // 类声明行 / 字段区 / 类收尾行：不在任何 PsiMethod 区间内
        assertNull(resolve("src/main/java/cn/demo/OrderService.java", 3))
        assertNull(resolve("src/main/java/cn/demo/OrderService.java", 25))
    }

    fun testKotlin调用方_已知边界返回null() {
        addJava("src/main/kotlin/cn/demo/OrderService.kt", "package cn.demo\nfun main() {}\n")
        assertNull("首批仅 Java 调用方（Kotlin 记已知边界）", resolve("src/main/kotlin/cn/demo/OrderService.kt", 2))
    }

    fun test守卫_缺路径缺行号_文件不存在() {
        assertNull("sourceFile null", CallerMethodRangeResolver.resolve(project, null, 5))
        assertNull("sourceLine 0（未知行）", CallerMethodRangeResolver.resolve(project, "a/A.java", 0))
        assertNull("文件不存在", resolve("src/main/java/cn/demo/Missing.java", 5))
    }
}
