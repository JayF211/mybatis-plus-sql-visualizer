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
 *  - 顶层 `;` 为界：界符本身丢弃、段 trim、空段跳过；无分号尾段非空视为一条（容错）。
 *
 * 纯函数零依赖，普通 JUnit 可直接覆盖。
 */
object SqlStatementSplitter {

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
                    if (parenDepth == 0 && blockDepth == 0) {
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
                    word.append(c)
                    sb.append(c)
                    i++
                }
                else -> {
                    // 非标识符定界符：词终结（BEGIN 前视在此触发）
                    if (word.isNotEmpty() && word.toString().equals("BEGIN", ignoreCase = true) &&
                        !beginModeEnabled && hasMatchingEndAhead(sql, i)
                    ) {
                        beginModeEnabled = true
                        blockDepth = 1
                        word.setLength(0)
                    } else {
                        flushWord()
                    }
                    sb.append(c)
                    i++
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
        return parts
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
