package cn.song.mybatisplus.sqlvis.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 织入端到端测试夹具：最小 Driver。connect 返回 JDK Proxy 的 Connection
 * （prepareStatement 返回 JDK Proxy 的 PreparedStatement），避免手写接口全实现。
 * 织入钩子只包 connect 出口，与返回对象的真实形态无关。
 */
public class FakeDriver implements java.sql.Driver {

    /** 最近一次 connect 返回的原对象（未织入语义断言用）。 */
    public static volatile Connection lastRaw;

    @Override
    public Connection connect(String url, Properties info) {
        if (url == null || !url.startsWith("jdbc:fake:")) {
            return null;
        }
        final ClassLoader cl = FakeDriver.class.getClassLoader();
        lastRaw = (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                new FakeConnHandler());
        return lastRaw;
    }

    /** 最小 Connection 代理：prepareStatement 返回 PreparedStatement 代理，其余安全默认值。 */
    public static class FakeConnHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "prepareStatement":
                    return Proxy.newProxyInstance(proxy.getClass().getClassLoader(),
                            new Class<?>[]{java.sql.PreparedStatement.class},
                            new FakeStmtHandler((String) args[0]));
                case "isClosed":
                    return Boolean.FALSE;
                case "isValid":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        }
    }

    /** 最小 PreparedStatement 代理：executeUpdate 计数返回 1，execute/executeQuery 默认值。 */
    public static class FakeStmtHandler implements InvocationHandler {
        public final String sql;
        public int executeCalls;

        public FakeStmtHandler(String sql) {
            this.sql = sql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "executeUpdate":
                case "executeLargeUpdate":
                    executeCalls++;
                    return method.getName().equals("executeLargeUpdate") ? 1L : (Object) 1;
                case "execute":
                    executeCalls++;
                    return Boolean.FALSE;
                case "executeQuery":
                    executeCalls++;
                    return null;
                case "toString":
                    return "FakeStatement(" + sql + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:fake:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        throw new UnsupportedOperationException();
    }
}
