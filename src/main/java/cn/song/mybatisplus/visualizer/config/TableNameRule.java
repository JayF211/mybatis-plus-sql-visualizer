package cn.song.mybatisplus.visualizer.config;

import java.util.Objects;

/**
 * 动态表名映射规则（F-6 Phase B）：预览时按正则替换表名。
 *
 * @param pattern     正则表达式（匹配原始表名或带前缀/后缀的表名）
 * @param replacement 替换模板（支持 `$1`/`$2` 引用正则捕获组，`$tableName` 引用完整原始表名）
 * @param enabled     是否启用（false 时跳过该规则）
 */
public final class TableNameRule {

    private final String pattern;
    private final String replacement;
    private final boolean enabled;

    public TableNameRule(String pattern, String replacement) {
        this(pattern, replacement, true);
    }

    public TableNameRule(String pattern, String replacement, boolean enabled) {
        this.pattern = pattern;
        this.replacement = replacement;
        this.enabled = enabled;
    }

    public String getPattern() {
        return pattern;
    }

    public String getReplacement() {
        return replacement;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Java Bean 别名（与原 Kotlin `var enabled` 生成的 getEnabled 一致）。 */
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableNameRule)) return false;
        TableNameRule that = (TableNameRule) o;
        return enabled == that.enabled
                && Objects.equals(pattern, that.pattern)
                && Objects.equals(replacement, that.replacement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, replacement, enabled);
    }

    @Override
    public String toString() {
        return "TableNameRule(pattern=" + pattern + ", replacement=" + replacement + ", enabled=" + enabled + ")";
    }
}
