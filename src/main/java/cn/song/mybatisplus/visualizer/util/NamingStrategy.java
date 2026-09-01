package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

/**
 * 表名 / 列名命名策略（F-13）。
 *
 * MyBatis-Plus 默认将实体驼峰字段名转换为数据库下划线列名（如 shareSchemeId → share_scheme_id）。
 * M1 实现默认策略；后续（M5 配置系统）接入可配置策略。
 */
public final class NamingStrategy {

    /** 单例（Kotlin object 迁移；Java 侧经 {@link #INSTANCE} 访问）。 */
    public static final NamingStrategy INSTANCE = new NamingStrategy();

    private NamingStrategy() {
    }

    /**
     * 驼峰命名转下划线小写：`shareSchemeId` → `share_scheme_id`。
     *
     * 规则：大写字母（非首字符）前插入 `_`，全部转小写。
     * 已知边界：连续缩写（如 `stationID`）会得到 `station_i_d`，与 MP 默认行为一致，可接受。
     *
     * @param camelCase 驼峰命名（实体字段名或类名）
     * @return 下划线小写命名
     */
    @NotNull
    public String toSnakeCase(@NotNull String camelCase) {
        if (camelCase.isEmpty()) return camelCase;
        StringBuilder sb = new StringBuilder(camelCase.length() + 8);
        for (int index = 0; index < camelCase.length(); index++) {
            char ch = camelCase.charAt(index);
            if (Character.isUpperCase(ch) && index > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }
}
