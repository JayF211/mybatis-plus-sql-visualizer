package cn.song.mybatisplus.visualizer.log

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * ConsoleView.Filter 代理处理器（T-M31-03，纯逻辑，不依赖平台类）：
 * [java.lang.reflect.Proxy] 需实现 `com.intellij.execution.ui.ConsoleView$Filter` 接口
 * （该接口不在插件编译 classpath——IPGP 发行版裁剪 execution 模块，运行期由真实 IDE 提供），
 * 经本处理器按方法名分发：
 * - `applyFilter(String line, int entireLength)` → 行文本转发 [onLine]，返回 null（不修改 console 输出）
 * - 反射代理通用方法（toString/hashCode/equals）按约定处理
 */
class LogFilterProxyHandler(
    private val onLine: (String) -> Unit,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            "applyFilter" -> {
                val line = args?.getOrNull(0) as? String ?: return null
                onLine(line)
                null
            }
            "toString" -> "MyBatisLogConsoleFilter"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }
    }
}
