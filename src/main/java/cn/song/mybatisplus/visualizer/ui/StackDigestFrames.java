// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/StackDigestFrames.kt
// （KOTLIN_TO_JAVA_RULES §2：Kotlin object 仅静态成员 → Java 全 static 承载类；Kotlin 测试树按
//   StackDigestFrames.parse(...) 静态形态直呼）
package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * T-StackHoverUi：调用栈快照串的结构化解析与调试器 Frames 风格展示模型。
 *
 * <p>快照串由 agent 侧 {@code StackDigest.build(StackTraceElement[])} 产出，格式
 * {@code pkg.Class#method:line<-pkg.Class#method:line<-…}（{@code <-} 连接、栈顶在左、行号负数不写、
 * ≤16 帧/总长 ≤2000 字符——帧数或长度触顶时追加 {@code …} 截断标记）。IDE 侧此前把它当
 * 整段纯文本塞 tooltip（T-SourceJumpFunnel P2-2 形态），本对象把它拆回帧列表，
 * 供 {@link StackFrameHover} 悬停弹层逐帧渲染。</p>
 */
public final class StackDigestFrames {

    /** 帧间连接符（agent 侧 StackDigest 逐字契约）。 */
    public static final String FRAME_SEPARATOR = "<-";

    /** agent 侧截断标记（帧数触顶追加在完整末帧之后；长度触顶切在半帧中段，二者均以 `…` 收尾）。 */
    public static final String TRUNCATION_MARK = "…";

    /** agent 自身帧的包前缀（弹层里淡灰降权，模拟调试器「库帧」弱化样式）。 */
    public static final String AGENT_PACKAGE_PREFIX = "cn.song.mybatisplus.sqlvis.";

    private StackDigestFrames() {
    }

    /**
     * 把快照串拆回帧列表（栈顶在左，展示序与串序一致）。解析失败容错到底：
     * 无 `#` 的残段跳过、整串不可解析返回空表（调用方回退纯文本形态），永不抛异常。
     *
     * <p>截断串（结尾 `…`）的末段一律丢弃：触顶末段要么是带标记的完整帧、要么是被 2000 字符
     * 硬截切出的半帧（后者解析后方法名是残缺前缀，展示会造假），丢 1 帧换来「不展示残帧」。</p>
     */
    @NotNull
    public static List<StackFrameEntry> parse(@Nullable String digest) {
        if (digest == null || digest.isBlank()) return List.of();
        String trimmedDigest = digest.trim();
        if (trimmedDigest.isEmpty()) return List.of();
        boolean truncated = trimmedDigest.endsWith(TRUNCATION_MARK);
        String s = truncated
                ? trimmedDigest.substring(0, trimmedDigest.length() - TRUNCATION_MARK.length())
                : trimmedDigest;
        if (s.isEmpty()) return List.of();
        String[] segments = s.split(FRAME_SEPARATOR, -1);
        // 截断串末段一律丢弃：触顶末段要么是带标记的完整帧（丢 1/16 帧无伤）、要么是被 2000 字符
        // 硬截切出的半帧（解析后方法名是残缺前缀，展示会造假）——两者按「丢弃」统一处理。
        List<String> usable;
        if (truncated && segments.length > 0) {
            usable = new ArrayList<>();
            for (int i = 0; i < segments.length - 1; i++) usable.add(segments[i]);
        } else {
            usable = new ArrayList<>();
            for (String seg : segments) usable.add(seg);
        }
        List<StackFrameEntry> frames = new ArrayList<>(usable.size());
        for (String seg : usable) {
            String seg1 = seg.trim();
            if (seg1.isEmpty()) continue;
            StackFrameEntry frame = parseFrame(seg1);
            if (frame != null) frames.add(frame);
        }
        return frames;
    }

    /** agent 自身帧判定（采集埋点链，弹层降权用）。 */
    public static boolean isAgentFrame(@NotNull StackFrameEntry frame) {
        return frame.fqcn().startsWith(AGENT_PACKAGE_PREFIX);
    }

    /**
     * 单帧解析：`pkg.Class#method:line`。`#` 定位类名/方法名边界（首个 `#`——方法名不含 `#`）；
     * 行号取方法名内**最后一个** `:` 之后全数字段（lambda 名 `lambda$run$0` 等含 `:` 的情况不存在，
     * 但代理/内嵌类名 `$` 段已在 `#` 前被隔离）。无行号段或非数字 → {@link StackFrameEntry#line} = 0。
     */
    @Nullable
    private static StackFrameEntry parseFrame(@NotNull String segment) {
        int hash = segment.indexOf('#');
        if (hash <= 0 || hash == segment.length() - 1) return null;
        String fqcn = segment.substring(0, hash);
        if (fqcn.isBlank()) return null;
        String rest = segment.substring(hash + 1);
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            String linePart = rest.substring(colon + 1);
            if (!linePart.isEmpty() && isAllDigits(linePart)) {
                return new StackFrameEntry(fqcn, rest.substring(0, colon), Integer.parseInt(linePart));
            }
        }
        return new StackFrameEntry(fqcn, rest, 0);
    }

    /** Kotlin {@code CharSequence.all { it.isDigit() }} 等价：全部字符为（Unicode）数字。 */
    private static boolean isAllDigits(@NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }
}
