package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;

/**
 * 极简 JSON 写出（agent 内禁外部依赖）：只需对象/字符串/数字/数组/布尔，
 * 写侧自己构造故无需解析器。转义覆盖引号/反斜杠/控制字符/Unicode 控制区。
 */
public final class Jsons {

    public static String writeEvent(SqlEvent e) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        str(sb, "sql", e.sql);
        sb.append(",\"ts\":").append(e.timestamp);
        sb.append(",\"dn\":").append(e.durationNanos);
        if (e.updateCount != null) {
            sb.append(",\"u\":").append(e.updateCount);
        }
        str(sb, "err", e.errorText);
        str(sb, "sf", e.sourceFile);
        if (e.sourceLine > 0) {
            sb.append(",\"sl\":").append(e.sourceLine);
        }
        str(sb, "mk", e.methodKey);
        str(sb, "rt", e.requestTag);
        str(sb, "th", e.threadName);
        str(sb, "url", e.url);
        if (e.params != null && !e.params.isEmpty()) {
            sb.append(",\"p\":[");
            for (int i = 0; i < e.params.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String[] p = e.params.get(i);
                sb.append('[').append(p[0]).append(',');
                val(sb, p.length > 1 ? p[1] : null);
                sb.append(',');
                val(sb, p.length > 2 ? p[2] : null);
                sb.append(']');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static void str(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(",\"").append(key).append("\":");
            val(sb, value);
        }
    }

    private static void val(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private Jsons() {
    }
}
