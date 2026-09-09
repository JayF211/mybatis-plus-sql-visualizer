package cn.song.mybatisplus.visualizer.log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * ConsoleView.Filter 代理处理器（T-M31-03，纯逻辑，不依赖平台类）：
 * {@link java.lang.reflect.Proxy} 需实现 `com.intellij.execution.ui.ConsoleView$Filter` 接口
 * （该接口不在插件编译 classpath——IPGP 发行版裁剪 execution 模块，运行期由真实 IDE 提供），
 * 经本处理器按方法名分发：
 * - `applyFilter(String line, int entireLength)` → 行文本 + 行尾偏移转发 {@code onLine}，返回 null（不修改 console 输出）
 * - 反射代理通用方法（toString/hashCode/equals）按约定处理
 *
 * <p>T-PreviewFlash：onLine 扩为 {@code (line, entireLength)}——entireLength 是行在整篇文档中的行尾偏移，
 * {@link ConsoleReplayGuard} 用它做流式水位判定与重放识别（平台 rehighlight 会全文档重跑 filter）。
 */
public class LogFilterProxyHandler implements InvocationHandler {

    /** 行回调：line 为行文本，entireLength 为该行在整篇文档中的行尾偏移。 */
    public interface LineSink {
        void onLine(String line, int entireLength);
    }

    private final LineSink onLine;

    public LogFilterProxyHandler(LineSink onLine) {
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
                int endOffset = (args != null && args.length > 1 && args[1] instanceof Integer i) ? i : 0;
                onLine.onLine(line, endOffset);
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
