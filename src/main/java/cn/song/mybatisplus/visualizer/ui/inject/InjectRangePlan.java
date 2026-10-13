// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/InjectRangePlan.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.openapi.util.TextRange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T-XmlGap W1：注入区间互补的纯逻辑（无平台依赖，便于单测）。
 *
 * <p>背景（计划 §六 W1 行 + §四 决策 1-A）：原让位判定是<b>宿主级</b>的——外来注入器只要对该宿主
 * 调用过 {@code startInjecting}，本插件就整宿主放弃（SqlMapperInjector 直接 return），于是「对方只占了
 * 一个 {@code #{}} 区间」也会导致<b>整条语句</b>的 SQL 关键字/注释/字符串全部无色。本轮把让位下沉到
 * <b>区间级</b>：算出外来注入器实际占用的区间，我方只注入<b>未被子集覆盖的余下区间</b>。</p>
 *
 * <p>两个要点：</p>
 * <ol>
 *   <li><b>占用区间先扩张到包住它的 {@code #{...}} / {@code ${...}} 整段</b>——竞品的 {@code ${}}
 *       注入器只报「"{${" 之后到 "}" 之前」的区间（前缀合成 "%{"），若照原样求补，会把
 *       "${" 与 "}" 两个定界符切给我方单独着色成一个运算符 token，观感错乱。扩张后切割点落在参数
 *       占位边界上。</li>
 *   <li>补集产出<b>按序、互不重叠、非空</b>；全被覆盖时返回空表（调用方据此零注入）。</li>
 * </ol>
 *
 * <p>移植注记：Kotlin object + 顶层 fun → Java 私有构造器 final 工具类 + static 方法。</p>
 */
public final class InjectRangePlan {

    private InjectRangePlan() {
    }

    /** 全覆盖哨兵：探测失败时用它表达「让位（保守）」，调用方无需额外分支。 */
    public static List<TextRange> yieldAll(int hostLength) {
        if (hostLength <= 0) return List.of();
        return List.of(new TextRange(0, hostLength));
    }

    /**
     * 我方应注入的区间表：{@code ourRange} 去掉 {@code occupied} 覆盖的部分。
     *
     * @param text     宿主文本（用于扩张 {@code #{...}}/{@code ${...}} 整段边界）
     * @param ourRange 我方打算注入的整段（宿主坐标系）
     * @param occupied 外来注入器占用的区间（宿主坐标系，可为空/乱序/越界）
     */
    public static List<TextRange> plan(CharSequence text, TextRange ourRange, List<TextRange> occupied) {
        if (ourRange.isEmpty()) return List.of();
        int clipStart = Math.max(0, ourRange.getStartOffset());
        int clipEnd = Math.min(text.length(), ourRange.getEndOffset());
        if (clipStart >= clipEnd) return List.of();
        List<TextRange> blocks = mergeBlocks(text, occupied);
        if (blocks.isEmpty()) return List.of(new TextRange(clipStart, clipEnd));

        List<TextRange> out = new ArrayList<>();
        int cursor = clipStart;
        for (TextRange b : blocks) {
            if (b.getEndOffset() <= cursor) continue;
            if (b.getStartOffset() >= clipEnd) break;
            int gapEnd = Math.min(b.getStartOffset(), clipEnd);
            if (gapEnd > cursor) out.add(new TextRange(cursor, gapEnd));
            cursor = Math.max(cursor, Math.min(b.getEndOffset(), clipEnd));
            if (cursor >= clipEnd) break;
        }
        if (cursor < clipEnd) out.add(new TextRange(cursor, clipEnd));
        return out;
    }

    /** 占用区间归一：裁剪到宿主范围 → 扩张到参数占位整段 → 按起点排序去空 → 合并重叠/相邻。 */
    private static List<TextRange> mergeBlocks(CharSequence text, List<TextRange> occupied) {
        if (occupied.isEmpty()) return List.of();
        List<TextRange> normalized = new ArrayList<>();
        for (TextRange r : occupied) {
            int s = Math.max(0, r.getStartOffset());
            int e = Math.min(text.length(), r.getEndOffset());
            if (s < e) normalized.add(expandToParamSpan(text, new TextRange(s, e)));
        }
        normalized.sort(Comparator.comparingInt(TextRange::getStartOffset));
        if (normalized.isEmpty()) return List.of();
        List<TextRange> merged = new ArrayList<>();
        TextRange cur = normalized.get(0);
        for (int i = 1; i < normalized.size(); i++) {
            TextRange r = normalized.get(i);
            if (r.getStartOffset() <= cur.getEndOffset()) {
                cur = new TextRange(cur.getStartOffset(), Math.max(cur.getEndOffset(), r.getEndOffset()));
            } else {
                merged.add(cur);
                cur = r;
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * 把区间扩张到包住它的 {@code #{...}} / {@code ${...}} 整段；不在任何参数占位内则原样返回。
     *
     * <p>向左找最近的未转义开定界符（"{" 前带 {@code #} 或 {@code $}）；途中遇到 "}" 说明已越过
     * 上一个参数的收尾，判定为「不在参数内」。未闭合（找不到 "}"）时扩张到区间自身终点——不外扩，
     * 避免把后续整段文本吞掉。</p>
     */
    public static TextRange expandToParamSpan(CharSequence text, TextRange range) {
        int open = -1;
        int k = range.getStartOffset();
        while (k >= 2) {
            if ((text.charAt(k - 2) == '#' || text.charAt(k - 2) == '$') && text.charAt(k - 1) == '{'
                    && (k - 2 == 0 || text.charAt(k - 3) != '\\')) {
                open = k - 2;
                break;
            }
            if (text.charAt(k - 1) == '}') break;
            k--;
        }
        if (open < 0) return range;
        int close = indexOf(text, '}', open + 2);
        int spanEnd = close >= 0 ? close + 1 : range.getEndOffset();
        if (spanEnd < range.getEndOffset()) return range;
        return new TextRange(open, spanEnd);
    }

    private static int indexOf(CharSequence text, char c, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == c) return i;
        }
        return -1;
    }
}
