package cn.song.mybatisplus.visualizer.log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * ConsoleView.Filter 代理处理器（T-M31-03，纯逻辑，不依赖平台类）：
 * {@link java.lang.reflect.Proxy} 需实现 `com.intellij.execution.ui.ConsoleView$Filter` 接口
 * （该接口不在插件编译 classpath——IPGP 发行版裁剪 execution 模块，运行期由真实 IDE 提供），
 * 经本处理器按方法名分发：
 * - `applyFilter(String line, int entireLength)` → 行文本转发 {@code onLine}，返回 null（不修改 console 输出）
 * - 反射代理通用方法（toString/hashCode/equals）按约定处理
 */
public class LogFilterProxyHandler implements InvocationHandler {

    private final Consumer<String> onLine;

    public LogFilterProxyHandler(Consumer<String> onLine) {
        this.onLine = onLine;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "applyFilter" -> {
                String line = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
                if (line == null) {
                    return null;
                }
                onLine.accept(line);
                return null;
            }
            case "toString" -> {
                return "MyBatisLogConsoleFilter";
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            default -> {
                return null;
            }
        }
    }
}
