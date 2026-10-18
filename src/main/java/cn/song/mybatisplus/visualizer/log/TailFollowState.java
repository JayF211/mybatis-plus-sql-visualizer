package cn.song.mybatisplus.visualizer.log;

/** 用户滚动暂停尾随，只有明确回到底部或清空才恢复。 */
public final class TailFollowState {
    private TailFollowMode mode = TailFollowMode.FOLLOWING;

    public TailFollowMode getMode() {
        return mode;
    }

    public void onUserScroll() {
        mode = TailFollowMode.PAUSED;
    }

    public void onScrollToEnd() {
        mode = TailFollowMode.FOLLOWING;
    }

    public void onClear() {
        mode = TailFollowMode.FOLLOWING;
    }

    public boolean getShouldFollow() {
        return mode == TailFollowMode.FOLLOWING;
    }
}
