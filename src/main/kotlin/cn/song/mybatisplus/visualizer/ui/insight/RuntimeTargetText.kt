package cn.song.mybatisplus.visualizer.ui.insight

internal fun runtimeTargetText(identity: String?, text: (String) -> String): String {
    val key = when {
        identity?.startsWith("jdbc-target:") == true -> "runtime.targetKnown"
        identity?.startsWith("unknown-connection:") == true -> "runtime.targetConnectionUnknown"
        identity?.startsWith("unknown-event:") == true -> "runtime.targetEventUnknown"
        else -> "runtime.targetUnknown"
    }
    return text(key) + (identity?.let { " [${it.take(96)}]" } ?: "")
}
