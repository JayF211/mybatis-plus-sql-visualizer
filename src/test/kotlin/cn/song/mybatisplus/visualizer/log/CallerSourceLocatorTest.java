package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Test;

import java.lang.StackTraceElement;
import java.util.Set;

/**
 * [CallerSourceLocator] 纯逻辑测试（F-44-6）：调用栈 → 用户代码帧定位。
 * 用合成栈验证跳过规则，不依赖运行期线程栈。
 */
public class CallerSourceLocatorTest {

    private static StackTraceElement el(String className, String fileName, int line) {
        return new StackTraceElement(className, "someMethod", fileName, line);
    }

    @Test
    public void 跳过框架帧_取首个应用帧() {
        StackTraceElement[] stack = new StackTraceElement[]{
            el("cn.song.mybatisplus.visualizer.log.SqlLogConsoleBridge", "SqlLogConsoleBridge.kt", 100),
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 200),
            el("com.sun.proxy.$Proxy42", "Proxy.java", 0),
            el("com.example.service.UserService", "UserService.java", 87),
            el("com.example.controller.UserController", "UserController.java", 12),
        };
        // 第一个非框架帧是 UserService（调用 Mapper 的那一行），精确跳转应落到这里
        assertEquals(new Pair<>("com/example/service/UserService.java", 87), CallerSourceLocator.locateFrom(stack, Set.of()));
    }

    @Test
    public void appPackages_限定_只接受指定包() {
        StackTraceElement[] stack = new StackTraceElement[]{
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 1),
            el("com.other.Lib", "Lib.java", 5),
            el("com.example.service.UserService", "UserService.java", 87),
        };
        assertEquals(new Pair<>("com/example/service/UserService.java", 87), CallerSourceLocator.locateFrom(stack, Set.of("com.example.")));
        assertEquals(new Pair<>("com/other/Lib.java", 5), CallerSourceLocator.locateFrom(stack, Set.of("com.other.")));
    }

    @Test
    public void 全为框架帧_返回null() {
        StackTraceElement[] stack = new StackTraceElement[]{
            el("java.lang.Thread", "Thread.java", 1),
            el("com.intellij.openapi.foo.Bar", "Bar.java", 2),
        };
        assertNull(CallerSourceLocator.locateFrom(stack, Set.of()));
    }

    @Test
    public void fileName缺失_跳过该帧取下一个() {
        StackTraceElement[] stack = new StackTraceElement[]{
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", 1),
            el("com.example.service.UserService", null, 87),
            el("com.example.service.UserService2", "UserService2.java", 9),
        };
        assertEquals(new Pair<>("com/example/service/UserService2.java", 9), CallerSourceLocator.locateFrom(stack, Set.of()));
    }

    @Test
    public void 行号为负_跳过该帧() {
        StackTraceElement[] stack = new StackTraceElement[]{
            el("org.apache.ibatis.executor.BaseExecutor", "BaseExecutor.java", -1),
            el("com.example.service.UserService", "UserService.java", 87),
        };
        assertEquals(new Pair<>("com/example/service/UserService.java", 87), CallerSourceLocator.locateFrom(stack, Set.of()));
    }
}
