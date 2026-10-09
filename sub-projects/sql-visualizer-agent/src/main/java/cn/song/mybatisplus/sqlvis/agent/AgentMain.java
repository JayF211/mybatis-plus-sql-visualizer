package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;

import java.lang.instrument.Instrumentation;

/**
 * M56 agent 双入口（premain / agentmain 同类，§4.2 #3）：premain 需求池补位「启动即捕获」，
 * 主路径 attach + agentmain——agentmain 必须挂 transformer（可重转换）并对已加载驱动类
 * retransform（DebugTools 半成品陷阱的硬验收项）。
 *
 * 幂等：system property {@code sqlvis.agent.active} 标记（IDE 侧 pid 表双保险）——
 * 二次 loadAgent 直接返回，不再叠加 transformer/代理。
 *
 * 勘误（T-FirstUsePremainConsent S3，2026-10-05）：「AgentLoadException:0 只出现在 JDK8 二次
 * 挂载」的旧口径不准——243 沙盒（attach 目标为现代 JDK）**首次**挂载也观察到该异常（idea.log
 * 10:15:23 实录），随后握手成功证明 agent 实际装载。该异常只作「结果以握手为准」的容错信号，
 * 不作为幂等判据，IDE 侧容错见 AgentManager。
 */
public final class AgentMain {

    /** 在线标记 key：值 = "port=<n>"。 */
    public static final String ACTIVE_MARKER = "sqlvis.agent.active";

    private static volatile boolean installed = false;

    // 尽早定义 DriverRegistry（AgentMain 自身 <clinit> 内）——消除 attach 线程与并发
    // transform 调用对同一类的首次定义竞争窗口（实测双 define LinkageError 规避）
    static {
        try {
            Class.forName(DriverRegistry.class.getName(), true, AgentMain.class.getClassLoader());
        } catch (Throwable ignore) {
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static synchronized void install(String agentArgs, Instrumentation inst) {
        if (installed) {
            return;
        }
        final AgentArgs args = AgentArgs.parse(agentArgs);
        if (args.getPort() <= 0) {
            // 无端口可上报：事件无处可发，直接放弃（IDE 侧 attach 前必下发端口）
            return;
        }
        // 幂等（双保险之 agent 侧）：先于 transformer 安装检查
        final String prev = System.getProperty(ACTIVE_MARKER);
        if (prev != null) {
            return;
        }

        AgentState.setLogLevel(args.getLogLevel());
        // T-StackHoverUi：attach/premain 参数即初始化调用栈快照帧数预算（运行期可经 CONFIG 帧热更新）
        AgentState.setStackMaxFrames(args.getStackMaxFrames());
        // transformer 一律 canRetransform=true：premain 路径新加载驱动直接织入；
        // agentmain 路径对已加载驱动 retransform（只改方法体，不增删成员——ConnectWeaver 合规）
        inst.addTransformer(new SqlHookTransformer(), true);
        retransformLoadedDrivers(inst);
        EventBus.start(args.getPort());
        System.setProperty(ACTIVE_MARKER, "port=" + args.getPort());
        installed = true;
    }

    /**
     * 对已加载的驱动类执行 retransform（agentmain 门禁：SQL 事件须在应用已运行后到达）。
     *
     * T-StatementWeave S5：扩展为**三面**——驱动 connect 面（原有）+ 语句 execute 面 + prepare
     * 登记面。语句面是「attach 即生效」的直接落点：钩子挂在 execute 族方法体上，retransform 后
     * **存量**语句对象的下一次执行即带钩子，与连接/语句对象的创建时间无关（池化老连接由此覆盖——
     * connect 面做不到：包装只发生在连接创建时，存量连接不可回溯）。
     *
     * T-AttachZeroEvent（2026-10-09）：不再静默——attach 现场零事件时此前无从区分「名单没命中/
     * isModifiableClass 拒绝/retransform 被 JVM 拒/transform 产出原字节」四种失败形态，只能靠行为
     * 反推。现逐类计数 + 汇总一行 stderr（宿主应用控制台可见）：单类失败仍不阻断整体，但**不再无声**。
     * 判读：wove>0 而 transformer wovenCount==0 ⇒ transform 层静默 no-change（织入器/可见性闸门）；
     * failed>0 ⇒ JVM 拒绝（VerifyError 等），失败类名随行列出。
     */
    private static void retransformLoadedDrivers(Instrumentation inst) {
        Class<?>[] loaded = inst.getAllLoadedClasses();
        int matched = 0;
        int wove = 0;
        int unmodifiable = 0;
        int failed = 0;
        final StringBuilder failures = new StringBuilder();
        for (Class<?> c : loaded) {
            final String name = c.getName();
            final boolean weavable = DriverRegistry.isWeavable(name)
                    || DriverRegistry.isStatementWeavable(name)
                    || DriverRegistry.isPrepareWeavable(name);
            if (!weavable) {
                continue;
            }
            matched++;
            if (!inst.isModifiableClass(c)) {
                unmodifiable++;
                continue;
            }
            try {
                inst.retransformClasses(c);
                wove++;
            } catch (Throwable t) {
                // 单类失败不阻断整体（版本漂移/不可重转换场景），该驱动降级为不增强——计数 + 类名留痕
                failed++;
                if (failures.length() < 400) {
                    failures.append(name).append('=').append(t.getClass().getSimpleName()).append(' ');
                }
            }
        }
        // 每次挂载恰一行（premain/agentmain 同走 install），量级无害；这是 attach 链唯一的总览出口
        System.err.println("[SqlVisAgent] attach retransform: matched=" + matched
                + " wove=" + wove + " unmodifiable=" + unmodifiable + " failed=" + failed
                + (failures.length() > 0 ? " [" + failures.toString().trim() + "]" : "")
                + " | transformer: weavable=" + SqlHookTransformer.weavableCount()
                + " woven=" + SqlHookTransformer.wovenCount()
                + " noChange=" + SqlHookTransformer.noChangeCount()
                + " transformFailed=" + SqlHookTransformer.failedCount());
    }

    private AgentMain() {
    }
}
