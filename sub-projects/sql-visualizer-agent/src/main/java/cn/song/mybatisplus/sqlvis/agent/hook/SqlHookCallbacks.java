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
     * 取中间：首次打印完整原因（类名 + 消息），此后只累加 {@link #fallbackCount()}，读该计数即可
     * 判断「是偶发一次还是持续失败」。
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
        }
        return fallback;
    }

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
