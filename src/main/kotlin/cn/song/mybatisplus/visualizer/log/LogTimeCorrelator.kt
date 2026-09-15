package cn.song.mybatisplus.visualizer.log

/**
 * 控制台日志时间相关器（T-AgentDialectLoop）：增强捕获接管期间（[SqlCaptureGate.paused]=true）
 * 控制台链不再产出条目，但日志行里的原始时间戳仍可提取——本相关器以去重指纹为键暂存
 * 「控制台日志时间」，agent 事件入队前按同指纹取回回填（[LogSqlEntry.logTimestamp]）。
 *
 * 键：`entry.dedupeKey`（sqlFingerprint(规范化 restoredSql)）——agent 与控制台两通道同 SQL
 * 产出同指纹（M56 T-M56-02 既定口径），无需新造键。
 *
 * 并线程模型：控制台过滤线程 put / agent 会话线程 take，synchronized LinkedHashMap 两侧
 * 各为每条 SQL 一次 O(1) 操作，锁竞争可忽略。有界（容量 [CAPACITY]，溢出逐出最老）防
 * 长时间接管无 agent 事件匹配时驻留膨胀；take 命中即消费（同 SQL 并发多份按 FIFO 配对）。
 *
 * 时序取舍：MyBatis/JdbcTemplate 日志行打印于 execute 之前，控制台链通常先于 agent 事件
 * 到达；控制台文本管道偶发滞后时 take 未命中 → 显示回落捕获时间（不重试不积压，宁缺不错）。
 */
object LogTimeCorrelator {

    /** 容量上限（条）：512 × 每项 ~50B ≈ 25KB 封顶。 */
    private const val CAPACITY = 512

    private val map = LinkedHashMap<String, Long>()

    /** 暂存一条控制台日志时间（logTimestamp 未解析到时由调用方回落条目捕获时间后传入）。 */
    @Synchronized
    fun put(fingerprint: String, logTime: Long) {
        if (fingerprint.isEmpty()) return
        map.remove(fingerprint)
        map[fingerprint] = logTime
        if (map.size > CAPACITY) {
            val eldest = map.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    /** 取回并消费一条日志时间；未命中返回 null。 */
    @Synchronized
    fun take(fingerprint: String): Long? {
        val v = map.remove(fingerprint) ?: return null
        return v
    }

    /** 清空（在线态回落/会话切换时调用，防跨运行陈旧匹配）。 */
    @Synchronized
    fun clear() {
        map.clear()
    }

    /** 当前暂存量（测试/诊断用）。 */
    @Synchronized
    fun size(): Int = map.size
}
