package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 连接归属链（T-StatementWeave S4）：语句面织入钩子拿不到包装代理记下的 URL，
 * 解析顺序＝驱动连接实现类的 getURL 反射（mysql `ConnectionImpl.getURL()` public 直调、
 * h2 `JdbcConnection.getURL()` 包私有 setAccessible——javap 实测 2026-10-05）
 * → 最近 connect 钩子 URL 兜底（[noteConnectUrl]，onConnection 出口记一笔）→ null
 * （事件 url 留空，IDE 侧推断链接管）。
 *
 * <p>Method 按连接实现类缓存（ConcurrentHashMap），反射失败保守返回 null——归属链任何一环
 * 失败只影响事件的「目标库」展示，绝不影响 SQL 事件本身与宿主执行。
 */
public final class UrlResolver {

    private static final ConcurrentHashMap<Class<?>, Method> GET_URL_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();

    /** 最近一次 connect 钩子看到的 URL（粗粒度兜底：进程单库场景基本即真相）。 */
    private static final AtomicReference<String> LAST_CONNECT_URL = new AtomicReference<String>();

    private UrlResolver() {
    }

    /** connect 钩子出口记一笔（url 非空才覆盖——null 连接路径不冲掉旧值）。 */
    public static void noteConnectUrl(String url) {
        if (url != null && !url.isEmpty()) {
            LAST_CONNECT_URL.set(url);
        }
    }

    /** 最近 connect URL（兜底源，可为 null）。 */
    public static String lastConnectUrl() {
        return LAST_CONNECT_URL.get();
    }

    /**
     * 反射取连接 URL：按类缓存 Method（mysql public 直调；h2 包私有 setAccessible 一次）。
     * 调用失败返回 null（调用方落兜底）。
     */
    public static String resolve(Object connection) {
        if (connection == null) {
            return null;
        }
        try {
            Method m = GET_URL_METHODS.get(connection.getClass());
            if (m == null) {
                try {
                    m = connection.getClass().getMethod("getURL");
                } catch (NoSuchMethodException nsme) {
                    // h2 形态：包私有/非 public——declared + setAccessible
                    m = connection.getClass().getDeclaredMethod("getURL");
                    m.setAccessible(true);
                }
                GET_URL_METHODS.put(connection.getClass(), m);
            }
            Object v = m.invoke(connection);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null; // 采集红线：归属链失败不外溢
        }
    }
}
