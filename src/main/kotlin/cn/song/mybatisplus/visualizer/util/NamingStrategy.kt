package cn.song.mybatisplus.visualizer.util

/**
 * 表名 / 列名命名策略（F-13）。
 *
 * MyBatis-Plus 默认将实体驼峰字段名转换为数据库下划线列名（如 shareSchemeId → share_scheme_id）。
 * M1 实现默认策略；后续（M5 配置系统）接入可配置策略。
 */
object NamingStrategy {

    /**
     * 驼峰命名转下划线小写：`shareSchemeId` → `share_scheme_id`。
     *
     * 规则：大写字母（非首字符）前插入 `_`，全部转小写。
     * 已知边界：连续缩写（如 `stationID`）会得到 `station_i_d`，与 MP 默认行为一致，可接受。
     *
     * @param camelCase 驼峰命名（实体字段名或类名）
     * @return 下划线小写命名
     */
    fun toSnakeCase(camelCase: String): String {
        if (camelCase.isEmpty()) return camelCase
        val sb = StringBuilder(camelCase.length + 8)
        camelCase.forEachIndexed { index, ch ->
            if (ch.isUpperCase() && index > 0) {
                sb.append('_')
            }
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }
}
