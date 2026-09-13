package cn.song.mybatisplus.visualizer.ui.gutter;

/**
 * 保留供可能外部引用的防抖纯函数。对应原 Kotlin 顶层函数 {@code shouldNavigate}。
 */
public final class SqlGutterLineMarkerProviderKt {

    /** 默认防抖窗口（毫秒），对应原 Kotlin 顶层函数缺省参数 {@code windowMs = 200L}。 */
    public static boolean shouldNavigate(long lastTimeMs, long nowMs) {
        return shouldNavigate(lastTimeMs, nowMs, 200L);
    }

    public static boolean shouldNavigate(long lastTimeMs, long nowMs, long windowMs) {
        return nowMs - lastTimeMs >= windowMs;
    }
}
