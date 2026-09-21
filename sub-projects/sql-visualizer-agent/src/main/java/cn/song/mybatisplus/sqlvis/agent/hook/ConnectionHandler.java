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
                    return wrapStatementSafely(stmt, (String) args[0], url);
                }
            }
            if (args == null && "createStatement".equals(name)) {
                final Object stmt = method.invoke(raw, args);
                return wrapStatementSafely(stmt, null, url);
            }
            return method.invoke(raw, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /**
     * Statement 包装收口（T-AgentProxyCL）：代理定义失败（loader 看不见语句接口）时**返回原语句**
     * 而不是把异常抛给宿主——旧实现在这里裸调 wrapStatement，失败会从宿主的 prepareStatement
     * 调用点冒出去（采集红线：钩子绝不影响宿主语义）。失败走 dropToRaw 记数 + 一次性告警。
     */
    private Object wrapStatementSafely(Object stmt, String sql, String url) {
        if (stmt == null) {
            return null;
        }
        try {
            return ProxySupport.wrapStatement(stmt, sql, url);
        } catch (Throwable t) {
            return SqlHookCallbacks.dropToRaw("wrapStatement 抛异常", t, stmt);
        }
    }
}
