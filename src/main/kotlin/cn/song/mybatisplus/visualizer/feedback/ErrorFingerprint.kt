package cn.song.mybatisplus.visualizer.feedback

import java.security.MessageDigest

/**
 * 错误栈指纹与 24 小时去重（T-ErrReport-02）。
 *
 * 指纹 = SHA-256(异常类名 + 前 5 个非平台栈帧的「类名.方法名」) 前 16 位——同根因同指纹，
 * 平台/JDK 帧剔除后对行号漂移不敏感；已报指纹在 24h 窗口内不再重复提交（服务端
 * 5 次/小时/IP 限流，重复帖只会挤占额度）。内存 LRU（128 条）即可，不持久化：
 * 重启后允许再报属可接受语义。
 */
object ErrorFingerprint {

    private const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 128
    private const val MAX_BUSINESS_FRAMES = 5

    /** 平台/JDK 帧前缀：指纹只吃业务帧（插件自身 + 目标工程），同栈同因。 */
    private val PLATFORM_FRAME_PREFIXES = listOf(
        "com.intellij.", "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.jetbrains.",
    )

    /** `at com.foo.Bar.baz(...)` 行 → `com.foo.Bar.baz`；非栈帧行返回 null。 */
    private val FRAME_LINE_REGEX = Regex("""^\s*at\s+([^\s(]+)\s*\(""")

    /** 已报指纹 → 记录时间（accessOrder=true 的 LRU；internal 供测试断言与重置）。 */
    @Volatile
    internal var seenEntries = LinkedHashMap<String, Long>(16, 0.75f, true)
        private set

    /** 时钟 seam（测试注入固定时间）。 */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * 计算栈指纹（纯函数）。[throwableClassName] 为 null（无异常对象）时仅用栈帧；
     * 全部帧都被剔除或 [stackTraceText] 为空时退化为对异常类名的哈希，保证非空返回。
     */
    fun of(throwableClassName: String?, stackTraceText: String?): String {
        val frames = businessFrames(stackTraceText).take(MAX_BUSINESS_FRAMES)
        val material = listOfNotNull(throwableClassName).plus(frames).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** [fingerprint] 在 24h 窗口内已报过则 true（顺带清理过期项）。 */
    @Synchronized
    fun seenRecently(fingerprint: String): Boolean {
        val now = clock()
        val iterator = seenEntries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > WINDOW_MS) iterator.remove()
        }
        return seenEntries.containsKey(fingerprint)
    }

    /** 记录已报指纹（提交发起时调用；LRU 超 128 条淘汰最旧）。 */
    @Synchronized
    fun record(fingerprint: String) {
        seenEntries[fingerprint] = clock()
        val iterator = seenEntries.entries.iterator()
        while (seenEntries.size > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /** 测试重置。 */
    @Synchronized
    fun reset() {
        seenEntries = LinkedHashMap(16, 0.75f, true)
        clock = { System.currentTimeMillis() }
    }

    /** 从堆栈文本提取非平台业务帧（纯函数，internal 供测试）。 */
    internal fun businessFrames(stackTraceText: String?): List<String> {
        if (stackTraceText.isNullOrBlank()) return emptyList()
        return stackTraceText.lineSequence().mapNotNull { line ->
            FRAME_LINE_REGEX.find(line)?.groupValues?.get(1)
        }.filter { frame ->
            PLATFORM_FRAME_PREFIXES.none { prefix -> frame.startsWith(prefix) }
        }.toList()
    }
}
