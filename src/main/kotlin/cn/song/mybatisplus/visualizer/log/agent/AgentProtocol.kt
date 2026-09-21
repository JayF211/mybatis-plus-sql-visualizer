package cn.song.mybatisplus.visualizer.log.agent

/**
 * agent 通信协议常量与编解码（§4.3，M56 T-M56-01/02）：
 * loopback TCP，长度前缀帧（4 字节 len + 1 字节 command + JSON body UTF-8）。
 * 命令三值起步 + HELLO（agent 连接后自报 pid）；loadAgent 参数 k=v 分号分隔。
 * 两侧（agent Java / IDE Kotlin）各自实现同一契约——本对象是 IDE 侧权威定义，
 * agent 侧 SenderLoop/EventBus 的常量须与这里逐值一致（AgentProtocolSyncTest 锁定）。
 */
object AgentProtocol {

    /** 事件帧：SQL 结构化事件流（JSON body，见 [AgentEvent]）。 */
    const val CMD_EVENT = 1
    /** 心跳帧：静默 10s 一发，在线态判定依据。 */
    const val CMD_HEARTBEAT = 2
    /** 配置帧（IDE → agent）：禁用开关/脱敏开关下发。 */
    const val CMD_CONFIG = 3
    /** 握手帧：agent 连接后首帧，body `{"pid":<n>}`。 */
    const val CMD_HELLO = 4

    /** 单帧 payload 上限（1MB：SQL/堆栈文本远小于此，超限视为流损坏立即断开）。 */
    const val MAX_FRAME_LEN = 1 shl 20

    /**
     * agent 侧心跳间隔（§4.3 静默 10s）——与 agent `SenderLoop.DEFAULT_HEARTBEAT_MS` 同值，
     * 由 [AgentProtocolSyncTest] 源码扫描锁定（`ONLINE_WINDOW_MS >= 3 * 该值`）。
     */
    const val HEARTBEAT_INTERVAL_MS = 10_000L

    /**
     * 在线态判定：最近 N 毫秒内有心跳或事件。
     *
     * **不变量：窗口 = 3 倍心跳间隔**——留 3 次心跳的容错余量（一次网络/调度抖动、一次事件突发
     * 占用发送线程、一次入站读阻塞跨过判定点）。窗口不再与心跳间隔各自写死：此前 agent 侧按
     * 「假定每轮 200ms」累加计时，「静默 10s」实际要 60s 才发（真实每轮≈1.2s），而窗口 30s 照旧
     * 执行 → 增强捕获会话建立后 30 秒必被摘除（T-AgentHeartbeat 现场：HELLO +17ms、首条
     * HEARTBEAT +60633ms）。值未变（心跳 10s → 窗口 30s），推导关系写进代码，任一侧漂移都会在
     * `AgentProtocolSyncTest.心跳节奏_窗口不小于三倍间隔` 转红。
     */
    const val ONLINE_WINDOW_MS = HEARTBEAT_INTERVAL_MS * 3

    /** loadAgent 参数串（§4.3 k=v 契约，agent 侧 AgentArgs.parse 对应）。 */
    fun agentArgs(port: Int, logLevel: String = "warn"): String = "port=$port;logLevel=$logLevel"

    /** CONFIG 帧 body：禁用/脱敏开关（agent 侧极简 contains 解析对应——键序固定）。 */
    fun configBody(disabled: Boolean, desensitize: Boolean): String =
        """{"disabled":$disabled,"desensitize":$desensitize}"""

    /**
     * T-SourceJumpFunnel P1-3：CONFIG 帧扩展 body——追加用户登记的帧过滤前缀
     * （`"skipPrefixes":["a.","b."]`；空列表省略键，旧 agent contains 解析天然忽略未知键）。
     * 前缀文本 JSON 转义走最简 deny-list（反斜杠/引号/控制字符），与 [configBody] 的
     * 固定布尔键序兼容（agent 侧 disabled/desensitize 判定不受追加键影响）。
     */
    fun configBody(disabled: Boolean, desensitize: Boolean, skipPrefixes: List<String>): String {
        val clean = skipPrefixes.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return configBody(disabled, desensitize)
        val arr = clean.joinToString(",") { p ->
            "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\u000c", "\\f")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        }
        return """{"disabled":$disabled,"desensitize":$desensitize,"skipPrefixes":[$arr]}"""
    }
}
