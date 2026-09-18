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

    /** 在线态判定：最近 N 毫秒内有心跳或事件。 */
    const val ONLINE_WINDOW_MS = 30_000L

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
