package cn.song.mybatisplus.sqlvis.agent.util;

/**
 * 采集层脱敏（§4.5，形态对齐 BatsLog desensitize）：数据不出应用 JVM 前已处理。
 * 双条件打码——①列名/参数名提示命中敏感关键词形态（password/token/secret/身份证/手机号等）；
 * ②值形态（18 位身份证 / 11 位手机号）——列提示缺失时兜底。打码结果固定 "***"。
 */
public final class Desensitizer {

    private static final String MASK = "***";

    private static final String[] KEYWORDS = {
            "password", "passwd", "pwd", "token", "secret", "credential", "apikey", "api_key",
            "access_key", "private_key", "idcard", "id_card", "mobile", "phone",
            "身份证", "手机号", "密码", "密钥", "令牌",
    };

    /** 列名提示 + 值形态双条件打码；desensitize=false 时原样返回（设置页开关）。 */
    public static String mask(String columnHint, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (columnHint != null) {
            final String hint = columnHint.toLowerCase();
            for (String k : KEYWORDS) {
                if (hint.contains(k)) {
                    return MASK;
                }
            }
        }
        if (looksLikeIdCard(value) || looksLikeMobile(value)) {
            return MASK;
        }
        return value;
    }

    static boolean looksLikeIdCard(String v) {
        if (v.length() != 18) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        final char last = v.charAt(17);
        return Character.isDigit(last) || last == 'X' || last == 'x';
    }

    static boolean looksLikeMobile(String v) {
        if (v.length() != 11) {
            return false;
        }
        for (int i = 0; i < 11; i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        return v.charAt(0) == '1';
    }

    private Desensitizer() {
    }
}
