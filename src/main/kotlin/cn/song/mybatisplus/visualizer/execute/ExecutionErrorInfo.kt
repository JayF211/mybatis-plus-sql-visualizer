package cn.song.mybatisplus.visualizer.execute

/**
 * T-ErrorLocate（N-18）：执行链错误的富信息载体——旧 [onError: (String) -> Unit] 通道只投递
 * `cause.message`（SQLState/errorCode/驱动位置信息全部丢弃），本类在反射链 catch 点并行携带
 * 结构化信息，消费方（SqlResultPanel 错误定位动作 / MCP 工具）按需取用。
 *
 * DBX sql_error_position.rs 对齐：把驱动错误位置结构化是排错体验的最大单点提升；
 * [positionChar]/[lineInSql] 提取自错误消息文本（PG `Position: N`、MySQL `at line N`），
 * 不动协议层。marker 单一性门控与「基建语句不带位置」防误指细节见 [ErrorPositionExtractor]。
 */
data class ExecutionErrorInfo(
    val message: String,
    val sqlState: String? = null,
    val errorCode: Int? = null,
    /** 驱动报错字符位置（1-based，指被执行 SQL 文本；null = 消息未携带或多个 marker 放弃归属）。 */
    val positionChar: Int? = null,
    /** 驱动报错行号（1-based，指被执行 SQL 文本；语义同上）。 */
    val lineInSql: Int? = null,
    /** 位置信号来源标签（"pg-position" / "line" / "mysql-at-line" / "oracle-position"），留证与测试断言用。 */
    val positionSource: String? = null,
    /**
     * T-ErrorLocateUsability T-ELU-04（O-49）：被执行 SQL 的长度（调用方传入时的钳制上界）。
     *
     * **两态结构化**：驱动报错位置指向的是**被执行 SQL 文本**（动态 SQL 拼接、参数注入后行号必然
     * 错位），与 mapper 原文行不一一对应——v1 只承诺「语句起始行」跳转，不引入位置换算。
     * 本字段把「这个位置属于哪一态」变成数据：非 null 即证明调用方确实把被执行文本长度传了进来
     * （越界钳制的生效前提），消费方（tooltip / MCP 文案）据此如实说明，测试也据此断言接线。
     */
    val executedSqlLength: Int? = null,
)

/**
 * 错误位置提取器（纯函数，零平台依赖）。
 *
 * 两条 DBX 防误指规则照抄：
 *  - **marker 单一性门控**（sql_error_position.rs:113）：消息里恰好一个位置信号才归属，
 *    多个并存时放弃（宁缺毋滥——归属错了比没有更危险）；
 *  - **基建语句不带位置**（postgres.rs:1501）：连接串错误等非 SQL 错误天然不含位置标记，
 *    提取不出即无位置（诚实降级），绝不猜测。
 *
 * 越界钳制：调用方传入被执行 SQL 长度时，位置钳到 [1, sqlMaxLength]；钳后越界（<=0）返回 null。
 */
object ErrorPositionExtractor {

    /**
     * 位置信号形态：PG pgjdbc 把 server 错误的 Position 拼进 message（`…  Position: 42`）。
     *
     * 正则保持逐字不变（`\bPosition:` 会顺带命中 Oracle 形态里的 `position: N`，见
     * [ORACLE_POSITION] 的遮蔽说明——这里**不动正则**是为了任何不含 Oracle 形态的消息行为零变化）。
     */
    private val PG_POSITION = Regex("""(?i)\bPosition:\s*(\d+)""")

    /** 行号形态：MySQL 语法错误（`… near '…' at line 1`）与 PG 旧形态（`At line N`）。 */
    private val AT_LINE = Regex("""(?i)\bat line (\d+)""")

    /** 行号形态：`Line: N`（部分驱动/方言的详细错误附行号冒号形态）。 */
    private val LINE_COLON = Regex("""(?i)\bline:\s*(\d+)""")

    /**
     * T-ELU-02：Oracle 位置形态——`error occur at position: N`（真库实测原文，见下）。
     *
     * **真库实测**（Oracle Free 23 / FREEPDB1，DBX 通道 2026-09-22，只读）：
     *  - `SELECT * FROM dbx_test.no_such_table_xyz` → `ORA-00942: table or view
     *    "DBX_TEST"."NO_SUCH_TABLE_XYZ" does not exist\n error occur at position: 23`
     *  - `SELECT FROM` → `ORA-00936: missing expression\n error occur at position: 7`
     *  - `FROM dbx_test.issue_new` → `ORA-00900: invalid SQL statement\n error occur at position: 0`
     *
     * **取值语义（实测三例一致）**：N 是**出错记号起点减一**（`FROM …` 的记号在 0 位 → N=0；
     * `SELECT FROM` 的 `FROM` 在 1-based 第 8 位 → N=7；表名在 1-based 第 15 位 → N=14），
     * 即 0-based 偏移。本提取器的契约是 **1-based 字符位置**（与 PG `Position:` 同口径），
     * 故 **N+1** 后归属——不做换算就报出去会让用户看到的位置恒差一位。
     *
     * **为何要先遮蔽**（[maskOraclePositions]）：`PG_POSITION` 的 `\bPosition:` 会顺带命中
     * 本形态里的 `position: N`（实测：当前实现正是如此，Oracle 位置一直被提取但**标签错标成
     * pg-position 且按 1-based 直接报出**）。两个 marker 并存会让单一性门控判成「多信号」而
     * **放弃归属**——这正是「补了 Oracle marker 反而丢位置」的陷阱，故 Oracle 段先从文本里抹掉。
     */
    private val ORACLE_POSITION = Regex("""(?i)\berror occur at position:\s*(\d+)""")

    /** 单个位置信号：种类标签 + 1-based 值。 */
    private data class Marker(val source: String, val value: Int, val isCharPosition: Boolean)

    /**
     * 从错误消息提取位置；无信号 / 多 marker / 越界返回 null（调用方按无位置降级，不猜测）。
     *
     * @param sqlMaxLength 被执行 SQL 的长度（用于越界钳制）；null = 不钳制
     */
    fun extractPosition(
        message: String,
        sqlMaxLength: Int? = null,
    ): Triple<Int, Boolean, String>? {
        val markers = collectMarkers(message)
        // marker 单一性门控：恰好一个才归属（DBX take_message_position 同款）
        val distinct = markers.distinctBy { it.source to it.value }
        if (distinct.size != 1) return null
        val m = distinct.single()
        var value = m.value
        if (value < 1) return null
        sqlMaxLength?.let { max ->
            if (max < 1) return null
            value = value.coerceAtMost(max)
        }
        return Triple(value, m.isCharPosition, m.source)
    }

    private fun collectMarkers(message: String): List<Marker> {
        val out = mutableListOf<Marker>()
        ORACLE_POSITION.findAll(message).forEach {
            out += Marker("oracle-position", it.groupValues[1].toInt() + 1, isCharPosition = true)
        }
        val rest = maskOraclePositions(message)
        PG_POSITION.findAll(rest).forEach { out += Marker("pg-position", it.groupValues[1].toInt(), isCharPosition = true) }
        AT_LINE.findAll(rest).forEach { out += Marker("mysql-at-line", it.groupValues[1].toInt(), isCharPosition = false) }
        LINE_COLON.findAll(rest).forEach { out += Marker("line", it.groupValues[1].toInt(), isCharPosition = false) }
        return out
    }

    /** 把 Oracle 位置段替换为等长空格（保持其余 marker 的偏移与文本内容不变）。 */
    private fun maskOraclePositions(message: String): String {
        val matches = ORACLE_POSITION.findAll(message).toList()
        if (matches.isEmpty()) return message
        return buildString(message.length) {
            var last = 0
            for (m in matches) {
                append(message, last, m.range.first)
                append(" ".repeat(m.value.length))
                last = m.range.last + 1
            }
            append(message, last, message.length)
        }
    }

    /**
     * 从反射链 catch 点已解包的 cause 构建富信息：SQLException 链取 SQLState/errorCode
     * （cause 链与 nextException 链各走一层，深度有界），位置从消息文本提取。
     */
    fun buildInfo(cause: Throwable?, message: String, sqlMaxLength: Int? = null): ExecutionErrorInfo {
        var sqlState: String? = null
        var errorCode: Int? = null
        var e: Throwable? = cause
        var depth = 0
        while (e != null && depth <= 5) {
            if (e is java.sql.SQLException) {
                if (sqlState == null) sqlState = e.sqlState?.takeIf { it.isNotBlank() }
                if (errorCode == null) errorCode = e.errorCode.takeIf { it != 0 }
                // nextException 链（批量场景第二个 SQLException 常在此）
                var next = e.nextException
                var d2 = 0
                while (next != null && d2 <= 3) {
                    if (sqlState == null) sqlState = next.sqlState?.takeIf { it.isNotBlank() }
                    if (errorCode == null) errorCode = next.errorCode.takeIf { it != 0 }
                    next = next.nextException
                    d2++
                }
                break
            }
            e = e.cause
            depth++
        }
        val pos = extractPosition(message, sqlMaxLength)
        return ExecutionErrorInfo(
            message = message,
            sqlState = sqlState,
            errorCode = errorCode,
            positionChar = pos?.takeIf { it.second }?.first,
            lineInSql = pos?.takeIf { !it.second }?.first,
            positionSource = pos?.third,
            executedSqlLength = sqlMaxLength,
        )
    }
}
