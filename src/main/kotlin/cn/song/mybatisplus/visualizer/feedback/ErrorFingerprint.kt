package cn.song.mybatisplus.visualizer.feedback

import java.security.MessageDigest

/**
 * 错误栈指纹与 24 小时去重（T-ErrReport-02）。
 *
 * 指纹 = SHA-256(异常类名 + 前 5 个非平台栈帧的「类名.方法名」 + cause 链各类名) 前 16 位——
 * 同根因同指纹，平台/JDK 帧剔除后对行号漂移不敏感；已报指纹在 24h 窗口内不再重复提交（服务端
 * 5 次/小时/IP 限流，重复帖只会挤占额度）。内存 LRU（128 条）即可，不持久化：
 * 重启后允许再报属可接受语义。
 *
 * T-ErrorReportRedact（T-ERR-02 补强，2026-09-19）：D1=B 起 message 全文默认不外传，
 * 首报的区分度全落在「类名 + 帧 + cause 链」上。实测常见失败形态（JDBC 驱动包装的
 * SQLException：前 5 个业务帧全是 `com.mysql.cj.*`，不同根因帧序列完全相同）帧序列无区分度，
 * 故把 **cause 链类名**（只取类名，`Caused by:` 行上的 message 一律丢弃）并入指纹材料：
 * `SQLException` 与 `SQLException → CommunicationsException` 由此分开，且不引入任何业务文本。
 */
object ErrorFingerprint {

    private const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 128
    private const val MAX_BUSINESS_FRAMES = 5

    /** cause 链最多取前 3 个类名（链路更长时尾部多为包装层，区分度收益递减）。 */
    private const val MAX_CAUSE_CLASSES = 3

    /** 平台/JDK 帧前缀：指纹只吃业务帧（插件自身 + 目标工程），同栈同因。 */
    private val PLATFORM_FRAME_PREFIXES = listOf(
        "com.intellij.", "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.jetbrains.",
    )

    /** `at com.foo.Bar.baz(...)` 行 → 组 1 = `com.foo.Bar.baz`、组 2 = 左括号之后的剩余原文；非栈帧行返回 null。 */
    private val FRAME_LINE_REGEX = Regex("""^\s*at\s+([^\s(]+)\s*\((.*)$""")

    /** `Caused by: <类名>[: 消息]` / `Suppressed: <类名>[: 消息]` → 组 1 只取类名（消息不入指纹）。 */
    private val CAUSE_LINE_REGEX = Regex("""^\s*(?:Caused by|Suppressed):\s*([^\s:]+)""")

    /** 已报指纹 → 记录时间（accessOrder=true 的 LRU；internal 供测试断言与重置）。 */
    @Volatile
    internal var seenEntries = LinkedHashMap<String, Long>(16, 0.75f, true)
        private set

    /** 时钟 seam（测试注入固定时间）。 */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * 计算栈指纹（纯函数）。[throwableClassName] 为 null（无异常对象）时仅用栈帧与 cause 链；
     * 全部帧都被剔除或 [stackTraceText] 为空时退化为对异常类名（+ cause 链类名）的哈希，
     * 保证非空返回。
     */
    fun of(throwableClassName: String?, stackTraceText: String?): String {
        val frames = businessFrames(stackTraceText).take(MAX_BUSINESS_FRAMES)
        val causes = causeChainClasses(stackTraceText).take(MAX_CAUSE_CLASSES)
        val material = (listOfNotNull(throwableClassName) + frames + causes).joinToString("\n")
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

    /** 从堆栈文本提取非平台业务帧的「类名.方法名」（纯函数，internal 供测试）。 */
    internal fun businessFrames(stackTraceText: String?): List<String> =
        frameParts(stackTraceText).map { it.first }

    /**
     * 从堆栈文本提取非平台业务帧的**整行位置形态** `类.方法(文件:行)`（纯函数，internal 供测试）——
     * T-ErrorReportRedact 的描述预填（栈帧摘要）用：指纹只要「类.方法」（防行号漂移），
     * 但给人看的摘要需要行号才能定位。与 [businessFrames] 共用同一套解析与过滤，杜绝两处口径漂移。
     */
    internal fun businessFrameLines(stackTraceText: String?): List<String> =
        // 组 2 = 左括号之后**原样**的剩余文本（含右括号；老正则只要求左括号，容忍 `at a.B.c(` 残缺行），
        // 拼回时只补左括号即可，多补一个右括号会把 `(...)` 拼成 `(...))`
        frameParts(stackTraceText).map { (frame, rest) -> "$frame($rest" }

    /**
     * 从堆栈文本提取 cause 链类名（纯函数，internal 供测试）：`Caused by:`/`Suppressed:` 行只取类名，
     * 行上的 message 一律丢弃——指纹材料**永不包含业务文本**（D1=B 的隐私面）。
     */
    internal fun causeChainClasses(stackTraceText: String?): List<String> {
        if (stackTraceText.isNullOrBlank()) return emptyList()
        return stackTraceText.lineSequence().mapNotNull { line ->
            CAUSE_LINE_REGEX.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }.toList()
    }

    /** 帧解析 + 平台前缀过滤（[businessFrames]/[businessFrameLines] 唯一事实源）。 */
    private fun frameParts(stackTraceText: String?): List<Pair<String, String>> {
        if (stackTraceText.isNullOrBlank()) return emptyList()
        return stackTraceText.lineSequence().mapNotNull { line ->
            FRAME_LINE_REGEX.find(line)?.groupValues?.let { it[1] to it[2] }
        }.filter { (frame, _) ->
            PLATFORM_FRAME_PREFIXES.none { prefix -> frame.startsWith(prefix) }
        }.toList()
    }
}
