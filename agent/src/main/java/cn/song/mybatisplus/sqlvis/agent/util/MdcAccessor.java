package cn.song.mybatisplus.sqlvis.agent.util;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求聚合标签（T-M56-07 一期兜底路径）：反射读取 org.slf4j.MDC.get("sqlvisRequestTag")——
 * slf4j 不在 classpath / 无标签时静默 null；Method 一次解析缓存（采集红线：静态缓存反射）。
 * Web 过滤器 hook 路径待二期（计划 §5 T-M56-07「一期可选」）。
 */
public final class MdcAccessor {

    private static final String MDC_CLASS = "org.slf4j.MDC";
    private static final String TAG_KEY = "sqlvisRequestTag";
    private static final AtomicReference<Method> MDC_GET = new AtomicReference<Method>(null);
    private static volatile boolean probeFailed = false;

    public static String requestTag() {
        if (probeFailed) {
            return null;
        }
        try {
            Method m = MDC_GET.get();
            if (m == null) {
                m = Class.forName(MDC_CLASS).getMethod("get", String.class);
                MDC_GET.set(m);
            }
            final Object v = m.invoke(null, TAG_KEY);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            probeFailed = true; // 无 slf4j：本轮会话不再探测
            return null;
        }
    }

    private MdcAccessor() {
    }
}
