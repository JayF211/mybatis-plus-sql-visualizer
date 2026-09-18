package cn.song.mybatisplus.visualizer.execute

/**
 * T-MysqlGStrip：MySQL CLI 习惯的 `\G`/`\g` 后缀剥离（DBX 吸收清单 v6 #35）。
 *
 * 背景：用户从 MySQL 命令行带过来的 `SELECT 1\G`（垂直输出后缀）粘进预览面板/控制台执行时，
 * 文本原样透传服务端报语法错（MySQL 1064）。DBX 的做法是**提交前**剥掉最后一语句行尾的
 * `\G`/`\g`，同时保留历史记录里的原文（提交文本与历史原文两态分离）。
 *
 * 规则（对齐 DBX sqlStatementRanges 的 `\G` 处理，v1 取**行级判定**）：
 *  - 只看**最后一条语句的末行**——即全文最后一个非空白字符所在行，行尾匹配 `\G`/`\g` 才剥离；
 *  - 允许尾随空白，允许可选分号在 `\G` 之前或之后（`SELECT 1\G`、`SELECT 1;\G`、`SELECT 1\G;`）；
 *  - 行内容以 `--` / `#` 注释开头 → 不碰；
 *  - 字符串字面量内的 `\G`（如 `SELECT '\G'`）——行尾锚定天然不匹配，不碰；
 *  - 只移除 `\G` 与它紧邻的空白（其前/后的分号保留），其余字节（含尾随换行）原样保留。
 *
 * **宁漏剥勿错剥**：不做全文字符串状态机（漏剥 = 维持现状报服务端错，错剥 = 改变用户 SQL）。
 *
 * 本对象为纯函数（零平台依赖），可进默认 test 任务的快桶（testFast）。
 */
object MysqlClientGSuffix {

    /**
     * 剥离结果两态（提交文本与历史原文分离）：
     *  - [original]：剥前原文——历史链记录它（用户回看时仍是自己粘进来的 SQL）；
     *  - [submitText]：提交用文本（未剥离时与 [original] 同一实例语义）；
     *  - [stripped]：本次是否真的剥掉了后缀（false 时调用方零动作）。
     */
    data class Result(val original: String, val submitText: String, val stripped: Boolean)

    /**
     * 末行 `\G` 后缀形态：`<内容>;?[空白]\[gG][空白];?`（行尾锚定）。
     * 组 1 = `\G` 前内容、组 2 = `\G` 前的可选分号（保留）、组 3 = `\G` 后的可选分号（保留）。
     * `\G` 两侧的空白被吞掉（`SELECT 1\G ;` → `SELECT 1;`），其余字节不动。
     */
    private val G_SUFFIX = Regex("""^(.*?)(;?)[ \t]*\\[gG][ \t]*(;?)$""")

    /** MySQL 系方言标识（JDBC URL 子协议段小写形态；含 MySQL 协议兼容的国产/衍生库）。 */
    private val MYSQL_FAMILY = setOf(
        "mysql", "mariadb", "tidb", "oceanbase", "goldendb", "gbase", "polardb",
    )

    /**
     * 无条件剥离（方言不可得场景：exec 文件首次创建时数据源尚未选定）。
     * 文本不含末行 `\G` 后缀时原样返回（[Result.stripped] = false）。
     */
    fun strip(sqlText: String): Result {
        if (sqlText.isEmpty()) return Result(sqlText, sqlText, false)
        // 末行 = 最后一个非空白字符所在行；尾随空白（含换行）不参与匹配但原样保留
        val trimmed = sqlText.trimEnd()
        if (trimmed.isEmpty()) return Result(sqlText, sqlText, false)
        val lineStart = trimmed.lastIndexOf('\n') + 1
        val lastLine = trimmed.substring(lineStart)
        if (isCommentLine(lastLine)) return Result(sqlText, sqlText, false)
        val m = G_SUFFIX.matchEntire(lastLine) ?: return Result(sqlText, sqlText, false)
        val head = m.groupValues[1]
        val kept = head + m.groupValues[2] + m.groupValues[3]
        val before = sqlText.substring(0, lineStart)
        val after = sqlText.substring(trimmed.length)
        // 末行只有 `\G` 命令（可选分号/空白）时整行删除——`SELECT 1;\n\G` → `SELECT 1;`
        val submitText = if (head.isBlank()) before.trimEnd() else before + kept + after
        return Result(sqlText, submitText, true)
    }

    /**
     * 按方言剥离：非 MySQL 系方言原样返回（[Result.stripped] = false）。
     * **拿不到方言（null/空白）时按 MySQL 处理（剥离）**——`\G` 不是任何方言的合法语法，
     * 剥离对非 MySQL 同样无害（开发计划 §三 T-MG-02 定案）。
     */
    fun stripForDialect(sqlText: String, dialect: String?): Result =
        if (isMySqlFamily(dialect)) strip(sqlText) else Result(sqlText, sqlText, false)

    /** 方言标识是否 MySQL 系；null/空白 = 判定不可得 → true（默认剥离，见 [stripForDialect]）。 */
    fun isMySqlFamily(dialect: String?): Boolean {
        val d = dialect?.trim()?.lowercase().orEmpty()
        if (d.isEmpty()) return true
        return d in MYSQL_FAMILY
    }

    /**
     * 从 JDBC URL 提取方言标识（`jdbc:mysql://host:3306/db` → `mysql`；`jdbc:postgresql://…` →
     * `postgresql`）。取 `://` 之前 scheme 段的末节，纯字符串截取不做连接参数解析。
     *
     * 无法判定时返回 null（无 `://` 的形态如 Oracle thin `@host:1521:SID`、空串）——
     * 调用方据此走 [stripForDialect] 的「不可得 → 默认剥离」分支。
     */
    fun dialectFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = raw.substring(0, schemeEnd)
        if (scheme.any { !(it.isLetterOrDigit() || it == ':' || it == '+' || it == '-' || it == '.') }) {
            return null // 不是 URL scheme 形态，不猜
        }
        return scheme.substringAfterLast(':').lowercase().takeIf { it.isNotEmpty() }
    }

    /** 行内容（跳过前导空白）是否以 `--` / `#` 行注释开头。 */
    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("--") || t.startsWith("#")
    }
}
