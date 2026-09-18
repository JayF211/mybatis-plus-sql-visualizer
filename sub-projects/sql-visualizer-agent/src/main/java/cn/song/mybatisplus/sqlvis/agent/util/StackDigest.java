package cn.song.mybatisplus.sqlvis.agent.util;

/**
 * 调用栈快照压缩（T-SourceJumpFunnel P2-2）：execute 时把全栈压成
 * 「pkg.Class#method:line」单串（← 连接，栈顶在左），供 IDE 详情面板人工往上追踪。
 *
 * <p>预算红线：≤16 帧、总长 ≤2000 字符（超长截断加 … 后缀）——事件体体积可控，
 * 汇聚门面场景下 16 帧足以覆盖「业务 → 引擎门面」全链。
 *
 * <p>T-PS4-09 一次捕获两用：产线主路径不再经无参 {@link #build()} 二次捕获——StatementHandler 用
 * locate 同一快照调 {@link #build(StackTraceElement[])}。两捕获点仅相差头部自身帧（原头帧为
 * StackDigest#build，现头帧为捕获处 emitEvent，行号亦随捕获点），16 帧/2000 字符截断下内容差异
 * 极小（尾部多含一帧业务帧）；过滤规则不变：仅跳 Thread#getStackTrace 自身帧，业务帧与 agent 帧
 * 均不过滤。
 */
public final class StackDigest {

    private static final int MAX_FRAMES = 16;
    private static final int MAX_LEN = 2000;

    /**
     * 在当前线程执行，产出快照串（空栈返回 null）。
     * T-PS4-09 后产线主路径不再使用（见类注携栈出口）；保留作兼容/测试出口。
     */
    public static String build() {
        return build(Thread.currentThread().getStackTrace());
    }

    /** 纯函数（单测用）。 */
    public static String build(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(256);
        int used = 0;
        for (StackTraceElement f : stack) {
            // [0]=Thread.getStackTrace 自身：跳过
            if (f.getClassName().equals(Thread.class.getName()) && "getStackTrace".equals(f.getMethodName())) {
                continue;
            }
            if (used > 0) {
                sb.append("<-");
            }
            sb.append(f.getClassName()).append('#').append(f.getMethodName());
            final int line = f.getLineNumber();
            if (line >= 0) {
                sb.append(':').append(line);
            }
            used++;
            if (used >= MAX_FRAMES || sb.length() >= MAX_LEN) {
                sb.append("…");
                break;
            }
        }
        final String s = sb.length() > MAX_LEN ? sb.substring(0, MAX_LEN) + "…" : sb.toString();
        return used == 0 ? null : s;
    }

    private StackDigest() {
    }
}
