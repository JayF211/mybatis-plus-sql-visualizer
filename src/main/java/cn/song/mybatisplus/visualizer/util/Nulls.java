package cn.song.mybatisplus.visualizer.util;

import java.util.Collection;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Kotlin 空安全 / 常用顶层函数的 Java 等价工具（迁移期统一替换点）。
 * 仅做语义等价，不改变行为；其余 Kotlin 惯用法（?. ?: let/apply 等）由各文件就地展开。
 */
public final class Nulls {

    private Nulls() {}

    // ---- isNullOrXxx ----

    public static boolean isNullOrEmpty(@Nullable CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean isNotNullOrEmpty(@Nullable CharSequence s) {
        return !isNullOrEmpty(s);
    }

    public static boolean isNullOrBlank(@Nullable CharSequence s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false;
        }
        return true;
    }

    public static boolean isNotNullOrBlank(@Nullable CharSequence s) {
        return !isNullOrBlank(s);
    }

    public static boolean isNullOrEmpty(@Nullable Collection<?> c) {
        return c == null || c.isEmpty();
    }

    public static boolean isNullOrEmpty(@Nullable Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    // ---- 空值兜底 / 合并 ----

    @Nullable
    public static <T> T firstNonNull(@Nullable T a, @Nullable T b) {
        return a != null ? a : b;
    }

    @Nullable
    public static <T> T firstNonNull(@Nullable T a, @Nullable T b, @Nullable T c) {
        if (a != null) return a;
        if (b != null) return b;
        return c;
    }

    @NotNull
    public static <T> T requireNonNullElse(@Nullable T value, @NotNull T defaultVal) {
        return value != null ? value : defaultVal;
    }

    // ---- 数值解析（Kotlin toIntOrNull / toLongOrNull）----

    @Nullable
    public static Integer toIntOrNull(@Nullable String s) {
        if (isNullOrBlank(s)) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    public static Long toLongOrNull(@Nullable String s) {
        if (isNullOrBlank(s)) return null;
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    public static Double toDoubleOrNull(@Nullable String s) {
        if (isNullOrBlank(s)) return null;
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 安全相等 ----

    public static boolean equalsSafe(@Nullable Object a, @Nullable Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
