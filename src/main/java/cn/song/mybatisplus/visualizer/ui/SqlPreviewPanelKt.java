// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanel.kt
// 文件顶层声明移植（KOTLIN_TO_JAVA_RULES §5 铁律⑦：顶层函数 → XKt.java 门面；同 LogSqlRestorerKt.java 先例）。
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * {@code SqlPreviewPanel.kt} 顶层纯函数与常量（meta 行文案/宽度预算组装）的 Java 承载类。
 *
 * <p>这些声明在 master 侧位于 {@code SqlPreviewPanel.kt} 文件末尾的类外（顶层 {@code internal}），
 * 与面板类无组件依赖，抽出即为了可脱 {@code FontMetrics} 单测（见各自 KDoc）。Kotlin 顶层声明
 * 编译落点为 {@code <文件名>Kt}，故本类名为 {@code SqlPreviewPanelKt}。</p>
 */
public final class SqlPreviewPanelKt {

    private SqlPreviewPanelKt() {
    }

    // ------------------------------------------------------------------
    // T-SourceJumpFunnel P2-2（§3.3 验收项单测抽取）：调用栈快照文案组装
    // ------------------------------------------------------------------

    /**
     * 调用栈快照的两段文案产物（纯数据，无组件依赖）。
     *
     * <p>Kotlin {@code data class} → Java {@code record}（访问器 {@code inlineLine()} /
     * {@code tooltipTail()}；record 不能 extends，此处也无继承需求）。</p>
     *
     * @param inlineLine  meta 提示行内联摘要（`   调用栈: &lt;摘要&gt;`，超长截断；无快照时为空串）
     * @param tooltipTail 悬停 tooltip 追加的全文节点（`\n调用栈: &lt;完整链&gt;`；无快照时为空串）
     */
    public record StackDigestMetaText(String inlineLine, String tooltipTail) {
    }

    /** 调用栈摘要字符上限：超过即截断到该长度并追加 {@link #STACK_DIGEST_ELLIPSIS}。 */
    public static final int STACK_DIGEST_MAX_CHARS = 120;

    /** 调用栈摘要截断标记。 */
    public static final String STACK_DIGEST_ELLIPSIS = "…";

    /**
     * T-SourceJumpFunnel P2-2：把调用栈快照 {@code digest} 组装成「meta 行内联摘要（截断展示）」与
     * 「tooltip 全文节点（完整链不截断）」两段文案；{@code digest} 为 null 时两段均为空串。
     *
     * <p>由 {@link SqlPreviewPanel#updateMeta} 调用（内联摘要进 tooltipText 首行，全文节点换行追加其后）。
     * 抽出为顶层 internal 纯函数仅为让 §3.3 验收项（截断长度/截断标记/全文节点）可直接单测断言，
     * 组装表达式与原内联实现逐字一致，不改任何展示行为。</p>
     */
    public static StackDigestMetaText buildStackDigestMetaText(@Nullable String digest) {
        if (digest == null) return new StackDigestMetaText("", "");
        String label = MpSqlVisualizerBundle.message("panel.meta.stackDigest");
        String summary = digest.length() > STACK_DIGEST_MAX_CHARS
                ? digest.substring(0, STACK_DIGEST_MAX_CHARS) + STACK_DIGEST_ELLIPSIS
                : digest;
        return new StackDigestMetaText("   " + label + " " + summary, "\n" + label + " " + digest);
    }

    // ------------------------------------------------------------------
    // T-SourceJumpFunnel P5 复核修正：meta 行宽度预算组装（纯逻辑，脱 FontMetrics 可直测）
    // ------------------------------------------------------------------

    /**
     * 调用栈摘要在 meta 行里至少要拿到的宽度（px）：约等于标签「   调用栈:」自身宽度 @13pt，
     * 低于此值就整段不放摘要——宁可让主体独占预算，也不留一个看不出是什么的孤零零省略号。
     */
    public static final int META_DIGEST_MIN_PX = 56;

    /**
     * T-M26.3 中段省略的纯函数形态（{@link SqlPreviewPanel#ellipsize} 逐字同源）：保留尾部 30%，
     * 超宽时显示 `头…尾`。{@code widthOf} 是文本像素宽度度量——注入后本函数与
     * {@link #metaLineWithinBudget} 都能脱 FontMetrics 单测（测试用「1 字符 = 1px」替身即可）。
     *
     * <p>Kotlin `(String) -&gt; Int` → Java {@link ToIntFunction}（Kotlin 侧传 lambda 即自动 SAM 转换）。</p>
     */
    public static String ellipsizeMiddle(String text, ToIntFunction<String> widthOf, int maxWidth) {
        String mark = "…";
        if (widthOf.applyAsInt(text) <= maxWidth) return text;
        int tailLen = (int) (text.length() * 0.3);
        tailLen = Math.max(tailLen, 4);
        tailLen = Math.min(tailLen, text.length() - 1);
        String tail = text.substring(text.length() - tailLen);
        int head = text.length() - tailLen;
        while (head > 0 && widthOf.applyAsInt(text.substring(0, head) + mark + tail) > maxWidth) head--;
        return text.substring(0, head) + mark + tail;
    }

    /**
     * 尾部截断（保留头部）——用于调用栈摘要：摘要<b>栈顶在左</b>（{@link #buildStackDigestMetaText} 的摘要
     * 自栈顶起截），尾部是信息量最低的栈底帧，故超宽时截尾，而非 {@link #ellipsizeMiddle} 的中段省略。
     */
    public static String clipTailToWidth(String text, ToIntFunction<String> widthOf, int maxWidth) {
        if (widthOf.applyAsInt(text) <= maxWidth) return text;
        String mark = "…";
        int end = text.length();
        while (end > 0 && widthOf.applyAsInt(text.substring(0, end) + mark) > maxWidth) end--;
        return end <= 0 ? "" : text.substring(0, end) + mark;
    }

    /**
     * T-SourceJumpFunnel P5 复核修正：meta 行在宽度预算内的组装——<b>主体优先，摘要吃剩余宽度</b>。
     *
     * <p>P5 把调用栈摘要并入可见行后，若仍把「主体 + 摘要」当<b>单段文本</b>交给 {@link #ellipsizeMiddle}
     * （保留尾部 30%），窄窗下主体会被整段挤掉、可见行只剩「…」+ 栈底帧——主体（表名 / wrapper 类型）
     * 是这一行的主信息，栈底恰是调用链里信息量最低的一端，优先级倒挂。复核（20260920）实测默认宽度
     * 预算仅够 15~25 字符，而摘要上限 120 字符，故必然触发。</p>
     *
     * <p>组装规则：主体自身超预算 → 主体中段省略（F3 原行为）；主体放得下 → 摘要按剩余宽度截尾；
     * 剩余不足 {@link #META_DIGEST_MIN_PX} → 整段丢弃摘要。{@code digest} 为空（无调用栈快照）时逐字
     * 退回 F3 原行为，渲染不变。</p>
     *
     * @param full   完整行（主体 + 摘要）——放得下即整段返回（与改前逐字节一致）
     * @param head   主体（meta + 警示行）
     * @param digest 调用栈内联摘要（`   调用栈: &lt;摘要&gt;`；无快照时为空串）
     */
    public static String metaLineWithinBudget(
            String full,
            String head,
            String digest,
            ToIntFunction<String> widthOf,
            int budget) {
        if (widthOf.applyAsInt(full) <= budget) return full;
        if (digest.isEmpty()) return ellipsizeMiddle(head, widthOf, budget);
        int remain = budget - widthOf.applyAsInt(head);
        if (remain < META_DIGEST_MIN_PX) return ellipsizeMiddle(head, widthOf, budget);
        return head + clipTailToWidth(digest, widthOf, remain);
    }
}
