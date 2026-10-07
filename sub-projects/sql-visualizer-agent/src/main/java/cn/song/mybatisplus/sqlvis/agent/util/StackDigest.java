package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用栈快照压缩（T-SourceJumpFunnel P2-2；T-StackHoverUi 两端采样改版）：execute 时把全栈压成
 * 「pkg.Class#method:line」单串（← 连接，栈顶在左），供 IDE 详情面板往上追踪。
 *
 * <p>预算（T-StackHoverUi 用户可配）：总帧数 B=[AgentState#getStackMaxFrames]（默认 24，
 * 钳位 [12,64]）。链长 ≤B 时全链保留（原行为）；链长 &gt;B 时**两端采样**——栈顶取 B-8 帧
 * （引擎/门面侧），栈底固定保留 8 帧（业务/Controller 侧；20261007 用户实测：JdbcTemplate
 * 深封装链下旧 16 帧顶格预算在 spring-jdbc 内部耗尽，够不着 controller），中段以标记段
 * {@code <…中间省略 N 帧…>} 连接（IDE 侧解析器对无 {@code #} 的段天然跳过，弹层兼容）。
 *
 * <p>总长硬顶 MAX_LEN=max(2000, B×96)：两端形态下超长**先缩栈顶**再出标记（栈底业务帧
 * 永不因长度被截）；全链形态沿用旧尾截语义（超长截断加 … 后缀）。
 *
 * <p>T-PS4-09 一次捕获两用：产线主路径经 locate 同一快照调 {@link #build(StackTraceElement[])}
 * （读 [AgentState] 配置）；显式帧数出口 {@link #build(StackTraceElement[], int)} 为纯函数
 * （单测用）。过滤规则不变：仅跳 Thread#getStackTrace 自身帧，业务帧与 agent 帧均不过滤。
 */
public final class StackDigest {

    /** 栈底固定保留帧数（业务/Controller 侧，T-StackHoverUi A 案）。 */
    private static final int BOTTOM_RESERVED = 8;

    /** 总帧数钳位下限（保证栈顶至少 4 帧）。 */
    private static final int MIN_FRAMES = 12;

    /** 总帧数钳位上限（事件体可控）。 */
    private static final int MAX_FRAMES_CAP = 64;

    /** 无参出口（兼容/测试）：当前线程全栈 + AgentState 配置预算。 */
    public static String build() {
        return build(Thread.currentThread().getStackTrace());
    }

    /** 产线出口：以 AgentState 配置预算压缩给定快照（T-PS4-09 与 locate 共享同一捕获）。 */
    public static String build(StackTraceElement[] stack) {
        return build(stack, AgentState.getStackMaxFrames());
    }

    /** 纯函数（单测用）：maxFrames 为用户配置的总帧数（内部钳位）。 */
    public static String build(StackTraceElement[] stack, int maxFrames) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        final List<StackTraceElement> frames = new ArrayList<>(stack.length);
        for (StackTraceElement f : stack) {
            // [0]=Thread.getStackTrace 自身：跳过
            if (f.getClassName().equals(Thread.class.getName()) && "getStackTrace".equals(f.getMethodName())) {
                continue;
            }
            frames.add(f);
        }
        if (frames.isEmpty()) {
            return null;
        }
        final int budget = clamp(maxFrames);
        final long maxLen = Math.max(2000L, (long) budget * 96L);
        if (frames.size() <= budget) {
            return join(frames, 0, frames.size(), maxLen);
        }
        // 两端采样：栈顶 + 中段标记 + 栈底；超长先缩栈顶（缩掉的顶帧计入省略数），业务帧永不因长度被截
        int topCount = Math.max(1, budget - BOTTOM_RESERVED);
        String top = join(frames, 0, topCount, Long.MAX_VALUE);
        String bottom = join(frames, frames.size() - BOTTOM_RESERVED, BOTTOM_RESERVED, Long.MAX_VALUE);
        int elided = frames.size() - topCount - BOTTOM_RESERVED;
        String marker = elisionMarker(elided);
        while (topCount > 1 && top.length() + marker.length() + bottom.length() > maxLen) {
            topCount--;
            elided++;
            top = join(frames, 0, topCount, Long.MAX_VALUE);
            marker = elisionMarker(elided);
        }
        return top + "<-" + marker + "<-" + bottom;
    }

    /** 中段省略标记段（无 {@code #}，IDE 侧解析器按不可解析段跳过）。 */
    private static String elisionMarker(int elided) {
        return "<…中间省略 " + elided + " 帧…>";
    }

    private static int clamp(int maxFrames) {
        if (maxFrames < MIN_FRAMES) {
            return MIN_FRAMES;
        }
        if (maxFrames > MAX_FRAMES_CAP) {
            return MAX_FRAMES_CAP;
        }
        return maxFrames;
    }

    /**
     * 把 frames[from, from+count) 逐帧追加为「类#方法:行」（← 连接）；maxLen 为总长硬顶，
     * 超长截断加 … 后缀（语义与旧实现逐字一致）；maxLen 传 Long.MAX_VALUE 表示不截（由调用方统管）。
     */
    private static String join(List<StackTraceElement> frames, int from, int count, long maxLen) {
        final StringBuilder sb = new StringBuilder(256);
        int used = 0;
        for (int i = from; i < frames.size() && used < count; i++) {
            final StackTraceElement f = frames.get(i);
            if (used > 0) {
                sb.append("<-");
            }
            sb.append(f.getClassName()).append('#').append(f.getMethodName());
            final int line = f.getLineNumber();
            if (line >= 0) {
                sb.append(':').append(line);
            }
            used++;
            if (sb.length() >= maxLen) {
                sb.append("…");
                break;
            }
        }
        final String s = sb.length() > maxLen ? sb.substring(0, (int) maxLen) + "…" : sb.toString();
        return used == 0 ? "" : s;
    }

    private StackDigest() {
    }
}
