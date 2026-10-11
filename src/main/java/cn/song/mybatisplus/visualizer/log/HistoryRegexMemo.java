package cn.song.mybatisplus.visualizer.log;

/**
 * 正则编译 memo（F41 双字段 memo 的 T-HistorySearchToggles 扩展）。
 *
 * <p><b>键 = 源串 + matchCase 旗二元组</b>——只按源串判等会在切开关后复用错误编译结果
 * （例：{@code user} 以 IGNORE_CASE 编译后切到敏感态仍复用 → 大写目标命中不了，S3 最易埋点）。
 * 非法源串同样整键 memo（{@link #compiled} 配 null），同坏串同旗反复 apply 不再重复抛编译异常，
 * 换串或换旗即重试，匹配行为与逐次重编完全一致（Regex 的 containsMatchIn 无状态，复用安全）。
 * 面板单实例且调用全部发生在 EDT（Swing Timer 回调），可变字段无需并发容器。
 */
public class HistoryRegexMemo {

    public String source = null;

    /** 与 {@link #source} 配对的编译大小写旗（memo 键的另一半）。 */
    public boolean matchCase = false;

    /** 编译结果（非法源串配 null，调用方走子串回退路径）。 */
    public kotlin.text.Regex compiled = null;
}
