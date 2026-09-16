package cn.song.mybatisplus.visualizer.log;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 解析期静默失效计数（T-LogParseConc S5）：这些失效过去<b>完全无痕</b>——用户只能靠肉眼发现
 * 「参数没了」「SQL 里混进日志行」。计数暴露给面板/日志侧周期上报，排障时一眼看到是哪一类。
 *
 * 纯静态计数（无 IDE 依赖，可在纯逻辑用例中断言）；上报由调用方（控制台桥，已持有 Logger）完成。
 *
 * <p>master 的 {@code internal object LogParseDiag} 的 Java 承载（全 static 形态）。</p>
 */
public final class LogParseDiag {

    private LogParseDiag() {
    }

    /** 参数行到达时无待闭合语句（上下文已丢）→ 该参数被丢弃。 */
    public static final AtomicLong paramWithoutContext = new AtomicLong();

    /** 空 {@code Parameters:} 等待态被判别式拦下（原本会把下一行整行吞成参数值）。 */
    public static final AtomicLong swallowedLineBlocked = new AtomicLong();

    /** 多行残句强制闭合（未等到闭合括号）。 */
    public static final AtomicLong partialMultilineClosed = new AtomicLong();

    /** 参数文本命中日志行特征被拒（最后一道防污染）。 */
    public static final AtomicLong paramWithLogMarker = new AtomicLong();

    /** 上次已上报的计数快照（仅上报增量，避免刷屏）。 */
    private static long[] reported = new long[]{0, 0, 0, 0};

    /** 当前各计数（{@link #paramWithoutContext}, {@link #swallowedLineBlocked}, {@link #partialMultilineClosed}, {@link #paramWithLogMarker}）。 */
    public static long[] counts() {
        return new long[]{
                paramWithoutContext.get(),
                swallowedLineBlocked.get(),
                partialMultilineClosed.get(),
                paramWithLogMarker.get(),
        };
    }

    /**
     * 返回「自上次上报以来的增量」描述；无增量返回 null（调用方按周期调用，零增量零输出）。
     */
    public static synchronized String takeDeltaReport() {
        long[] now = counts();
        if (Arrays.equals(now, reported)) {
            return null;
        }
        long[] delta = new long[now.length];
        for (int i = 0; i < now.length; i++) {
            delta[i] = now[i] - reported[i];
        }
        reported = now;
        StringBuilder totals = new StringBuilder();
        for (int i = 0; i < now.length; i++) {
            if (i > 0) {
                totals.append('/');
            }
            totals.append(now[i]);
        }
        return "控制台解析诊断（增量）：参数行无上下文丢弃=" + delta[0] + " 空参数行拦下=" + delta[1] + " "
                + "多行残句闭合=" + delta[2] + " 参数含日志特征被拒=" + delta[3] + "（累计 " + totals + "）";
    }

    /** 全量归零（测试/会话切换）。 */
    public static synchronized void reset() {
        paramWithoutContext.set(0);
        swallowedLineBlocked.set(0);
        partialMultilineClosed.set(0);
        paramWithLogMarker.set(0);
        reported = new long[]{0, 0, 0, 0};
    }
}
