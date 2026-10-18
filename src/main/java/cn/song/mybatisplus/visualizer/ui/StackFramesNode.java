package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 仅折叠已确定的库或 agent 帧，未知命名空间保持可见。 */
public sealed interface StackFramesNode permits StackFramesNode.Visible, StackFramesNode.Internal, StackFramesNode.Omission {
    List<StackFrameEntry> frames();

    record Visible(
            /** 默认可见的帧。 */
            StackFrameEntry frame) implements StackFramesNode {
        @Override
        public List<StackFrameEntry> frames() {
            return List.of(frame);
        }
    }

    record Internal(
            /** 原始次序中的连续内部帧。 */
            List<StackFrameEntry> entries) implements StackFramesNode {
        @Override
        public List<StackFrameEntry> frames() {
            return entries;
        }
    }

    record Omission(
            /** 未采集帧数；未知时为 null，不可展开成虚构帧。 */
            @Nullable Integer count) implements StackFramesNode {
        @Override
        public List<StackFrameEntry> frames() {
            return List.of();
        }
    }
}
