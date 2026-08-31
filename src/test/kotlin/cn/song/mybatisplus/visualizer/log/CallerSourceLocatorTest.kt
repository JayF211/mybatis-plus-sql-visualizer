package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.StackTraceElement

/**
 * [CallerSourceLocator] 纯逻辑测试（F-44-6）：调用栈 → 用户代码帧定位。
 * 用合成栈验证跳过规则，不依赖运行期线程栈。
 */
class CallerSourceLocatorTest {

    private fun el(className: String, fileName: String?, line: Int) =
        StackTraceElement(className, "someMethod", fileName, line)

    @Test
    fun 跳过框架帧_取首个应用帧() {
        val stack = arrayOf(
            el("cn.song.mybatisplus.visualizer.log.SqlLogConsoleBridge", "SqlLogConsoleBridge.kt", 100),
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 200),
            el("com.sun.proxy.\$Proxy42", "Proxy.java", 0),
            el("com.example.service.UserService", "UserService.java", 87),
            el("com.example.controller.UserController", "UserController.java", 12),
        )
        // 第一个非框架帧是 UserService（调用 Mapper 的那一行），精确跳转应落到这里
        assertEquals("com/example/service/UserService.java" to 87, CallerSourceLocator.locateFrom(stack))
    }

    @Test
    fun appPackages_限定_只接受指定包() {
        val stack = arrayOf(
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 1),
            el("com.other.Lib", "Lib.java", 5),
            el("com.example.service.UserService", "UserService.java", 87),
        )
        assertEquals("com/example/service/UserService.java" to 87, CallerSourceLocator.locateFrom(stack, setOf("com.example.")))
        assertEquals("com/other/Lib.java" to 5, CallerSourceLocator.locateFrom(stack, setOf("com.other.")))
    }

    @Test
    fun 全为框架帧_返回null() {
        val stack = arrayOf(
            el("java.lang.Thread", "Thread.java", 1),
            el("com.intellij.openapi.foo.Bar", "Bar.java", 2),
        )
        assertNull(CallerSourceLocator.locateFrom(stack))
    }

    @Test
    fun fileName缺失_跳过该帧取下一个() {
        val stack = arrayOf(
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 1),
            el("com.example.service.UserService", null, 87),
            el("com.example.service.UserService2", "UserService2.java", 9),
        )
        assertEquals("com/example/service/UserService2.java" to 9, CallerSourceLocator.locateFrom(stack))
    }

    @Test
    fun 行号为负_跳过该帧() {
        val stack = arrayOf(
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", -1),
            el("com.example.service.UserService", "UserService.java", 87),
        )
        assertEquals("com/example/service/UserService.java" to 87, CallerSourceLocator.locateFrom(stack))
    }
}
