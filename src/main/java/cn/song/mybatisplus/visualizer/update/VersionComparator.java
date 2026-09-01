package cn.song.mybatisplus.visualizer.update;

import java.util.Arrays;
import java.util.List;

/**
 * 版本号比较（M28）。
 *
 * 插件对外版本号为 `YYYY.M.D`（gradle.properties，一日一版；版本号规范禁止同日递增后缀）——
 * 点分数字逐段比较即可。非数字段按字符串比较兜底；任何异常不抛出（比较异常一律降级为
 * 「不更新」，不打扰用户）。
 */
public final class VersionComparator {

    public static final VersionComparator INSTANCE = new VersionComparator();
    private VersionComparator() {}

    /**
     * 比较两个版本号：`a > b` 返回正数，`a == b` 返回 0，`a < b` 返回负数。
     * null 视为小于非 null；同为 null 相等。非数字段（如 `1.a` vs `1.b`）按字符串比较。
     */
    public int compare(String a, String b) {
        if (a == null || b == null) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            return 1;
        }
        List<String> aSegments = splitDot(a);
        List<String> bSegments = splitDot(b);
        int max = Math.max(aSegments.size(), bSegments.size());
        for (int i = 0; i < max; i++) {
            String x = i < aSegments.size() ? aSegments.get(i) : null;
            String y = i < bSegments.size() ? bSegments.get(i) : null;
            if (x == null ? y == null : x.equals(y)) continue;
            Integer xn = x != null ? toIntOrNull(x) : null;
            Integer yn = y != null ? toIntOrNull(y) : null;
            int c;
            if (xn != null && yn != null) {
                c = Integer.compare(xn, yn);
            } else {
                // 非数字段（或缺失段按空串）——字符串比较兜底
                c = (x != null ? x : "").compareTo(y != null ? y : "");
            }
            if (c != 0) return c;
        }
        return 0;
    }

    /** 版本号是否可识别（非空白且至少一段）。 */
    public boolean isRecognizable(String version) {
        if (version == null || version.isBlank()) return false;
        for (String seg : splitDot(version)) {
            if (!seg.isBlank()) return true;
        }
        return false;
    }

    /** 按 `.` 分割（与 Kotlin `split('.')` 一致：丢弃尾部空段）。 */
    private static List<String> splitDot(String s) {
        return Arrays.asList(s.split("\\."));
    }

    /** Kotlin `toIntOrNull` 等价：无法解析时返回 null。 */
    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
