import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * ACP 绕行的**运行时**探针（复核 major⑯ 的真机替代物）：在受控 classpath 下加载**真制品**的
 * `AcpIdeaMcpTransportProvider` 接口与 `AcpIdeaMcpTransport` 枚举，再加载**我方实现类**，
 * 然后按接口声明的形参类型做一次真实调用。
 *
 * 与 `MlLlmAcpSignatureChecker` 的分工：checker 只读 class 字节（静态、逐制品核描述符）；
 * 本探针走 JVM 真实链接与调用（动态），回答静态检查答不了的两问：
 *  1. 我方类在**该制品的类型面**下能否加载/实例化/通过 `instanceof` 接口判定（形参类型缺席时是否炸）；
 *  2. 调接口方法是否抛 `AbstractMethodError`（= 没有实现该接口）——这正是复核⑯ 指认的失效形态。
 *
 * 为什么必须是独立 JVM：本仓测试 JVM 的 classpath 上是 `src/mcpStub` 的**桩**接口，而探针要的是
 * **真制品**的接口；两者同 FQCN 不同 ClassLoader，同进程内 `isInstance` 恒假，故必须另起进程。
 *
 * 用法：`java -cp <探针类目录> AcpTransportRuntimeProbe <implClassDir> <agentIdPackage> <jar>...`
 * 退出码：0 = 两问都成立；1 = 有断言不成立（末行 `RESULT MISSING n`）。
 */
public class AcpTransportRuntimeProbe {

    private static final String PROVIDER = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransportProvider";
    private static final String TRANSPORT = "com.intellij.ml.llm.agents.acp.client.AcpIdeaMcpTransport";
    private static final String IMPL = "cn.song.mybatisplus.visualizer.acp.AcpIdeaMcpTransportProviderImpl";
    private static final String CLAUDE_ACP_ID = "acp.registry.claude-acp";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: AcpTransportRuntimeProbe <implClassDir> <agentIdPackage> <jar|dir>...");
            System.out.println("RESULT MISSING 1");
            System.exit(1);
            return;
        }
        File implDir = new File(args[0]);
        String agentIdPackage = args[1];
        List<URL> urls = new ArrayList<URL>();
        urls.add(implDir.toURI().toURL());
        for (int i = 2; i < args.length; i++) {
            File f = new File(args[i]);
            if (f.isDirectory()) {
                // 目录 = 把其中所有 jar 挂上（发行版 lib/ 全量挂载：真机上平台类本来就都在
                // 插件 classloader 视野内，逐个挑 jar 会陷入「缺一个类补一个 jar」的循环）
                File[] jars = f.listFiles(new java.io.FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith(".jar");
                    }
                });
                if (jars != null) {
                    java.util.Arrays.sort(jars);
                    for (File j : jars) {
                        urls.add(j.toURI().toURL());
                    }
                }
            } else {
                urls.add(f.toURI().toURL());
            }
        }
        // parent = null：完全隔离，避免宿主测试 classpath 上的桩类抢先解析
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        System.out.println("IMPL_DIR " + implDir.getAbsolutePath());
        System.out.println("AGENT_ID_PACKAGE " + agentIdPackage);

        int missing = 0;

        // initialize=false：真接口的 Companion 静态块会 new ExtensionPointName（要平台类），
        // 而本探针只做类型链接与虚分派，不需要初始化它
        Class<?> iface = Class.forName(PROVIDER, false, loader);
        Class<?> transport = Class.forName(TRANSPORT, false, loader);
        Class<?> agentIdClass = Class.forName(agentIdPackage + ".AcpAgentId", false, loader);
        System.out.println("IFACE " + iface.getName() + " loader=" + iface.getClassLoader());
        System.out.println("AGENT_ID_CLASS " + agentIdClass.getName());

        // 接口声明的唯一抽象方法（取其**实际**形参类型，故探针自适应两条发行线的形态）
        Method target = null;
        for (Method m : iface.getMethods()) {
            if ("getTransport".equals(m.getName()) && m.getParameterCount() == 1) {
                target = m;
            }
        }
        if (target == null) {
            System.out.println("MISS 接口未声明 getTransport(…)");
            System.out.println("RESULT MISSING 1");
            System.exit(1);
            return;
        }
        System.out.println("IFACE_METHOD getTransport(" + target.getParameterTypes()[0].getName() + ")");

        Class<?> impl;
        try {
            impl = Class.forName(IMPL, true, loader);
            System.out.println("IMPL_LOADED " + impl.getName());
        } catch (Throwable t) {
            System.out.println("MISS 我方实现类加载失败：" + t);
            System.out.println("RESULT MISSING 1");
            System.exit(1);
            return;
        }
        if (!target.getParameterTypes()[0].equals(agentIdClass)) {
            System.out.println("MISS 接口形参类型与登记的 AcpAgentId 包不一致（接口="
                + target.getParameterTypes()[0].getName() + "，登记=" + agentIdClass.getName() + "）");
            missing++;
        }

        Object instance;
        try {
            instance = impl.getDeclaredConstructor().newInstance();
            System.out.println("IMPL_INSTANTIATED ok");
        } catch (Throwable t) {
            System.out.println("MISS 我方实现类实例化失败：" + t);
            System.out.println("RESULT MISSING 1");
            System.exit(1);
            return;
        }
        if (iface.isInstance(instance)) {
            System.out.println("INTERFACE_CHECK isInstance=true");
        } else {
            System.out.println("MISS 实例不通过接口判定（impl 未声明实现该接口）");
            missing++;
        }

        Object sse = null;
        for (Object c : transport.getEnumConstants()) {
            if ("SSE".equals(String.valueOf(c))) {
                sse = c;
            }
        }

        // ① 目标 agent → 必须返回 SSE（真调用，不是只读字节）
        try {
            Object r = target.invoke(instance, newAgentId(agentIdClass, CLAUDE_ACP_ID));
            System.out.println("CALL_CLAUDE result=" + r);
            if (sse != null && sse.equals(r)) {
                System.out.println("CALL_CLAUDE_MATCHES_SSE ok");
            } else {
                System.out.println("MISS 目标 agent 未返回 SSE（实际 " + r + "）");
                missing++;
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("MISS 调接口方法抛异常：" + cause.getClass().getName() + ": " + cause.getMessage()
                + "（AbstractMethodError = 我方类没有实现该制品接口的抽象方法）");
            missing++;
        }

        // ② 非目标 agent → 必须返回 null（证明逻辑真在跑，不是恒返回常量）
        try {
            Object r = target.invoke(instance, newAgentId(agentIdClass, "acp.registry.copilot"));
            System.out.println("CALL_OTHER result=" + r);
            if (r == null) {
                System.out.println("CALL_OTHER_NULL ok");
            } else {
                System.out.println("MISS 非目标 agent 应返回 null（不表态、让位给后续注册者），实际 " + r);
                missing++;
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("MISS 非目标 agent 调用抛异常：" + cause.getClass().getName() + ": " + cause.getMessage());
            missing++;
        }

        System.out.println(missing == 0 ? "RESULT OK" : "RESULT MISSING " + missing);
        System.exit(missing == 0 ? 0 : 1);
    }

    /**
     * 造一个**真制品**的 AcpAgentId 实例。不能用 Java 动态代理：真类是 `sealed interface`
     * （JDK 明确拒绝 `Proxy` 实现 sealed 接口），故改走真制品自己的 `Companion.parse(String)`
     * （javap 实测存在，签名 `parse(java.lang.String): AcpAgentId`）。
     */
    private static Object newAgentId(Class<?> agentIdClass, String fullId) throws Exception {
        Object companion = agentIdClass.getField("Companion").get(null);
        Method parse = companion.getClass().getMethod("parse", String.class);
        Object id = parse.invoke(companion, fullId);
        System.out.println("AGENT_ID_INSTANCE " + fullId + " -> " + id.getClass().getName());
        return id;
    }
}
