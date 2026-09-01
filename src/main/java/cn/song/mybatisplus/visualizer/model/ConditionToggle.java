package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 动态条件元数据（M12）：布尔条件重载的解析产物，决定该条件在预览面板可否勾选开关。
 *
 * @param id             稳定标识（条件树 pre-order 序号，如 "c0" / "c1"），UI 复选框 ↔ 条件关联键；
 *                       面板维护的启用集合（Set<String>）以 id 为元素
 * @param flagSource     布尔条件实参源码文本（"true"/"false"/"isActive"/"StringUtils.isNotBlank(name)"），
 *                       用于复选框 tooltip 展示；null 表示该条件非布尔重载（保留扩展位）
 * @param defaultEnabled 默认勾选态（M13 简化）：字面量 true→true、false→false；运行时布尔→true；
 *                       不再做判空语义识别与静态求值
 * @param label          人读条件文本（如 "name = '张三'" / "name = ?"），复选框行标签
 * @param valueSource    关联的未解析参数来源（该条件有值输入框时非 null，用于按 source 绑定）；
 *                       多值条件（between 等）取第一个来源，完整列表见 valueSources
 * @param valueSources   关联的未解析参数来源全集（M19.5：between 双值 → 两个输入框，每个 source 一个）
 */
public final class ConditionToggle {
    private final String id;
    private final String flagSource;
    private final boolean defaultEnabled;
    private final String label;
    private final String valueSource;
    private final List<String> valueSources;

    public ConditionToggle(String id, String flagSource, boolean defaultEnabled, String label,
                           String valueSource, List<String> valueSources) {
        this.id = id;
        this.flagSource = flagSource;
        this.defaultEnabled = defaultEnabled;
        this.label = label;
        this.valueSource = valueSource;
        this.valueSources = valueSources;
    }

    public ConditionToggle(String id, String flagSource, boolean defaultEnabled, String label) {
        this(id, flagSource, defaultEnabled, label, null, List.of());
    }

    public String getId() {
        return id;
    }

    public String getFlagSource() {
        return flagSource;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public boolean getDefaultEnabled() {
        return defaultEnabled;
    }

    public String getLabel() {
        return label;
    }

    public String getValueSource() {
        return valueSource;
    }

    public List<String> getValueSources() {
        return valueSources;
    }

    public ConditionToggle copy(String id, String flagSource, boolean defaultEnabled, String label,
                                String valueSource, List<String> valueSources) {
        return new ConditionToggle(id, flagSource, defaultEnabled, label, valueSource, valueSources);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConditionToggle)) {
            return false;
        }
        ConditionToggle that = (ConditionToggle) o;
        return defaultEnabled == that.defaultEnabled
                && Objects.equals(id, that.id)
                && Objects.equals(flagSource, that.flagSource)
                && Objects.equals(label, that.label)
                && Objects.equals(valueSource, that.valueSource)
                && Objects.equals(valueSources, that.valueSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, flagSource, defaultEnabled, label, valueSource, valueSources);
    }

    @Override
    public String toString() {
        return "ConditionToggle(id=" + id + ", flagSource=" + flagSource + ", defaultEnabled=" + defaultEnabled
                + ", label=" + label + ", valueSource=" + valueSource + ", valueSources=" + valueSources + ")";
    }
}
