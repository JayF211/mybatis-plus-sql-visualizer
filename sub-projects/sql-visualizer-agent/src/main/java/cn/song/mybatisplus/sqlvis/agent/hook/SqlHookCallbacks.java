package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 织入回调入口（§4.2 #6 classloader 契约）：被织入代码经 SystemClassLoader 反射调用本类。
 * 注意：本类的方法签名被织入代码硬编码（"onConnection"），改动须同步 ConnectWeaver。
 * 采集红线：任何异常自吞（钩子绝不影响宿主应用）；全局禁用 volatile 短路。
 */
public final class SqlHookCallbacks {

    private static final AtomicLong DROP_FALLBACK = new AtomicLong();

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
                DROP_FALLBACK.incrementAndGet();
                return rawConnection;
            }
            return proxied;
        } catch (Throwable ignore) {
            // 采集红线：catch(Throwable) 静默自吞——失败返回原连接
            return rawConnection;
        }
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
