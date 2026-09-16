package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;

/**
 * 极简 JSON 写出（agent 内禁外部依赖）：只需对象/字符串/数字/数组/布尔，
 * 写侧自己构造故无需解析器。转义覆盖引号/反斜杠/控制字符/Unicode 控制区。
 */
public final class Jsons {

    public static String writeEvent(SqlEvent e) {
        final StringBuilder sb = new StringBuilder(256);
        // 首字段标志：第一个成员前不得有逗号（否则 IDE 侧 JSON 解析全量失败——
        // 实证 {,"sql":...} 被丢弃，事件零到达）
        final boolean[] first = {true};
        sb.append('{');
        str(sb, first, "sql", e.sql);
        num(sb, first, "ts", e.timestamp);
        num(sb, first, "dn", e.durationNanos);
        if (e.updateCount != null) {
            num(sb, first, "u", e.updateCount);
        }
        str(sb, first, "err", e.errorText);
        str(sb, first, "sf", e.sourceFile);
        if (e.sourceLine > 0) {
            num(sb, first, "sl", e.sourceLine);
        }
        str(sb, first, "mk", e.methodKey);
        str(sb, first, "rt", e.requestTag);
        str(sb, first, "th", e.threadName);
        str(sb, first, "url", e.url);
        if (e.params != null && !e.params.isEmpty()) {
            sep(sb, first);
            sb.append("\"p\":[");
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

    /** 成员分隔：首成员无逗号，后续成员补逗号。 */
    private static void sep(StringBuilder sb, boolean[] first) {
        if (first[0]) {
            first[0] = false;
        } else {
            sb.append(',');
        }
    }

    private static void str(StringBuilder sb, boolean[] first, String key, String value) {
        if (value != null) {
            sep(sb, first);
            sb.append('"').append(key).append("\":");
            val(sb, value);
        }
    }

    private static void num(StringBuilder sb, boolean[] first, String key, long value) {
        sep(sb, first);
        sb.append('"').append(key).append("\":").append(value);
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
