// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/acp/AcpTransportRegistrar.kt（object AcpTransportEpRegistrar 声明）
package cn.song.mybatisplus.visualizer.acp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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

    // 常量前置声明：sseResolver 的 lambda 属静态初始化，Java 非法前向引用要求被引常量先声明
    // （Kotlin object 无此限制——顶层常量声明顺序不影响初始化期可引用性）。
    private static final String PROVIDER_IFACE_FQN = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider";
    private static final String TRANSPORT_ENUM_FQN_ = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport";
    private static final String EP_NAME = "com.intellij.ml.llm.agents.acp.ideaMcpTransportProvider";

    /* 4a（T-PerfSweep8）：SSE 常量缓存——KDoc 既有承诺「首次 getTransport 时解析并缓存」，
     *  实现此前每次调用重跑 Class.forName+getField（MCP 会话清单刷新级频率）。按计划口径：
     *  首次解析成功缓存（getField 返回 static final 枚举常量，每类加载器单实例）；解析失败
     *  <b>不缓存、维持 throw 路径</b>（与原实现语义一致）；getFullId 的 Method 保留现查
     *  （上游两条发行线 agentId 运行时类可能不同，单槽复杂度不值当）。 */
    private static volatile Object cachedSse = null;
    private static volatile boolean sseResolved = false;

    /** 4a 测试缝（internal）：SSE 解析函数可注入计数实现驱动先红后绿；生产=真反射。 */
    public static volatile Supplier<Object> sseResolver = () -> {
        try {
            return Class.forName(TRANSPORT_ENUM_FQN_).getField("SSE").get(null);
        } catch (Throwable t) {
            // Kotlin 函数类型 () -> Any? 不声明受检异常；Java Supplier.get() 亦不认受检异常，
            // 故反射异常经 sneakyThrow 原样透传（InvocationHandler.invoke 声明 throws Throwable，
            // 运行时与 Kotlin 直抛等价）。
            throw sneakyThrow(t);
        }
    };

    private static Object sseConstant() {
        if (sseResolved) return cachedSse;
        Object v = sseResolver.get(); // 解析失败抛出——不缓存，维持原 throw 语义（计划口径）
        cachedSse = v;
        sseResolved = true;
        return v;
    }

    /** 4a 测试缝：handler 消费入口 internal 化（生产链路仍走 registerProxy 私有调用）。 */
    public static Object sseConstantForTest() {
        return sseConstant();
    }

    /** 4a 测试缝：清缓存态（跨用例隔离）。 */
    public static void resetSseCacheForTest() {
        cachedSse = null;
        sseResolved = false;
    }

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
        InvocationHandler handler = AcpTransportRegistrarKt.transportHandler(AcpTransportEpRegistrar::sseConstant);
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
