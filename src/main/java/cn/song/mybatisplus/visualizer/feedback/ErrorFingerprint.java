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

import kotlin.Pair;

/**
 * 错误栈指纹与 24 小时去重（T-ErrReport-02）。
 *
 * <p>指纹 = SHA-256(异常类名 + 前 5 个非平台栈帧的「类名.方法名」 + cause 链各类名) 前 16 位——
 * 同根因同指纹，平台/JDK 帧剔除后对行号漂移不敏感；已报指纹在 24h 窗口内不再重复提交（服务端
 * 5 次/小时/IP 限流，重复帖只会挤占额度）。内存 LRU（128 条）即可，不持久化：
 * 重启后允许再报属可接受语义。</p>
 *
 * <p>T-ErrorReportRedact（T-ERR-02 补强，2026-09-19）：D1=B 起 message 全文默认不外传，
 * 首报的区分度全落在「类名 + 帧 + cause 链」上。实测常见失败形态（JDBC 驱动包装的
 * SQLException：前 5 个业务帧全是 {@code com.mysql.cj.*}，不同根因帧序列完全相同）帧序列无区分度，
 * 故把 <b>cause 链类名</b>（只取类名，{@code Caused by:} 行上的 message 一律丢弃）并入指纹材料：
 * {@code SQLException} 与 {@code SQLException → CommunicationsException} 由此分开，且不引入任何业务文本。</p>
 */
public final class ErrorFingerprint {

    /** Kotlin object 单例等价。 */
    public static final ErrorFingerprint INSTANCE = new ErrorFingerprint();

    private ErrorFingerprint() {
    }

    private static final long WINDOW_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_BUSINESS_FRAMES = 5;

    /** cause 链最多取前 3 个类名（链路更长时尾部多为包装层，区分度收益递减）。 */
    private static final int MAX_CAUSE_CLASSES = 3;

    /** 平台/JDK 帧前缀：指纹只吃业务帧（插件自身 + 目标工程），同栈同因。 */
    private static final List<String> PLATFORM_FRAME_PREFIXES = List.of(
            "com.intellij.", "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.jetbrains.");

    /** {@code at com.foo.Bar.baz(...)} 行 → 组 1 = {@code com.foo.Bar.baz}、组 2 = 左括号之后的剩余原文；非栈帧行返回 null。 */
    private static final Pattern FRAME_LINE_REGEX = Pattern.compile("^\\s*at\\s+([^\\s(]+)\\s*\\((.*)$");

    /** {@code Caused by: <类名>[: 消息]} / {@code Suppressed: <类名>[: 消息]} → 组 1 只取类名（消息不入指纹）。 */
    private static final Pattern CAUSE_LINE_REGEX = Pattern.compile("^\\s*(?:Caused by|Suppressed):\\s*([^\\s:]+)");

    /** 已报指纹 → 记录时间（accessOrder=true 的 LRU；internal 供测试断言与重置）。 */
    private static volatile LinkedHashMap<String, Long> seenEntries = new LinkedHashMap<>(16, 0.75f, true);

    /** 测试缝：直读淘汰缓存（与 master Kotlin object 内部字段访问等价）。 */
    public static java.util.Map<String, Long> seenEntriesForTest() {
        return seenEntries;
    }

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
     * 计算栈指纹（纯函数）。{@code throwableClassName} 为 null（无异常对象）时仅用栈帧与 cause 链；
     * 全部帧都被剔除或 {@code stackTraceText} 为空时退化为对异常类名（+ cause 链类名）的哈希，
     * 保证非空返回。
     */
    public static String of(String throwableClassName, String stackTraceText) {
        List<String> allFrames = businessFrames(stackTraceText);
        List<String> frames = allFrames.size() > MAX_BUSINESS_FRAMES
                ? allFrames.subList(0, MAX_BUSINESS_FRAMES)
                : allFrames;
        List<String> allCauses = causeChainClasses(stackTraceText);
        List<String> causes = allCauses.size() > MAX_CAUSE_CLASSES
                ? allCauses.subList(0, MAX_CAUSE_CLASSES)
                : allCauses;
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
        for (String cause : causes) {
            if (material.length() > 0) {
                material.append('\n');
            }
            material.append(cause);
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

    /** 从堆栈文本提取非平台业务帧的「类名.方法名」（纯函数，internal 供测试）。 */
    public static List<String> businessFrames(String stackTraceText) {
        List<String> result = new java.util.ArrayList<>();
        for (Pair<String, String> part : frameParts(stackTraceText)) {
            result.add(part.getFirst());
        }
        return result;
    }

    /**
     * 从堆栈文本提取非平台业务帧的<b>整行位置形态</b> {@code 类.方法(文件:行)}（纯函数，internal 供测试）——
     * T-ErrorReportRedact 的描述预填（栈帧摘要）用：指纹只要「类.方法」（防行号漂移），
     * 但给人看的摘要需要行号才能定位。与 {@link #businessFrames} 共用同一套解析与过滤，杜绝两处口径漂移。
     */
    public static List<String> businessFrameLines(String stackTraceText) {
        // 组 2 = 左括号之后**原样**的剩余文本（含右括号；老正则只要求左括号，容忍 `at a.B.c(` 残缺行），
        // 拼回时只补左括号即可，多补一个右括号会把 `(...)` 拼成 `(...))`
        List<String> result = new java.util.ArrayList<>();
        for (Pair<String, String> part : frameParts(stackTraceText)) {
            result.add(part.getFirst() + "(" + part.getSecond());
        }
        return result;
    }

    /**
     * 从堆栈文本提取 cause 链类名（纯函数，internal 供测试）：{@code Caused by:}/{@code Suppressed:} 行只取类名，
     * 行上的 message 一律丢弃——指纹材料<b>永不包含业务文本</b>（D1=B 的隐私面）。
     */
    public static List<String> causeChainClasses(String stackTraceText) {
        if (stackTraceText == null || stackTraceText.isBlank()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (String line : stackTraceText.split("\\R", -1)) {
            Matcher matcher = CAUSE_LINE_REGEX.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String className = matcher.group(1).trim();
            if (!className.isBlank()) {
                result.add(className);
            }
        }
        return result;
    }

    /** 帧解析 + 平台前缀过滤（{@link #businessFrames}/{@link #businessFrameLines} 唯一事实源）。 */
    private static List<Pair<String, String>> frameParts(String stackTraceText) {
        if (stackTraceText == null || stackTraceText.isBlank()) {
            return List.of();
        }
        List<Pair<String, String>> result = new java.util.ArrayList<>();
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
                result.add(new Pair<>(frame, matcher.group(2)));
            }
        }
        return result;
    }
}
