package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.Nullable;

/** 快照中的真实帧与未采集区间，保持原始次序。 */
public sealed interface StackDigestItem permits StackDigestItem.Frame, StackDigestItem.Omitted {
    record Frame(
            /** 已采集的完整帧。 */
            StackFrameEntry entry) implements StackDigestItem {}

    record Omitted(
            /** 未采集帧数；旧版尾部截断时未知。 */
            @Nullable Integer count) implements StackDigestItem {}
}
