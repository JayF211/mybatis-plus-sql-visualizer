package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 织入回调入口（§4.2 #6 classloader 契约）：被织入代码经 SystemClassLoader 反射调用本类。
 * 注意：本类的方法签名被织入代码硬编码（"onConnection"），改动须同步 ConnectWeaver。
 * 采集红线：任何异常自吞（钩子绝不影响宿主应用）；全局禁用 volatile 短路。
 * T-AgentProxyCL：自吞不等于无声——失败统一走 {@link #dropToRaw}（计数 + 一次性告警）。
 */
public final class SqlHookCallbacks {

    private static final AtomicLong DROP_FALLBACK = new AtomicLong();

    /**
     * 首次失败只告警一次：诊断可见性与日志洪水的取舍。失败形态（loader 选不对/驱动隐藏 agent 类）
     * 一旦发生就是**每条连接、每条语句**都失败——逐次 WARN 会在宿主应用日志里刷屏，比故障本身更糟；
     * 而「一次都不说」正是 T-AgentProxyCL 的成因（真机零诊断，只能靠只读代理跑真实驱动才钉住）。
     * 取中间：首次打印完整原因（类名 + 消息），此后只累加 {@link #fallbackCount()} 并**每满
     * {@link #SUMMARY_EVERY} 次打一行汇总**（T-PerfSweep6 T-PS6-08 / 性能报告发现 13：此前该计数
     * 零读取、宿主侧无任何出口，注释里的「读该计数即可判断」没有落点）。
     */
    private static final AtomicBoolean WARNED_ONCE = new AtomicBoolean();

    public static Object onConnection(Object rawConnection, String url) {
        try {
            if (rawConnection == null || AgentStateBridge.isDisabled()) {
                return rawConnection;
            }
            if (ProxySupport.isOurProxy(rawConnection)) {
                return rawConnection; // 幂等：连接池复用/二次包装不叠加代理
            }
            final Connection proxied = ProxySupport.wrapConnection(rawConnection, url);
            if (proxied == null) {
                return dropToRaw("wrapConnection 返回空", null, rawConnection);
            }
            return proxied;
        } catch (Throwable t) {
            // 采集红线：catch(Throwable) 自吞——失败返回原连接；但必须可观测（T-AgentProxyCL）
            return dropToRaw("wrapConnection 抛异常", t, rawConnection);
        }
    }

    /**
     * 包装失败统一出口（T-AgentProxyCL）：计数 + 一次性告警 + 返回原对象。
     * 供本包内 Statement 包装路径（ConnectionHandler → wrapStatement）复用——那条路径的异常
     * 会直接冒到宿主的 prepareStatement 调用点，必须在此收口成「返回原语句」。
     */
    static Object dropToRaw(String where, Throwable t, Object fallback) {
        final long count = DROP_FALLBACK.incrementAndGet();
        if (WARNED_ONCE.compareAndSet(false, true)) {
            System.err.println("[T-AgentProxyCL] hook 包装失败，降级为直通（本次采集失效，仅此一条告警，"
                    + "累计次数见 fallbackCount）: " + where + " -> "
                    + (t == null ? "null" : t.getClass().getName() + ": " + t.getMessage())
                    + " [fallbackCount=" + count + "]");
        } else if (count % SUMMARY_EVERY == 0L) {
            // T-PerfSweep6 T-PS6-08（性能报告发现 13）：{@link #fallbackCount()} 此前**零读取**——
            // 注释自述「读该计数即可判断偶发还是持续」，而宿主应用侧没有任何出口读它（agent 侧
            // 无 logger，跨侧协议帧加字段会撞 AgentProtocolSyncTest 的帧守卫）。
            // 折中：首次告警之后，每满 [SUMMARY_EVERY] 次打一行**汇总**到同一个 System.err 出口。
            // 「偶发一次」= 只有首行；「持续失败」= 首行之后按百递增的汇总行——判据可读，且量级是
            // 「每百次一行」，不会在持续故障时刷屏（这正是首行只打一次要避免的问题）。
            System.err.println("[T-AgentProxyCL] hook 包装失败持续发生，累计 fallbackCount=" + count
                    + "（最近一次: " + where + "）");
        }
        return fallback;
    }

    /** 汇总行间隔（每满这么多次失败打一行；见 {@link #dropToRaw} 的 T-PS6-08 注释）。 */
    private static final long SUMMARY_EVERY = 100L;

    /** 供测试观测（反射内联钩子链路不可直接断言的兜底计数）。 */
    public static long fallbackCount() {
        return DROP_FALLBACK.get();
    }

    /** 隔离 AgentState 引用，便于宿主 loader 隐藏 agent 类时单测覆盖（HiddenAgentClassLoader 契约）。 */
    private static final class AgentStateBridge {
        static boolean isDisabled() {
            try {
                Class<?> state = Class.forName("cn.song.mybatisplus.sqlvis.agent.AgentState");
                Method m = state.getMethod("isDisabled");
                return (Boolean) m.invoke(null);
            } catch (Throwable t) {
                return true; // 看不见 agent 类 = 视同禁用（保守）
            }
        }

        private AgentStateBridge() {
        }
    }

    private SqlHookCallbacks() {
    }
}
