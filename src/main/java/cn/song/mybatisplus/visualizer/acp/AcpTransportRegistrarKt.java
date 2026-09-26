// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpTransportRegistrar.kt（顶层函数 transportHandler 门面）
package cn.song.mybatisplus.visualizer.acp;

import java.lang.reflect.InvocationHandler;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * master 同文件顶层函数 {@code transportHandler} 的 Kt 门面（本仓顶层函数互操作惯例）。
 * {@code internal} 按本仓惯例升 public。
 */
public final class AcpTransportRegistrarKt {

    private AcpTransportRegistrarKt() {}

    /**
     * getTransport 的分发体（策略 (a)，与原 {@code AcpIdeaMcpTransportProviderImpl} 逐语义一致）：
     * {@code agentId.fullId == "acp.registry.claude-acp"} → 平台 {@code AcpIdeaMcpTransport.SSE}
     * （经 {@code sseProvider} 反射解析），其余 → null 不表态。agent 实参走鸭子形态（反射调
     * {@code getFullId()}），不引用平台类型。
     *
     * <p>移植注记：Kotlin 函数类型 {@code () -> Any?} 以 {@link Supplier} 承载——提供者体内的受检异常
     * 由调用方经 sneakyThrow 透传（InvocationHandler.invoke 声明 {@code throws Throwable}，运行时等价）。
     * {@code fullId == CLAUDE_ACP_ID} 的 Kotlin {@code ==} 是 equals 语义（且 null 安全），以非空常量作
     * 接收者的 {@code equals} 逐字等价。</p>
     */
    public static InvocationHandler transportHandler(Supplier<Object> sseProvider) {
        return (proxy, method, args) -> {
            switch (method.getName()) {
                case "getTransport": {
                    Object fullId = null;
                    if (args != null && args.length > 0 && args[0] != null) {
                        try {
                            fullId = args[0].getClass().getMethod("getFullId").invoke(args[0]);
                        } catch (Throwable ignored) {
                            // runCatching{...}.getOrNull() 语义
                        }
                    }
                    return Objects.equals(fullId, AcpTransportEpRegistrar.CLAUDE_ACP_ID) ? sseProvider.get() : null;
                }
                case "toString":
                    return "AcpIdeaMcpTransportProviderImpl(proxy)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && args.length > 0 && args[0] == proxy;
                default:
                    return null;
            }
        };
    }
}
