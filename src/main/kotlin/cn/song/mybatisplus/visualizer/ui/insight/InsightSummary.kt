package cn.song.mybatisplus.visualizer.ui.insight

/** Plain text only, 16 KiB UTF-8 and at most 200 tree items. */
internal fun boundedInsightSummary(presentation: InsightPresentation): String {
    val output = StringBuilder()
    var bytes = 0
    fun append(value: String) {
        for (codePoint in value.codePoints().toArray()) {
            val part = String(Character.toChars(codePoint))
            val size = part.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 16 * 1024) return
            output.append(part); bytes += size
        }
    }
    append(presentation.summary.take(4096) + "\n")
    val queue = java.util.ArrayDeque<InsightItem>()
    queue.addAll(presentation.items)
    var count = 0
    while (queue.isNotEmpty() && count++ < 200 && bytes < 16 * 1024) {
        val item = queue.removeFirst()
        append(item.text.take(512) + "\n")
        queue.addAll(item.children.take((200 - queue.size).coerceAtLeast(0)))
    }
    return output.toString()
}
