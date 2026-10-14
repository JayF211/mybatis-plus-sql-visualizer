package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.ui.SimpleTextAttributes

internal object SqlEntryPresentation {
    private val secondaryAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

    fun metadata(
        entry: LogSqlEntry,
        selected: Boolean,
        showDuration: Boolean,
        templateCounts: Map<String, Int>,
        dedupeCounts: Map<String, Int> = emptyMap(),
        showLogDetails: Boolean = false,
    ): List<SqlTextFragment> = buildList {
        fun addFragment(fragment: Pair<String, SimpleTextAttributes>?) {
            if (fragment != null) add(SqlTextFragment(fragment.first, fragment.second))
        }

        if (entry.sequenceNumber > 0) add(SqlTextFragment("#${entry.sequenceNumber} ", secondaryAttributes))
        addFragment(formatTimestampFragment(entry))
        if (showDuration) {
            val duration = formatDurationFragment(entry.executionTimeMs, selected)
            val threshold = PluginSettings.getInstance().state.slowQueryThresholdMs
            addFragment(if (!selected && (entry.executionTimeMs ?: 0) < threshold / 2) {
                duration.first to secondaryAttributes
            } else duration)
        }
        addFragment(formatDatabaseFragment(entry, selected))
        addFragment(formatMcpFragment(entry, selected))
        if (showLogDetails) {
            addFragment(formatUpdateCountFragment(entry, selected))
            addFragment(formatErrorFragment(entry))
            dedupeCounts[entry.dedupeKey]?.takeIf { it > 0 }?.let {
                add(SqlTextFragment("⨉$it ", secondaryAttributes))
            }
        }
        addFragment(formatTemplateCountFragment(entry, templateCounts, selected))
        addFragment(formatSourceFragment(entry, selected))
    }
}

internal fun clipSqlFragments(
    fragments: List<SqlTextFragment>,
    maxWidth: Int,
    measure: (String, SimpleTextAttributes) -> Int,
): List<SqlTextFragment> {
    if (maxWidth <= 0 || fragments.isEmpty()) return fragments
    var total = 0
    for (fragment in fragments) total += measure(fragment.text, fragment.attributes)
    if (total <= maxWidth) return fragments

    val ellipsis = "…"
    val clipped = ArrayList<SqlTextFragment>(fragments.size)
    var used = 0
    for (fragment in fragments) {
        val available = maxWidth - used - measure(ellipsis, fragment.attributes)
        if (available <= 0) break
        val text = fragment.text
        if (measure(text, fragment.attributes) <= available) {
            clipped.add(fragment)
            used += measure(text, fragment.attributes)
            continue
        }
        var low = 0
        var high = text.codePointCount(0, text.length)
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val prefix = text.substring(0, text.offsetByCodePoints(0, mid))
            if (measure(prefix, fragment.attributes) <= available) low = mid else high = mid - 1
        }
        if (low > 0) clipped.add(SqlTextFragment(text.substring(0, text.offsetByCodePoints(0, low)), fragment.attributes))
        used += if (low > 0) measure(clipped.last().text, fragment.attributes) else 0
        break
    }
    val attrs = clipped.lastOrNull()?.attributes ?: fragments.first().attributes
    if (used + measure(ellipsis, attrs) <= maxWidth) clipped.add(SqlTextFragment(ellipsis, attrs))
    return clipped
}
