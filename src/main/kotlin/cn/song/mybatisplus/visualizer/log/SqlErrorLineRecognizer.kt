package cn.song.mybatisplus.visualizer.log

/**
 * T-M55-02 SQL 执行报错同列：控制台异常行识别器（纯函数，零平台依赖）。
 *
 * 词表策略（仿 execute/DataSourceSelector.kt 的 ERROR_SIGNATURES 先例 + isSqlLine 防误报教训）：
 * 只认**明确的 SQL 执行错误签名**——MyBatis `###` 错误块形态、Spring DAO 体系
 * （BadSqlGrammarException 等）、JDBC SQLException 家族、MyBatis/Hibernate 异常签名、
 * 参数绑定异常（PreparedStatement setter 报错——Ultra 4.0.14 自述不支持的边界，一并覆盖）。
 *
 * 热路径口径（T-Perf 红线，对照 docs/性能分析/20260902-0054）：[isErrorLine] 是逐行 Filter
 * 回调的前置判定，O(词表 × 行长) 的常量词表 contains（首命中早退），无正则、无逐行构造
 * （词表为 object 常量，LogRouterHotPathRegexGuardTest 同款防回潮守护见测试）。
 *
 * 已知边界：词表外的自定义异常（无 SQL 签名）不识别——漏绑优于误绑纯 SQL 行；
 * 纯 SQL 文本行不匹配任何签名（词表均为类名/特征短语，不含裸 SQL 关键词）。
 */
object SqlErrorLineRecognizer {

    /**
     * 错误签名词表（全小写；contains 判定）。
     * 泛型类名 token 带 `:`（异常行惯用 `类名: 消息` 形态），避免与普通日志里
     * 出现的同词根 logger 名/SQL 文本误撞；`###` 形态与特征短语本身无歧义不带。
     */
    private val ERROR_SIGNATURES = listOf(
        // MyBatis ### 错误块（裸 MyBatis 首行 / Spring 包装首行均含）
        "### error",
        "### cause",
        "### the error may",
        "### sql:",
        // MyBatis / mybatis-spring 异常
        "org.apache.ibatis.exceptions",
        "mybatissystemexception",
        "bindingexception:",
        "typeexception:",
        // Spring DAO / 事务体系
        "dataaccessexception:",
        "badsqlgrammarexception",
        "dataintegrityviolationexception",
        "duplicatekeyexception",
        "typemismatchdataaccessexception",
        "uncategorizedsqlexception",
        "jdbcconnectionexception:",
        "deadlockloserexception",
        "cannotcreatetransactionexception:",
        // JDBC / 驱动异常家族
        "sqlexception:",
        "sqlsyntaxerrorexception:",
        "sqlintegrityconstraintviolationexception:",
        "sqlrecoverableexception:",
        "sqltransientconnectionexception:",
        "sqltimeoutexception:",
        "communicationsexception:",
        // Hibernate / JPA 异常
        "org.hibernate.exception",
        "persistenceexception:",
        "hibernateexception:",
        "constraintviolationexception:",
        // 参数绑定异常特征（JDBC setter / MyBatis 参数映射）
        "parameter index out of range",
        "could not set parameters",
        "error setting null for parameter",
        "invalid argument value",
    )

    /**
     * 廉价前置判定：该行是否为 SQL 执行错误行（错误块首行候选）。
     * 逐行 Filter 热路径调用——词表 contains 首命中早退，O(词表 × 行长)。
     */
    fun isErrorLine(line: String): Boolean {
        val lower = line.lowercase()
        for (sig in ERROR_SIGNATURES) {
            if (sig in lower) return true
        }
        return false
    }

    /**
     * 错误块续行判定：堆栈帧（`\tat ...`）/ `Caused by:` / `... N more` / `Suppressed:` /
     * MyBatis `###` 错误块续行。仅在已进入错误块（[isErrorLine] 命中后）参与聚合。
     */
    fun isContinuationLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("at ") ||
            t.startsWith("Caused by:") ||
            t.startsWith("... ") ||
            t.startsWith("Suppressed:") ||
            t.startsWith("###")
    }

    /**
     * 错误首行清洗（取入 errorText 的首行）：去首尾空白。
     * 日志前缀（时间戳/级别/logger）保留——异常类名往往跟在前缀后，截除反而伤信息。
     */
    fun firstLineText(line: String): String = line.trim()

    /** errorText 总长上限（字符）：堆栈聚合到此为止，防超长堆栈驻留内存。 */
    internal const val MAX_ERROR_TEXT_LENGTH = 4000

    /** 堆栈聚合行数上限（首行之外）。 */
    internal const val MAX_ERROR_STACK_LINES = 30
}
