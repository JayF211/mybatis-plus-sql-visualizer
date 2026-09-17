package cn.song.mybatisplus.visualizer.log

/**
 * 控制台日志时间相关器（T-AgentDialectLoop 建，T-AgentLogTime 改键）：
 * 增强捕获接管期间（[SqlCaptureGate.paused]=true）控制台链不再产出条目，但日志行里的原始时间戳
 * 仍可提取——本相关器暂存「控制台日志时间」，agent 事件入队前按键取回回填（[LogSqlEntry.logTimestamp]）。
 *
 * **键 = [logTimeKey]（占位符版 rawSql 指纹）**：两侧同源原材料，不经过参数还原。
 * 旧口径用 `dedupeKey`（还原后 SQL 指纹），要求控制台与 agent 两套还原器逐字符一致——
 * 布尔参数、注释内 `?`、参数个数不等三类分叉都会静默失配（T-AgentLogTime 实证）。
 * 参数值序列（[logTimeParamSignature]）只作**次级判别**：同键多次执行时挑参数一致的那条，
 * 不是命中条件——参数渲染口径再分叉也不会把时间整条丢掉。
 *
 * 结构：`LinkedHashMap<键, 队列>`，同键**排队**而非覆盖（旧实现同键 `remove`+`put`，
 * 同一条 SQL 连续执行时先来的时间会被后来的顶掉）。`take` 先扫同键队列找签名一致项，
 * 找不到退回队首（宁用近似时间也不错失）；超出时间窗（[WINDOW_MS]）的陈旧项直接丢弃，
 * 防长时间接管里与本次执行配错。
 *
 * 并线程模型：控制台过滤线程 put / agent 会话线程 take，两侧各为每条 SQL 一次 O(1)~O(队列长)
 * 操作，锁竞争可忽略。有界（[CAPACITY] 个键、单键 [PER_KEY_CAPACITY] 条）防驻留膨胀。
 *
 * 时序痕迹：MyBatis/JdbcTemplate 日志行打印于 execute 之前，控制台链通常先于 agent 事件到达；
 * 无参语句的条目要等下一条语句到来才闭合（`MyBatisLogParser.closePending`），其日志时间可能
 * 赶不上 agent 事件——此时 take 未命中回落捕获时间（T-AgentLogTime 已把捕获时间改为
 * execute **开始**时刻，与该日志行只差毫秒级），不重试不积压。
 */
object LogTimeCorrelator {

    /** 键上限（512 键；T-PS4-05 单值签名截断 256 字符后典型占用 MB 级封顶，旧「数万字节」口径失真已修）。 */
    private const val CAPACITY = 512

    /** 单键队列上限（同一条 SQL 连续执行的待配对条数；防循环里同语句刷爆队列）。 */
    private const val PER_KEY_CAPACITY = 8

    /** 陈旧项时间窗：入册后超过此值仍无人认领即丢弃（防「事件被丢后的残留条」配到下一次执行）。 */
    private const val WINDOW_MS = 30_000L

    private class Item(val time: Long, val signature: String, val receivedAt: Long)

    private val map = LinkedHashMap<String, ArrayDeque<Item>>()

    /** 在册条目总数（容量判定用，单键队列长度不是容量口径）。 */
    private var items = 0

    /** 累计未命中次数（诊断：真机排障看是「键不等」还是「时序赶不上」）。 */
    private var misses = 0L

    /**
     * 暂存一条控制台日志时间。
     *
     * @param key [logTimeKey]（占位符版 SQL 指纹）
     * @param logTime 日志行原始时间（未解析到时间戳时由调用方回落条目捕获时间）
     * @param signature [logTimeParamSignature]（次级判别用）
     */
    @Synchronized
    fun put(key: String, logTime: Long, signature: String = "") {
        putAt(key, logTime, signature, System.currentTimeMillis())
    }

    /**
     * [put] 的显式入册时刻版本——陈旧判定只用**入册时刻**（本机 IDE 时钟），不用日志行时间：
     * 应用 JVM 时区/时钟与 IDE 不同时（`-Duser.timezone`、日志格式带固定时区），日志行时间会整体
     * 偏移，拿它比墙钟会把全部条目误判为陈旧、日志时间静默全失。测试构造「已陈旧」条目也走这里。
     */
    @Synchronized
    internal fun putAt(key: String, logTime: Long, signature: String, receivedAt: Long) {
        if (key.isEmpty()) return
        pruneExpired(key, receivedAt)
        val queue = map.getOrPut(key) { ArrayDeque() }
        queue.addLast(Item(logTime, signature, receivedAt))
        items++
        while (queue.size > PER_KEY_CAPACITY) {
            queue.removeFirst()
            items--
        }
        while (map.size > CAPACITY) {
            val eldest = map.entries.iterator()
            val entry = eldest.next()
            items -= entry.value.size
            eldest.remove()
        }
    }

    /**
     * 取回并消费一条日志时间；未命中返回 null（调用方回落捕获时间）。
     * 同键队列里优先取参数签名一致的那条，找不到退回队首。
     */
    @Synchronized
    fun take(key: String, signature: String = ""): Long? {
        val queue = map[key] ?: return miss()
        pruneExpired(key, System.currentTimeMillis())
        if (queue.isEmpty()) {
            map.remove(key)
            return miss()
        }
        val matched = queue.firstOrNull { it.signature == signature }
        val item = matched ?: queue.first()
        queue.remove(item)
        items--
        if (queue.isEmpty()) map.remove(key)
        return item.time
    }

    /** 清空（在线态回落/会话切换时调用，防跨运行陈旧匹配）。 */
    @Synchronized
    fun clear() {
        map.clear()
        items = 0
    }

    /** 当前暂存量（测试/诊断用）。 */
    @Synchronized
    fun size(): Int = items

    /** 累计未命中次数（诊断用）。 */
    @Synchronized
    fun missCount(): Long = misses

    /** 丢弃超窗陈旧项（按入册时刻判定，见 [putAt]）；队列清空时顺手移除键。 */
    private fun pruneExpired(key: String, now: Long) {
        val queue = map[key] ?: return
        while (queue.isNotEmpty() && now - queue.first().receivedAt > WINDOW_MS) {
            queue.removeFirst()
            items--
        }
        if (queue.isEmpty()) map.remove(key)
    }

    private fun miss(): Long? {
        misses++
        return null
    }
}
