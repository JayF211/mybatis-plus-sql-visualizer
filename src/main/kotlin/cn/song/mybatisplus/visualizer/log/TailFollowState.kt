package cn.song.mybatisplus.visualizer.log

internal enum class TailFollowMode { FOLLOWING, PAUSED }

internal class TailFollowState {
    var mode: TailFollowMode = TailFollowMode.FOLLOWING
        private set

    fun onUserScroll() {
        mode = TailFollowMode.PAUSED
    }

    fun onScrollToEnd() {
        mode = TailFollowMode.FOLLOWING
    }

    fun onClear() {
        mode = TailFollowMode.FOLLOWING
    }

    val shouldFollow: Boolean get() = mode == TailFollowMode.FOLLOWING
}
