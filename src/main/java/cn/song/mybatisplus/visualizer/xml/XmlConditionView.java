package cn.song.mybatisplus.visualizer.xml;

/**
 * XML 预览条件行（T-M24-04）：一个可勾选开关的动态条件（if / choose-when）。
 *
 * @param id             条件标识 = test 表达式原文（启用集合元素；同表达式多处出现共享开关）
 * @param label          人读条件文本（复选框标签 = 表达式）
 * @param defaultEnabled 默认勾选态（启发式：含 == null → false，其余 true——保守展开）
 */
public record XmlConditionView(String id, String label, boolean defaultEnabled) {
}
