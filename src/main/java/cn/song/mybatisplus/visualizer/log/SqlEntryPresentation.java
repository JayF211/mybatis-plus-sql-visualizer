package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpvsUiColors;
import com.intellij.ui.SimpleTextAttributes;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;
import javax.swing.JList;
import javax.swing.JPanel;

/** 两个日志列表共用的元信息、裁剪与选中样式。 */
public final class SqlEntryPresentation {
    private static final SimpleTextAttributes SECONDARY = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY);
    private SqlEntryPresentation() {}

    public static List<SqlTextFragment> metadata(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts) {
        return metadata(entry, selected, showDuration, templateCounts, Map.of(), false);
    }

    public static List<SqlTextFragment> metadata(LogSqlEntry entry, boolean selected, boolean showDuration,
            Map<String, Integer> templateCounts, Map<String, Integer> dedupeCounts, boolean showLogDetails) {
        List<SqlTextFragment> result = new ArrayList<>();
        if (entry.sequenceNumber() > 0) result.add(new SqlTextFragment("#" + entry.sequenceNumber() + " ", SECONDARY));
        add(result, SqlLogPanelKt.formatTimestampFragment(entry));
        if (showDuration) add(result, SqlLogPanelKt.formatDurationFragment(entry.executionTimeMs(), selected));
        add(result, SqlLogPanelKt.formatDatabaseFragment(entry, selected));
        add(result, SqlLogPanelKt.formatMcpFragment(entry, selected));
        if (showLogDetails) {
            add(result, SqlLogPanelKt.formatUpdateCountFragment(entry, selected));
            add(result, SqlLogPanelKt.formatErrorFragment(entry));
            // UI013：同键的其他来源可能有丢弃计数，不能把徽章借给未参与去重的 Sharding 执行。
            Integer count = entry.source() == SqlSource.SHARDINGSPHERE
                    ? null : dedupeCounts.get(entry.dedupeKey());
            if (count != null && count > 0) result.add(new SqlTextFragment("⨉" + count + " ", SECONDARY));
        }
        add(result, SqlLogPanelKt.formatTemplateCountFragment(entry, templateCounts, selected));
        add(result, SqlLogPanelKt.formatSourceFragment(entry, selected));
        return result;
    }

    private static void add(List<SqlTextFragment> target, kotlin.Pair<String, SimpleTextAttributes> fragment) {
        if (fragment != null) target.add(new SqlTextFragment(fragment.getFirst(), fragment.getSecond()));
    }

    public static List<List<SqlTextFragment>> visibleSqlLines(List<List<SqlTextFragment>> lines, SqlListDisplayMode mode) {
        if (mode != SqlListDisplayMode.KEYWORD_WRAP || lines.size() <= 3) return lines;
        List<List<SqlTextFragment>> visible = new ArrayList<>(lines.subList(0, 2));
        visible.add(List.of(new SqlTextFragment("… (+" + (lines.size() - 2) + ")", SimpleTextAttributes.REGULAR_ATTRIBUTES)));
        return visible;
    }

    public static SimpleTextAttributes selectedAttributes(SimpleTextAttributes attributes, Color foreground) {
        return new SimpleTextAttributes(attributes.getStyle(), foreground);
    }

    public static List<SqlTextFragment> clipForList(List<SqlTextFragment> fragments, JList<?> list,
            JPanel cell, int colorBarWidth) {
        if (list == null || list.getWidth() <= 0 || list.getFont() == null) return fragments;
        int width = list.getWidth() - cell.getInsets().left - cell.getInsets().right - colorBarWidth
                - list.getInsets().left - list.getInsets().right;
        Font base = list.getFont();
        Map<Integer, FontMetrics> metrics = new HashMap<>(3);
        return clipSqlFragments(fragments, width, (text, attributes) -> {
            int style = ((attributes.getStyle() & SimpleTextAttributes.STYLE_BOLD) != 0 ? Font.BOLD : 0)
                    | ((attributes.getStyle() & SimpleTextAttributes.STYLE_ITALIC) != 0 ? Font.ITALIC : 0);
            return metrics.computeIfAbsent(style, key -> list.getFontMetrics(base.deriveFont(key))).stringWidth(text);
        });
    }

    public static List<SqlTextFragment> clipSqlFragments(List<SqlTextFragment> fragments, int maxWidth,
            ToIntBiFunction<String, SimpleTextAttributes> measure) {
        if (maxWidth <= 0 || fragments.isEmpty()) return fragments;
        int total = 0;
        for (SqlTextFragment fragment : fragments) total += measure.applyAsInt(fragment.text(), fragment.attributes());
        if (total <= maxWidth) return fragments;
        String ellipsis = "…";
        List<SqlTextFragment> clipped = new ArrayList<>(fragments.size());
        int used = 0;
        for (SqlTextFragment fragment : fragments) {
            int available = maxWidth - used - measure.applyAsInt(ellipsis, fragment.attributes());
            if (available <= 0) break;
            String text = fragment.text();
            int measured = measure.applyAsInt(text, fragment.attributes());
            if (measured <= available) {
                clipped.add(fragment);
                used += measured;
                continue;
            }
            int low = 0;
            int high = text.codePointCount(0, text.length());
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                String prefix = text.substring(0, text.offsetByCodePoints(0, mid));
                if (measure.applyAsInt(prefix, fragment.attributes()) <= available) low = mid;
                else high = mid - 1;
            }
            if (low > 0) {
                String prefix = text.substring(0, text.offsetByCodePoints(0, low));
                clipped.add(new SqlTextFragment(prefix, fragment.attributes()));
                used += measure.applyAsInt(prefix, fragment.attributes());
            }
            break;
        }
        SimpleTextAttributes attrs = clipped.isEmpty() ? fragments.get(0).attributes() : clipped.get(clipped.size() - 1).attributes();
        if (used + measure.applyAsInt(ellipsis, attrs) <= maxWidth) clipped.add(new SqlTextFragment(ellipsis, attrs));
        return clipped;
    }
}
