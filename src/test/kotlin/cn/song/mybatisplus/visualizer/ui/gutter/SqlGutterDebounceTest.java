package cn.song.mybatisplus.visualizer.ui.gutter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProviderKt;

/**
 * Gutter 图标点击防抖逻辑单元测试（T-GutterDeb）。
 *
 * 验证 shouldNavigate 纯函数的防抖判定：
 *  - 首次点击允许（距上次 >= 窗口）
 *  - 窗口内重复点击丢弃
 *  - 窗口边界（= 窗口）允许
 *  - 不同元素共享全局窗口（全局粒度防抖）
 */
public class SqlGutterDebounceTest {

    @Test
    public void shouldNavigate_firstCallAllowed() throws Exception {
        // 上次时间戳为 0（从未导航），当前任意时间均应允许
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(0L, 1000L));
    }

    @Test
    public void shouldNavigate_withinWindowBlocked() throws Exception {
        // 间隔 100ms < 200ms 窗口 → 丢弃
        assertFalse(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1100L));
    }

    @Test
    public void shouldNavigate_afterWindowAllowed() throws Exception {
        // 间隔 200ms = 窗口 → 允许（>= 窗口）
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1200L));
    }

    @Test
    public void shouldNavigate_wellAfterWindowAllowed() throws Exception {
        // 间隔远大于窗口 → 允许
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 5000L));
    }

    @Test
    public void shouldNavigate_boundaryExactlyAtWindow() throws Exception {
        // 刚好等于 200ms → 允许
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1200L));
    }

    @Test
    public void shouldNavigate_justBeforeWindowBlocked() throws Exception {
        // 间隔 199ms < 200ms → 丢弃
        assertFalse(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1199L));
    }

    @Test
    public void shouldNavigate_differentElementsShareGlobalWindow() throws Exception {
        // 全局粒度：点击 A 后立即点击 B（不同元素），B 被防抖丢弃
        long lastTime = 1000L;
        long now = 1050L; // 间隔 50ms < 200ms
        assertFalse("不同元素在窗口内应被防抖丢弃",
                SqlGutterLineMarkerProviderKt.shouldNavigate(lastTime, now));
    }

    @Test
    public void shouldNavigate_customWindowSize() throws Exception {
        // 自定义窗口 500ms：间隔 400ms → 丢弃
        assertFalse(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1400L, 500L));
        // 自定义窗口 500ms：间隔 500ms → 允许
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1500L, 500L));
    }

    @Test
    public void shouldNavigate_zeroWindowAlwaysAllows() throws Exception {
        // 窗口为 0：任何间隔都允许
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1000L, 0L));
        assertTrue(SqlGutterLineMarkerProviderKt.shouldNavigate(1000L, 1001L, 0L));
    }
}
