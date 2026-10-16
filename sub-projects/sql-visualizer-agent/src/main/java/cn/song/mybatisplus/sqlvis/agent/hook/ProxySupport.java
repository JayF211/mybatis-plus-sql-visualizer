package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        return (Connection) newProxy(cls, ifaces, handler);
    }

    static Object wrapStatement(Object rawStatement, String sql, String url) {
        return wrapStatement(rawStatement, sql, url, null);
    }

    static Object wrapStatement(Object rawStatement, String sql, String url, TransactionTracker transactions) {
        final Class<?> cls = rawStatement.getClass();
        final Set<Class<?>> ifaces = new HashSet<Class<?>>();
        collectInterfaces(cls, ifaces);
        final StatementHandler handler = new StatementHandler(rawStatement, sql, url, transactions);
        return newProxy(cls, ifaces, handler);
    }

    static boolean isOurProxy(Object obj) {
        if (!Proxy.isProxyClass(obj.getClass())) {
            return false;
        }
        return Proxy.getInvocationHandler(obj) instanceof ConnectionHandler;
    }

    /** 两个调用点共用的代理定义入口：loader 选择是唯一差异面，收口在此避免再次分叉。 */
    private static Object newProxy(Class<?> cls, Set<Class<?>> ifaces, InvocationHandler handler) {
        final Class<?>[] array = ifaces.toArray(new Class<?>[0]);
        return Proxy.newProxyInstance(chooseProxyLoader(cls, array), array, handler);
    }

    /**
     * 代理类定义 loader 选择（T-AgentProxyCL 缺陷修复）。
     *
     * <p>为什么必须选对：{@code Proxy.newProxyInstance} 要求 interfaces 里每个接口都能被传入的
     * loader 解析到**同一个 Class 对象**，否则抛
     * {@code IllegalArgumentException: interface X is not visible from class loader}。
     * 旧实现固定用 {@code ProxySupport.class.getClassLoader()}（agent 自身 loader，通常 = 系统
     * loader）：一旦驱动由系统 loader **之下**的子 loader 加载（{@code java -jar} 的 Spring Boot
     * fat jar → {@code LaunchedURLClassLoader}、devtools 的 {@code RestartClassLoader}、任何嵌套
     * loader 容器），真实驱动接口（{@code com.mysql.cj.jdbc.JdbcConnection} /
     * {@code com.mysql.cj.MysqlConnection} / {@code TransactionEventHandler} /
     * {@code Session$SessionEventListener}）对系统 loader 不可见 → 代理类根本定义不出来 →
     * SQL 织入整体失效。
     *
     * <p>取法：**优先对象自身类的 loader**（{@code cls.getClassLoader()}），理由是构造性保证：
     * <ul>
     *   <li>对象类的全部接口（含父接口、父类接口）都是这个 loader 解析出来的——它必然看得见，
     *       不需要靠约定或枚举驱动版本；</li>
     *   <li>对象本来就出自这一命名空间，代理类定义在这里，宿主侧的类型判定（cast/unwrap/
     *       instanceof）与未包装时完全一致；定义到 agent loader 反而可能造出「宿主看不见的
     *       命名空间里多一个类型」这种更隐蔽的形态；</li>
     *   <li>连接池/JDK 代理包装形态无需特判：别的 loader 定义的 JDK 代理类，其定义 loader 也是
     *       由 {@code newProxyInstance} 的调用方（= 池/驱动）按同一可见性规则选出来的，天然满足
     *       「看得见自己全部接口」。</li>
     * </ul>
     *
     * <p>不靠猜的部分：{@code getClassLoader()} 在两种极端下会给出不可用候选——cls 由 bootstrap
     * 定义（返回 null，只能解析 java.* 接口），或对象是别人定义的 JDK 代理而接口集跨多个 loader。
     * 因此对候选逐个做「能否解析出全部接口」的实测校验（{@code Class.forName(name, false, loader)}
     * 结果必须与接口 Class 同一），取第一个通过的：cls loader → agent loader → 系统 loader →
     * 各接口自身 loader。全都不通过才放弃包装（由调用方记数 + 一次性告警，绝不静默）。
     * 校验成本是每接口一次不初始化的类查找，只在 connect / prepareStatement 出口各跑一次，
     * 相对一次 JDBC 往返可忽略；不做全局缓存——缓存会强引用驱动类，devtools 重启换 loader 时
     * 会把旧 RestartClassLoader 钉在 agent 静态表里。
     */
    private static ClassLoader chooseProxyLoader(Class<?> cls, Class<?>[] ifaces) {
        for (ClassLoader candidate : candidates(cls, ifaces)) {
            if (canSeeAll(candidate, ifaces)) {
                return candidate;
            }
        }
        // 无候选能看见全部接口：不猜、不降级接口集（丢接口会让宿主 cast/unwrap 抛 ClassCastException），
        // 直接抛给调用方走「记数 + 一次性告警 + 返回原对象」的可见降级路径。
        throw new IllegalStateException("no single class loader resolves all " + ifaces.length
                + " interfaces of " + cls.getName() + " (loaded by " + describe(cls.getClassLoader())
                + "); e.g. " + ifaces[0].getName() + " loaded by " + describe(ifaces[0].getClassLoader()));
    }

    /** 候选 loader（去重，含 null=bootstrap 这一合法候选）；顺序即优先级。 */
    private static List<ClassLoader> candidates(Class<?> cls, Class<?>[] ifaces) {
        final List<ClassLoader> out = new ArrayList<ClassLoader>(3 + ifaces.length);
        addCandidate(out, cls.getClassLoader());
        addCandidate(out, ProxySupport.class.getClassLoader());
        addCandidate(out, ClassLoader.getSystemClassLoader());
        for (Class<?> iface : ifaces) {
            addCandidate(out, iface.getClassLoader());
        }
        return out;
    }

    private static void addCandidate(List<ClassLoader> out, ClassLoader loader) {
        if (!out.contains(loader)) {
            out.add(loader);
        }
    }

    /** 该 loader 能否把每个接口都解析成同一个 Class 对象——JDK 代理生成器的同款前置条件。 */
    private static boolean canSeeAll(ClassLoader loader, Class<?>[] ifaces) {
        for (Class<?> iface : ifaces) {
            try {
                if (Class.forName(iface.getName(), false, loader) != iface) {
                    return false;
                }
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
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
