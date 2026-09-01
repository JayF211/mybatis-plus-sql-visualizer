package cn.song.mybatisplus.visualizer.xml;

import java.util.Map;
import java.util.Set;

/**
 * XML 预览渲染状态（面板勾选产物）：
 *
 * @param enabledIds    启用中的条件开关 id（test 表达式原文）
 * @param forEachCounts collection 表达式 → 展开次数（0 = 空集合 → 整段空输出）
 * @param forEachNulls  勾选为 null 的 collection 表达式集合（nullable=false 时渲染错误提示）
 * @param bindOverrides bind 名 → 覆盖值（优先于表达式求值）
 */
public record XmlPreviewState(
        Set<String> enabledIds,
        Map<String, Integer> forEachCounts,
        Set<String> forEachNulls,
        Map<String, String> bindOverrides
) {
}
