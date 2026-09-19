package cn.song.mybatisplus.visualizer.util

/**
 * SQL 语句顶层切分单实现（T-SemicolonGuard / O-13）：字符串/注释感知状态机 + 括号深度 +
 * BEGIN/END 块感知，三处历史实现（LogToSqlPanel.splitSqlStatements、
 * SqlTypeClassifier.splitStatements、SqlToJavaConverter.splitStatements）收敛委托至此。
 * 回滚链维持 JSqlParser parseStatements 不动（parser 权威，见 RollbackSqlGenerator）。
 *
 * 吸收 DBX `sql_editability.rs` has_top_level_semicolon 的增量语义（quote 栈 + 括号深度）：
 *  - 单/双引号内 `;` 不切分（含反斜杠转义与 SQL 标准 `''` 加倍——加倍形态按开闭交替天然受保护）；
 *  - `--` 行注释与块注释内的 `;` 不切分（[split] 注释随语句保留——原生控制台接受注释；
 *    [splitStripped] 连注释一并剥离——分类/判据消费）；
 *  - **括号深度**：`(…;…)` 括号内的 `;` 不切分（存储过程参数/函数实参形态）；
 *  - **BEGIN/END 块感知**（大小写不敏感，字符串/注释内不算）：块内 `;` 不切分——仅当后方
 *    存在配对 END 时启用（保守：识别不到配对 END 按顶层切分原样，不吞后续语句）；
 *  - **T-SQL 控制流批首合并**（T-TsqlBatchSplit / O-28，DBX 1a7b44f 批首识别同型）：
 *    首段以 `IF` 起头时整批不拆——裸 `IF … ELSE …` 形态（两分支各以 `;` 终结、无
 *    BEGIN/END 包裹）后续以 `ELSE` 起头的顶层段并入同批；批内 BEGIN/END 配对失衡
 *    （截断批）时继续吞后续段直到配对或批尾（宁整勿碎，不产出 ELSE 起头的碎句）。
 *    **批首限定 IF**：语句首 `IF` 无方言歧义（MySQL `IF()` 是函数不出现在语句首位）；
 *    裸 `BEGIN` 起头**不**并入——`BEGIN; …; COMMIT` 是 MySQL 事务语法，既有「无配对
 *    END 按顶层切分原样」保守承诺维持（形态良好的 T-SQL BEGIN 块已由上方块感知整段保护）；
 *  - **DELIMITER 指令感知**（T-DelimiterAware / O-39）：`DELIMITER <token>` 行首指令把
 *    语句界符由 `;` 切换为 `<token>`（`$$`/`//` 等多字符形态按整串匹配），直到下一条
 *    `DELIMITER` 指令或文本结束；指令行本身**不作为独立语句产出**（界符 token 一并丢弃），
 *    界符生效期内的顶层 `;` 不再切分（过程体内部 `;` 原样保留）。识别限定（对齐 MySQL
 *    客户端语义 + 保守不吞）：①仅行首指令形态（行内前导仅空白）；②仅待切段为空时（语句
 *    边界）——语句中间的 `DELIMITER` 词照旧按普通文本处理；③字符串/注释内不识别；
 *    ④无参数 `DELIMITER` 复位为 `;`。识别无方言条件——文本层面判定，仅出现指令才切换。
 *  - 顶层 `;` 为界：界符本身丢弃、段 trim、空段跳过；无分号尾段非空视为一条（容错）。
 *
 * 纯函数零依赖，普通 JUnit 可直接覆盖。
 */
object SqlStatementSplitter {

    /**
     * T-SQL 控制流批首关键字（批首识别，DBX `starts_with_sqlserver_control_flow_batch` 同型）。
     * 限定 IF：语句首 IF 无方言歧义；裸 BEGIN 是 MySQL 事务形态不并入（见类注释）。
     */
    private val CONTROL_FLOW_BATCH_HEADS = setOf("IF")

    /** 默认语句界符（MySQL 客户端 `DELIMITER` 指令可切换；无参数指令复位为此值）。 */
    private const val DEFAULT_DELIMITER = ";"

    /** `DELIMITER` 指令关键字（行首指令形态识别，大小写不敏感）。 */
    private const val DELIMITER_DIRECTIVE = "DELIMITER"

    /** 标识符字符（BEGIN/END 词边界判定）：字母/数字/下划线/$/#。 */
    private fun isIdentifierChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c == '#'

    /**
     * 顶层切分（注释随语句保留，段尾分号丢弃，段 trim，空段跳过）——
     * 预览/执行/批量转换链契约（LogToSqlPanel / SqlToJavaConverter 迁移前行为）。
     */
    fun split(sql: String): List<String> = splitInternal(sql, keepComments = true)

    /**
     * 顶层切分 + 引号感知去注释（空段跳过）——分类/确认流契约
     * （SqlTypeClassifier 迁移前「切分后去注释」的等价收口；修复其字符串内分号误切与
     * 字符串内 `--` 误剥两个历史缺陷，见开发计划 T-SG-01 差异清单）。
     */
    fun splitStripped(sql: String): List<String> = splitInternal(sql, keepComments = false)

    private fun splitInternal(sql: String, keepComments: Boolean): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var inLine = false
        var inBlock = false
        var parenDepth = 0
        var blockDepth = 0
        // BEGIN/END 启用前视：见到 BEGIN 先看后方有无配对 END（保守启用）
        var beginModeEnabled = false
        var word = StringBuilder()
        // 当前词的首字符下标与词起点处的缓冲长度（DELIMITER 指令的行首/语句边界判定用；
        // word 非空时有效）
        var wordStart = -1
        var bufferLenAtWordStart = 0
        // 当前生效的语句界符（T-DA-02：DELIMITER 指令切换；默认 `;`）
        var delimiter = DEFAULT_DELIMITER
        var i = 0

        fun flushWord() {
            if (word.isNotEmpty()) {
                val token = word.toString()
                word.setLength(0)
                if (beginModeEnabled) {
                    when (token.uppercase()) {
                        "BEGIN" -> blockDepth++
                        "END" -> if (blockDepth > 0) blockDepth--
                    }
                }
            }
        }

        while (i < sql.length) {
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            when {
                inLine -> {
                    if (keepComments) sb.append(c)
                    if (c == '\n') inLine = false
                    i++
                }
                inBlock -> {
                    if (keepComments) sb.append(c)
                    if (c == '*' && next == '/') {
                        if (keepComments) sb.append(next)
                        inBlock = false
                        i++
                    }
                    i++
                }
                inSingle -> {
                    sb.append(c)
                    when {
                        c == '\\' && next != null -> {
                            sb.append(next)
                            i++
                        }
                        c == '\'' -> inSingle = false
                    }
                    i++
                }
                inDouble -> {
                    sb.append(c)
                    when {
                        c == '\\' && next != null -> {
                            sb.append(next)
                            i++
                        }
                        c == '"' -> inDouble = false
                    }
                    i++
                }
                // T-DA-02：自定义界符整串匹配——先于注释/引号/标识符分支（`$$` 的 `$` 是
                // 标识符字符，须先命中；`//` 与块注释起始 `/*` 字符不同故与注释分支不冲突）
                delimiter != DEFAULT_DELIMITER && sql.startsWith(delimiter, i) -> {
                    flushWord()
                    if (parenDepth == 0 && blockDepth == 0) {
                        val part = sb.toString().trim()
                        if (part.isNotEmpty()) parts += part
                        sb.setLength(0)
                    } else {
                        sb.append(delimiter)
                    }
                    i += delimiter.length
                }
                c == '-' && next == '-' -> {
                    flushWord()
                    if (keepComments) sb.append(c)
                    inLine = true
                    i++
                }
                c == '/' && next == '*' -> {
                    flushWord()
                    if (keepComments) sb.append(c)
                    inBlock = true
                    i++
                }
                c == '\'' -> {
                    flushWord()
                    inSingle = true
                    sb.append(c)
                    i++
                }
                c == '"' -> {
                    flushWord()
                    inDouble = true
                    sb.append(c)
                    i++
                }
                c == ';' -> {
                    flushWord()
                    if (delimiter == DEFAULT_DELIMITER && parenDepth == 0 && blockDepth == 0) {
                        val part = sb.toString().trim()
                        if (part.isNotEmpty()) parts += part
                        sb.setLength(0)
                    } else {
                        sb.append(c)
                    }
                    i++
                }
                c == '(' -> {
                    flushWord()
                    parenDepth++
                    sb.append(c)
                    i++
                }
                c == ')' -> {
                    flushWord()
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    sb.append(c)
                    i++
                }
                isIdentifierChar(c) -> {
                    if (word.isEmpty()) {
                        wordStart = i
                        bufferLenAtWordStart = sb.length
                    }
                    word.append(c)
                    sb.append(c)
                    i++
                }
                else -> {
                    // 非标识符定界符：词终结（DELIMITER 指令 / BEGIN 前视在此触发）
                    if (word.isNotEmpty() &&
                        word.toString().equals(DELIMITER_DIRECTIVE, ignoreCase = true) &&
                        c.isWhitespace() && isLineHead(sql, wordStart) &&
                        sb.substring(0, bufferLenAtWordStart).isBlank()
                    ) {
                        // T-DA-02：行首 `DELIMITER <token>` 指令——切换界符并整行丢弃（不产出语句）
                        word.setLength(0)
                        sb.setLength(0)
                        val directive = readDelimiterDirective(sql, i)
                        delimiter = directive.first
                        i = directive.second
                    } else if (word.isNotEmpty() && word.toString().equals("BEGIN", ignoreCase = true) &&
                        !beginModeEnabled && hasMatchingEndAhead(sql, i)
                    ) {
                        beginModeEnabled = true
                        blockDepth = 1
                        word.setLength(0)
                        sb.append(c)
                        i++
                    } else {
                        flushWord()
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        // 尾部未终结词（无定界符收尾）：补一次 BEGIN 前视判定
        if (word.isNotEmpty() && word.toString().equals("BEGIN", ignoreCase = true) &&
            !beginModeEnabled && hasMatchingEndAhead(sql, sql.length)
        ) {
            beginModeEnabled = true
            blockDepth = 1
            word.setLength(0)
        } else {
            flushWord()
        }
        val tail = sb.toString().trim()
        // 无分号结尾的残余段：非空视为一条语句（容错——用户手动删尾分号后仍可执行）
        if (tail.isNotEmpty()) parts += tail
        return mergeControlFlowBatches(parts)
    }

    /**
     * T-SQL 控制流批首合并（T-TsqlBatchSplit / O-28）：首段以 IF 起头 → 整批不拆。
     *
     * 两形态（宁整勿碎，识别不了整段保留）：
     *  - 裸 `IF …; ELSE …;`：两分支各自以顶层 `;` 终结且无 BEGIN/END 包裹，逐段切分会产出
     *    以 ELSE 起头的碎句（语法不成立）——后续以 ELSE 起头的顶层段（含 ELSE IF 链）并入同批；
     *  - 配对失衡（截断批）：IF 批内 BEGIN/END 计数未闭合（字符串/注释感知），继续吞后续
     *    段直到配对或批尾——碎半截控制流送进确认流/执行只会产出误导性语法错。
     *
     * MySQL `IF()` 是函数不出现在语句首位（`SELECT IF(…)`），SELECT 起头不触发合并；
     * 裸 BEGIN 起头不并入（MySQL 事务形态，保守承诺维持）；批首段之后的独立语句
     * （非 ELSE 起头、已配对闭合）不受影响。
     */
    private fun mergeControlFlowBatches(parts: List<String>): List<String> {
        if (parts.size < 2) return parts
        val out = ArrayList<String>(parts.size)
        var i = 0
        while (i < parts.size) {
            var batch = parts[i]
            if (firstKeywordOf(batch) in CONTROL_FLOW_BATCH_HEADS) {
                var j = i + 1
                while (j < parts.size) {
                    val unbalanced = beginEndBalance(batch) > 0
                    val nextIsElse = firstKeywordOf(parts[j]) == "ELSE"
                    if (!unbalanced && !nextIsElse) break
                    batch += "; " + parts[j]
                    j++
                }
                out += batch
                i = j
            } else {
                out += batch
                i++
            }
        }
        return out
    }

    /**
     * 行首判定（T-DA-02）：`index` 之前到行首之间是否只有空白（无前导内容）。
     * 对齐 MySQL 客户端语义——`DELIMITER` 只在行首起指令作用，行内出现按普通文本处理。
     */
    private fun isLineHead(sql: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0) {
            val c = sql[i]
            if (c == '\n') return true
            if (!c.isWhitespace()) return false
            i--
        }
        return true
    }

    /**
     * 读取 `DELIMITER` 指令的界符 token（T-DA-02）：取指令所在行去除首尾空白后的首个
     * 空白分隔词（对齐 MySQL 客户端「行余内容即界符」语义，行尾多余内容忽略）；空 token
     * 复位为默认界符 `;`。
     *
     * @param from 指令关键字之后的第一个字符下标（调用点已确认此处为空白）
     * @return Pair(界符 token, 指令行尾下标)——行尾下标指向 `\n` 或文本末尾（行本身整行丢弃）
     */
    private fun readDelimiterDirective(sql: String, from: Int): Pair<String, Int> {
        var end = from
        while (end < sql.length && sql[end] != '\n') end++
        val token = sql.substring(from, end).trim().takeWhile { !it.isWhitespace() }
        return (token.ifEmpty { DEFAULT_DELIMITER }) to end
    }

    /** 段首有效关键字（跳过空白与 `--`/`/* */` 注释后的首个标识符词，大写；无则 null）。 */
    private fun firstKeywordOf(part: String): String? {        var i = 0
        while (i < part.length) {
            val c = part[i]
            val next = part.getOrNull(i + 1)
            when {
                c.isWhitespace() -> i++
                c == '-' && next == '-' -> {
                    while (i < part.length && part[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < part.length && !(part[i] == '*' && part.getOrNull(i + 1) == '/')) i++
                    i += 2
                }
                isIdentifierChar(c) -> {
                    val sb = StringBuilder()
                    while (i < part.length && isIdentifierChar(part[i])) {
                        sb.append(part[i])
                        i++
                    }
                    return sb.toString().uppercase()
                }
                else -> return null
            }
        }
        return null
    }

    /** 段内顶层 BEGIN/END 配对计数（字符串/注释感知；>0 = 未闭合的截断批）。 */
    private fun beginEndBalance(part: String): Int {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var inLine = false
        var inBlock = false
        var word = StringBuilder()
        var i = 0
        fun flush() {
            if (word.isNotEmpty()) {
                when (word.toString().uppercase()) {
                    "BEGIN" -> depth++
                    "END" -> if (depth > 0) depth--
                }
                word.setLength(0)
            }
        }
        while (i < part.length) {
            val c = part[i]
            val next = part.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') inLine = false
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
                inSingle -> when {
                    c == '\\' && next != null -> i++
                    c == '\'' -> inSingle = false
                }
                inDouble -> when {
                    c == '\\' && next != null -> i++
                    c == '"' -> inDouble = false
                }
                c == '-' && next == '-' -> { flush(); inLine = true; i++ }
                c == '/' && next == '*' -> { flush(); inBlock = true; i++ }
                c == '\'' -> { flush(); inSingle = true }
                c == '"' -> { flush(); inDouble = true }
                isIdentifierChar(c) -> word.append(c)
                else -> flush()
            }
            i++
        }
        flush()
        return depth
    }

    /**
     * 前视扫描：`from` 之后是否存在词边界形态的 END（字符串/注释感知，大小写不敏感）。
     * 保守判定——用于决定是否启用 BEGIN/END 块感知（识别不到配对 END 则按顶层切分原样）。
     */
    private fun hasMatchingEndAhead(sql: String, from: Int): Boolean {
        var inSingle = false
        var inDouble = false
        var inLine = false
        var inBlock = false
        var word = StringBuilder()
        var i = from
        while (i < sql.length) {
            val c = sql[i]
            val next = sql.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') inLine = false
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
                inSingle -> when {
                    c == '\\' && next != null -> i++
                    c == '\'' -> inSingle = false
                }
                inDouble -> when {
                    c == '\\' && next != null -> i++
                    c == '"' -> inDouble = false
                }
                c == '-' && next == '-' -> { inLine = true; i++ }
                c == '/' && next == '*' -> { inBlock = true; i++ }
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                isIdentifierChar(c) -> word.append(c)
                else -> {
                    if (word.isNotEmpty() && word.toString().equals("END", ignoreCase = true)) return true
                    word.setLength(0)
                }
            }
            i++
        }
        return word.isNotEmpty() && word.toString().equals("END", ignoreCase = true)
    }
}
