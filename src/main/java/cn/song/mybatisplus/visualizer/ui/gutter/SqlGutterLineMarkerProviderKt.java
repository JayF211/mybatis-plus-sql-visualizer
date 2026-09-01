package cn.song.mybatisplus.visualizer.ui.gutter;

/**
 * 防抖判定（纯函数，便于单测）。对应原 Kotlin 顶层函数 {@code shouldNavigate}。
 *
 * @param lastTimeMs 上次导航时间戳（毫秒）
 * @param nowMs      当前时间戳（毫秒）
 * @param windowMs   防抖窗口（毫秒）
 * @return 是否应执行导航（true = 执行，false = 丢弃）
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
