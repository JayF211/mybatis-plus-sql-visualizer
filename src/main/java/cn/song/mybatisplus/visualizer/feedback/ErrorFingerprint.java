package cn.song.mybatisplus.visualizer.feedback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误栈指纹与 24 小时去重（T-ErrReport-02）。
 *
 * <p>指纹 = SHA-256(异常类名 + 前 5 个非平台栈帧的「类名.方法名」) 前 16 位——同根因同指纹，
 * 平台/JDK 帧剔除后对行号漂移不敏感；已报指纹在 24h 窗口内不再重复提交（服务端
 * 5 次/小时/IP 限流，重复帖只会挤占额度）。内存 LRU（128 条）即可，不持久化：
 * 重启后允许再报属可接受语义。
 */
public final class ErrorFingerprint {

    /** Kotlin object 单例等价。 */
    public static final ErrorFingerprint INSTANCE = new ErrorFingerprint();

    private ErrorFingerprint() {
    }

    private static final long WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_BUSINESS_FRAMES = 5;

    /** 平台/JDK 帧前缀：指纹只吃业务帧（插件自身 + 目标工程），同栈同因。 */
    private static final List<String> PLATFORM_FRAME_PREFIXES = List.of(
            "com.intellij.", "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.jetbrains.");

    /** {@code at com.foo.Bar.baz(...)} 行 → {@code com.foo.Bar.baz}；非栈帧行返回 null。 */
    private static final Pattern FRAME_LINE_REGEX = Pattern.compile("^\\s*at\\s+([^\\s(]+)\\s*\\(");

    /** 已报指纹 → 记录时间（accessOrder=true 的 LRU；internal 供测试断言与重置）。 */
    private static volatile LinkedHashMap<String, Long> seenEntries = new LinkedHashMap<>(16, 0.75f, true);

    /** 时钟 seam（测试注入固定时间）。 */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    public static LinkedHashMap<String, Long> getSeenEntries() {
        return seenEntries;
    }

    public static void setSeenEntries(LinkedHashMap<String, Long> entries) {
        seenEntries = entries;
    }

    public static LongSupplier getClock() {
        return clock;
    }

    public static void setClock(LongSupplier supplier) {
        clock = supplier;
    }

    /**
     * 计算栈指纹（纯函数）。{@code throwableClassName} 为 null（无异常对象）时仅用栈帧；
     * 全部帧都被剔除或 {@code stackTraceText} 为空时退化为对异常类名的哈希，保证非空返回。
     */
    public static String of(String throwableClassName, String stackTraceText) {
        List<String> allFrames = businessFrames(stackTraceText);
        List<String> frames = allFrames.size() > MAX_BUSINESS_FRAMES
                ? allFrames.subList(0, MAX_BUSINESS_FRAMES)
                : allFrames;
        StringBuilder material = new StringBuilder();
        if (throwableClassName != null) {
            material.append(throwableClassName);
        }
        for (String frame : frames) {
            if (material.length() > 0) {
                material.append('\n');
            }
            material.append(frame);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // JVM 必备算法，不可达；防御性退化常量指纹
            return "0000000000000000";
        }
    }

    /** {@code fingerprint} 在 24h 窗口内已报过则 true（顺带清理过期项）。 */
    public static synchronized boolean seenRecently(String fingerprint) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> iterator = seenEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() > WINDOW_MS) {
                iterator.remove();
            }
        }
        return seenEntries.containsKey(fingerprint);
    }

    /** 记录已报指纹（提交发起时调用；LRU 超 128 条淘汰最旧）。 */
    public static synchronized void record(String fingerprint) {
        seenEntries.put(fingerprint, clock.getAsLong());
        Iterator<Map.Entry<String, Long>> iterator = seenEntries.entrySet().iterator();
        while (seenEntries.size() > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /** 测试重置。 */
    public static synchronized void reset() {
        seenEntries = new LinkedHashMap<>(16, 0.75f, true);
        clock = System::currentTimeMillis;
    }

    /** 从堆栈文本提取非平台业务帧（纯函数，internal 供测试）。 */
    public static List<String> businessFrames(String stackTraceText) {
        if (stackTraceText == null || stackTraceText.isBlank()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (String line : stackTraceText.split("\\R", -1)) {
            Matcher matcher = FRAME_LINE_REGEX.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String frame = matcher.group(1);
            boolean platform = false;
            for (String prefix : PLATFORM_FRAME_PREFIXES) {
                if (frame.startsWith(prefix)) {
                    platform = true;
                    break;
                }
            }
            if (!platform) {
                result.add(frame);
            }
        }
        return result;
    }
}
