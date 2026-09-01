package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * 动态条件行（M12）：一个可勾选开关的动态条件。
 *
 * @param id             条件标识（与 ConditionToggle.id 一致，启用集合元素）
 * @param label          人读条件文本（复选框标签）
 * @param flagSource     布尔条件实参源码（tooltip），null 表示非布尔重载
 * @param defaultEnabled 默认勾选态
 * @param valueSource    关联值输入框来源（首值）；null 表示 checkbox-only（无输入框）
 * @param valueSources   关联值输入框来源全集（M19.5：between 双值 → 两个输入框，每个 source 一个）
 */
public final class ConditionView {
    private final String id;
    private final String label;
    private final String flagSource;
    private final boolean defaultEnabled;
    private final String valueSource;
    private final List<String> valueSources;

    public ConditionView(String id, String label, String flagSource, boolean defaultEnabled,
                         String valueSource, List<String> valueSources) {
        this.id = id;
        this.label = label;
        this.flagSource = flagSource;
        this.defaultEnabled = defaultEnabled;
        this.valueSource = valueSource;
        this.valueSources = valueSources;
    }

    public ConditionView(String id, String label, String flagSource, boolean defaultEnabled,
                         String valueSource) {
        this(id, label, flagSource, defaultEnabled, valueSource, List.of());
    }

    public ConditionView(String id, String label, String flagSource, boolean defaultEnabled) {
        this(id, label, flagSource, defaultEnabled, null, List.of());
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
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

    public String getValueSource() {
        return valueSource;
    }

    public List<String> getValueSources() {
        return valueSources;
    }

    public ConditionView copy(String id, String label, String flagSource, boolean defaultEnabled,
                              String valueSource, List<String> valueSources) {
        return new ConditionView(id, label, flagSource, defaultEnabled, valueSource, valueSources);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConditionView)) {
            return false;
        }
        ConditionView that = (ConditionView) o;
        return defaultEnabled == that.defaultEnabled
                && Objects.equals(id, that.id)
                && Objects.equals(label, that.label)
                && Objects.equals(flagSource, that.flagSource)
                && Objects.equals(valueSource, that.valueSource)
                && Objects.equals(valueSources, that.valueSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, label, flagSource, defaultEnabled, valueSource, valueSources);
    }

    @Override
    public String toString() {
        return "ConditionView(id=" + id + ", label=" + label + ", flagSource=" + flagSource
                + ", defaultEnabled=" + defaultEnabled + ", valueSource=" + valueSource
                + ", valueSources=" + valueSources + ")";
    }
}
