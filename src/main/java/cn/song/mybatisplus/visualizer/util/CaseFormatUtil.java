package cn.song.mybatisplus.visualizer.util;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P-05.1：驼峰 / 下划线互转工具——解决数据库列名（snake_case）与 Java 字段名（camelCase）
 * 命名风格不一致导致导入参数无法匹配的问题。
 *
 * 纯函数、无依赖，可独立单测。
 */
public final class CaseFormatUtil {

    private static final Pattern CAMEL_SNAKE = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SNAKE_CAMEL = Pattern.compile("_([a-z0-9])");

    private CaseFormatUtil() {
    }

    /**
     * camelCase → snake_case。
     *
     * 例：`userName` → `user_name`，`createTime` → `create_time`。
     * 在小写字母/数字与大写字母之间插入下划线并转小写。
     */
    @NotNull
    public static String camelToSnake(@NotNull String input) {
        Matcher m = CAMEL_SNAKE.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1) + "_" + m.group(2).toLowerCase());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * snake_case → camelCase。
     *
     * 例：`user_name` → `userName`，`create_time` → `createTime`。
     * 取下划线后的字符转大写并去掉下划线。
     */
    @NotNull
    public static String snakeToCamel(@NotNull String input) {
        Matcher m = SNAKE_CAMEL.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1).toUpperCase());
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
