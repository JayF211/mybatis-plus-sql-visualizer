package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;

/**
 * JDK 动态代理包装（§4.2 #1 主路径）：Connection → Statement/PreparedStatement 两层。
 * 代理接口 = 对象类层级全部接口 ∪ java.sql.Connection（覆盖 Hikari/Druid 的多层包装形态）。
 */
final class ProxySupport {

    static Connection wrapConnection(Object raw, String url) {
        final Class<?> cls = raw.getClass();
        final Set<Class<?>> ifaces = new HashSet<Class<?>>();
        collectInterfaces(cls, ifaces);
        ifaces.add(Connection.class);
        final ConnectionHandler handler = new ConnectionHandler(raw, url);
        return (Connection) Proxy.newProxyInstance(
                ProxySupport.class.getClassLoader(),
                ifaces.toArray(new Class<?>[0]),
                handler);
    }

    static Object wrapStatement(Object rawStatement, String sql, String url) {
        final Class<?> cls = rawStatement.getClass();
        final Set<Class<?>> ifaces = new HashSet<Class<?>>();
        collectInterfaces(cls, ifaces);
        final StatementHandler handler = new StatementHandler(rawStatement, sql, url);
        return Proxy.newProxyInstance(
                ProxySupport.class.getClassLoader(),
                ifaces.toArray(new Class<?>[0]),
                handler);
    }

    static boolean isOurProxy(Object obj) {
        if (!Proxy.isProxyClass(obj.getClass())) {
            return false;
        }
        return Proxy.getInvocationHandler(obj) instanceof ConnectionHandler;
    }

    /** 递归收集接口（含父接口），去重保持确定性顺序。 */
    private static void collectInterfaces(Class<?> cls, Set<Class<?>> out) {
        for (Class<?> i : cls.getInterfaces()) {
            if (out.add(i)) {
                collectInterfaces(i, out);
            }
        }
        final Class<?> sup = cls.getSuperclass();
        if (sup != null) {
            collectInterfaces(sup, out);
        }
    }

    private ProxySupport() {
    }
}
