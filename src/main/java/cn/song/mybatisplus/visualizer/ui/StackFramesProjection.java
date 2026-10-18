package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record StackFramesProjection(
        /** 原始快照；复制时不得以折叠后的展示替代。 */
        @Nullable String original,
        /** 保序的展示节点。 */
        List<StackFramesNode> nodes,
        /** 未发现可见业务帧，不能将纯内部链当作完整业务路径。 */
        boolean noProjectFrames) {

    public static StackFramesProjection build(@Nullable String digest) {
        List<StackFramesNode> nodes = new ArrayList<>();
        List<StackFrameEntry> pending = new ArrayList<>();
        for (StackDigestItem item : StackDigestFrames.parseItems(digest)) {
            if (item instanceof StackDigestItem.Omitted omitted) {
                flush(nodes, pending);
                nodes.add(new StackFramesNode.Omission(omitted.count()));
            } else if (item instanceof StackDigestItem.Frame frame) {
                if (StackDigestFrames.isKnownInternal(frame.entry())) {
                    pending.add(frame.entry());
                } else {
                    flush(nodes, pending);
                    nodes.add(new StackFramesNode.Visible(frame.entry()));
                }
            }
        }
        flush(nodes, pending);
        return new StackFramesProjection(digest, nodes,
                nodes.stream().noneMatch(node -> node instanceof StackFramesNode.Visible));
    }

    private static void flush(List<StackFramesNode> nodes, List<StackFrameEntry> pending) {
        if (!pending.isEmpty()) {
            nodes.add(new StackFramesNode.Internal(new ArrayList<>(pending)));
            pending.clear();
        }
    }
}
