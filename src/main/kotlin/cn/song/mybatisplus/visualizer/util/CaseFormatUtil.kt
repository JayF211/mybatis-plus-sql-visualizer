package cn.song.mybatisplus.visualizer.util

/**
 * P-05.1：驼峰 / 下划线互转工具——解决数据库列名（snake_case）与 Java 字段名（camelCase）
 * 命名风格不一致导致导入参数无法匹配的问题。
 *
 * 纯函数、无依赖，可独立单测。
 */
object CaseFormatUtil {

    /**
     * camelCase → snake_case。
     *
     * 例：`userName` → `user_name`，`createTime` → `create_time`。
     * 在小写字母/数字与大写字母之间插入下划线并转小写。
     */
    fun camelToSnake(input: String): String =
        input.replace(Regex("([a-z0-9])([A-Z])")) {
            "${it.groupValues[1]}_${it.groupValues[2].lowercase()}"
        }

    /**
     * snake_case → camelCase。
     *
     * 例：`user_name` → `userName`，`create_time` → `createTime`。
     * 取下划线后的字符转大写并去掉下划线。
     */
    fun snakeToCamel(input: String): String =
        input.replace(Regex("_([a-z0-9])")) {
            it.groupValues[1].uppercase()
        }
}
