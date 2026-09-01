package cn.song.mybatisplus.visualizer.xml;

import java.util.HashMap;
import java.util.Map;

/**
 * 参数映射（T-M24-01）：#{...} token 解析产物，属性名顺序对应 SQL 中 ? 顺序。
 *
 * @param property      参数属性名（foreach 内为重写后的 __frch_item_0.name 形态）
 * @param attributes    token 附加属性（jdbcType / typeHandler / javaType / mode / numericScale 等）
 * @param originalToken token 原文（面板展示用）
 */
public record ParameterMapping(String property, Map<String, String> attributes, String originalToken) {

    /** 解析 token 内容（对齐 SqlSourceBuilder.buildParameterMapping 的简化形态）。 */
    public static ParameterMapping parse(String token) {
        String property = "";
        Map<String, String> attributes = new HashMap<>();
        for (String segment : token.split(",")) {
            String part = segment.trim();
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    attributes.put(key, value);
                }
            } else if (property.isEmpty()) {
                property = part;
            }
        }
        return new ParameterMapping(property, attributes, token);
    }
}
