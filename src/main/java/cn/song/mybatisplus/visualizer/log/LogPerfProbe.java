package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.diagnostic.Logger;

import java.util.function.Supplier;

/**
 * T-SqlLogPerf：log 包热路径耗时探针（阈值触发 INFO）。
 *
 * 背景（20261006-1020 开发计划 §阶段 0）：用户日常 IDE 实测并发压测下 8708 条历史文件加载
 * 15~16.6s、实时列表灌入 65 条/秒时面板动作等待 EDT 358~1110ms——但热路径零耗时打点，
 * 延迟无法从 idea.log 直接归因。本探针给 readFromFile/解析/列表填充/refreshList 等
 * 疑似瓶颈段统一打点；**阈值触发**（单次低于 [THRESHOLD_MS] 毫秒零输出）保证常态零噪音，
 * 风暴期才在 idea.log 留痕，事后可 `[T-SqlLogPerf]` 检索对账。
 *
 * 探针是**转正**的观测设施（不是临时调试代码）：供后续性能轮与真机报障复用。
 *
 * <p>k2j 翻译注：Kotlin 侧为 {@code object} + {@code internal inline fun measure}——Java 无 inline，
 * 本类落为 final 工具类 + 普通静态方法。语义等价：未达阈值时仅一次 {@code System.nanoTime()} 差值
 * 与一次 Supplier 装载开销，可忽略；detail 经 {@link Supplier} 保持惰性求值（未达阈值不触发字符串拼接）。
 */
public final class LogPerfProbe {

    /** 单次耗时达到该毫秒数才输出 INFO（低于阈值的调用零成本输出、零日志噪音）。 */
    public static final long THRESHOLD_MS = 100L;

    /** internal inline（非 public）才能在 inline 体内直读非公开成员；调用面限本包热路径。 */
    private static final Logger LOG = Logger.getInstance(LogPerfProbe.class);

    private LogPerfProbe() {
    }

    /**
     * 包裹 block 计时；达到 {@link #THRESHOLD_MS} 输出 {@code [T-SqlLogPerf] name …ms}。
     * detail 缺省空串（Kotlin 默认参数 {@code detail: () -> String = { "" }} 的显式重载形态）。
     */
    public static <T> T measure(String name, Supplier<T> block) {
        return measure(name, () -> "", block);
    }

    /**
     * 包裹 block 计时；达到 {@link #THRESHOLD_MS} 输出 {@code [T-SqlLogPerf] name …ms (detail)}。
     * detail 惰性求值——未达阈值时零开销，不触发字符串拼接。
     */
    public static <T> T measure(String name, Supplier<String> detail, Supplier<T> block) {
        long start = System.nanoTime();
        T result = block.get();
        long costMs = (System.nanoTime() - start) / 1_000_000;
        if (costMs >= THRESHOLD_MS) {
            String text = detail.get();
            String suffix = text != null && !text.isEmpty() ? " (" + text + ")" : "";
            LOG.info("[T-SqlLogPerf] " + name + " " + costMs + "ms" + suffix);
        }
        return result;
    }
}
