package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;

/**
 * Connection 代理：只拦 {@code prepareStatement* / prepareCall / createStatement}，
 * 把返回的 Statement 再包一层代理（挂 SQL 与参数捕获）；其余方法直透原对象（零干预）。
 * 采集红线：代理不得影响原语义——InvocationTargetException 还原原始异常，close/unwrap 直透。
 */
public final class ConnectionHandler implements InvocationHandler {

    private final Object raw;
    private final String url;

    ConnectionHandler(Object raw, String url) {
        this.raw = raw;
        this.url = url;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final String name = method.getName();
        try {
            if (AgentState.isDisabled()) {
                return method.invoke(raw, args);
            }
            if (args != null && args.length >= 1 && args.length <= 4 && args[0] instanceof String) {
                if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                    final Object stmt = method.invoke(raw, args);
                    return stmt == null ? null : ProxySupport.wrapStatement(stmt, (String) args[0]);
                }
            }
            if (args == null && "createStatement".equals(name)) {
                final Object stmt = method.invoke(raw, args);
                return stmt == null ? null : ProxySupport.wrapStatement(stmt, null);
            }
            return method.invoke(raw, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /** 连接 URL（connect 时的原始 URL，事件附带便于面板识别目标库）。 */
    String url() {
        return url;
    }
}
