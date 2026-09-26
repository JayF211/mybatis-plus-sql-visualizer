// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpTransportRegistrar.kt（object AcpTransportEpRegistrar 声明）
package cn.song.mybatisplus.visualizer.acp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-AcpMcpTransport 的程序化注册器（master 同文件 {@code object AcpTransportEpRegistrar} 的 Java 承载，
 * {@code internal} 按本仓惯例升 public）：全反射 + 动态代理挂接 ml-llm 的 ideaMcpTransportProvider EP，
 * 字节码零 ml-llm ACP 类型引用（背景与加载纪律见 {@link AcpTransportRegistrar} KDoc）。
 */
public final class AcpTransportEpRegistrar {

    public static final AcpTransportEpRegistrar INSTANCE = new AcpTransportEpRegistrar();
    private AcpTransportEpRegistrar() {}

    private static final Logger LOG = Logger.getInstance(AcpTransportEpRegistrar.class);

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    /**
     * 与原 impl 的 CLAUDE_ACP_ID 同源：ml-llm 常量池实证（AcpBundledAgentUtils.CLAUDE_ACP_ID）。
     *
     * <p>字面量取值有两处权威实证：{@code AcpBundledAgentUtils.CLAUDE_ACP_ID = "acp.registry.claude-acp"}
     * （ml-llm 的 {@code intellij.ml.llm.agents.acp.jar} 常量池，ACC_STATIC|ACC_FINAL 字符串常量）；
     * {@code AcpRegistryAgentId.getFullId()} = 常量前缀 {@code "acp.registry."} + rawId（字节码实证），
     * 与 {@code AcpAgentId.REGISTRY_PREFIX} 一致。故意内联字面量而不引 {@code AcpBundledAgentUtils}：
     * 少一个桩类型即少一处版本漂移面，而该常量的取值已由上述两处实证钉死。</p>
     */
    public static final String CLAUDE_ACP_ID = "acp.registry.claude-acp";

    private static final String PROVIDER_IFACE_FQN = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider";
    private static final String TRANSPORT_ENUM_FQN = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport";
    private static final String EP_NAME = "com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider";

    public static void registerOnce() {
        if (registered.get()) return;
        Class<?> iface;
        try {
            iface = Class.forName(PROVIDER_IFACE_FQN);
        } catch (Throwable ignored) {
            return; // runCatching{...}.getOrNull() ?: return
        }
        try {
            registerProxy(iface);
            registered.set(true);
            LOG.info("T-AMT: sqlv SSE transport provider registered onto ideaMcpTransportProvider EP (proxy form)");
        } catch (Throwable t) {
            LOG.warn("T-AMT: ideaMcpTransportProvider EP 注册失败（ACP 会话回落平台默认传输，其余功能不受影响）", t);
        }
    }

    private static void registerProxy(Class<?> iface) {
        ClassLoader loader = AcpTransportEpRegistrar.class.getClassLoader();
        InvocationHandler handler = AcpTransportRegistrarKt.transportHandler(() -> {
            try {
                return Class.forName(TRANSPORT_ENUM_FQN).getField("SSE").get(null);
            } catch (Throwable t) {
                // Kotlin `runCatching{...}.getOrElse { throw it }`：解析失败原样透传
                //（InvocationHandler.invoke 声明 throws Throwable，Java Supplier 不认受检异常，走 sneakyThrow）
                throw sneakyThrow(t);
            }
        });
        Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {iface}, handler);
        ExtensionPointName<Object> ep = ExtensionPointName.create(EP_NAME);
        ep.getPoint(null).registerExtension(provider);
    }

    /** 透传受检异常的既有 k2j 手法（McpToolsetSupport 同款），供 Supplier 形态的 lambda 体承载。 */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
