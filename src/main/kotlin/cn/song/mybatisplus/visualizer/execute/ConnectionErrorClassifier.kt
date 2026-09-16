package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import net.sf.jsqlparser.parser.CCJSqlParserUtil

/**
 * 连接类异常分类器（T-DdlRecover T-DR-01，DBX 吸收清单 #20 语义）：
 * 「失败不等于未生效」——执行 DDL 撞上连接断开时数据库侧可能已隐式提交，先把连接类错误
 * 从普通失败里分出来，供上层做已生效性探测。
 *
 * 判定三路（任一命中即连接类，优先级从高到低）：
 *  - SQLState 08xxx（SQL 标准连接异常类，跨驱动最可靠）；
 *  - vendor 错误码表（MySQL 通讯族 2002/2003/2006/2013、Oracle JDBC 17002/17008/17410/1089
 *    及 ORA 数字族 3113/3114——JDBC errorCode 通常已是数字形态）；
 *  - 消息文本签名兜底（对齐既有 extractConsoleError 的字符串签名思路，小写 contains）。
 */
object ConnectionErrorClassifier {

    /** vendor 连接族错误码（errorCode 为数值形态；SQLState 不规范的驱动的兜底）。 */
    private val VENDOR_CONNECTION_CODES = setOf(
        2002, 2003, 2006, 2013,          // MySQL: via pipe / refused / gone away / during query
        17002, 17008, 17410, 1089,       // Oracle JDBC: IO exception / closed connection / no more data / session killed
        3113, 3114,                      // ORA-03113/03114 数字形态
    )

    /** 消息文本签名兜底（小写 contains；与 vendor 码/SQLState 三路互补）。 */
    private val MESSAGE_SIGNATURES = listOf(
        "broken pipe",
        "connection refused",
        "connection reset",
        "connection timed out",
        "communications link failure",
        "closed connection",
        "the connection is closed",
        "connection attempt failed",
        "could not open connection",
    )

    /** 明确排除：连接词出现在非连接语义里（deadlock 是普通执行错误，误判会触发无意义探测）。 */
    private val EXCLUSION_SIGNATURES = listOf("deadlock", "access denied", "password")

    fun isConnectionError(info: ExecutionErrorInfo): Boolean {
        // 1. SQLState 08xxx（标准连接异常类）
        info.sqlState?.let { if (it.length >= 2 && it.substring(0, 2).equals("08", ignoreCase = true)) return true }
        // 2. vendor 错误码
        info.errorCode?.let { if (it in VENDOR_CONNECTION_CODES) return true }
        // 3. 消息签名兜底（排除表先行——"deadlock" 等非连接语义不误判）
        val lower = info.message.lowercase()
        if (EXCLUSION_SIGNATURES.any { lower.contains(it) }) return false
        return MESSAGE_SIGNATURES.any { lower.contains(it) }
    }
}

/**
 * DDL 目标对象名提取（T-DdlRecover T-DR-02）：从执行 SQL 提取 CREATE TABLE 目标表名。
 *
 * 两路（对齐 MetadataInvalidator 的 AST+兜底先例）：
 *  - JSqlParser 路：CreateTable 语句权威取表名（引号/schema 前缀由 parser 处理）；
 *  - 正则兜底：残缺语句被 JSqlParser 5.3 包成 UnsupportedStatement（O-12 实证）时，
 *    `CREATE\s+TABLE\s+` 词法提取首个标识符（剥引号/schema 前缀取末段）。
 * 提取失败返回 null——上层放弃探测（诚实降级，绝不猜表名）。
 */
object DdlTargetExtractor {

    private val CREATE_TABLE_REGEX =
        Regex("""(?i)\bCREATE\s+(?:TEMPORARY\s+|TEMP\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`"\[](?:[^\`"\]]+)[`"\]]|\w+)""")

    fun extractCreateTarget(sql: String): String? {
        val cleaned = SqlCommentStripper.strip(sql)
        if (cleaned.isBlank()) return null
        // AST 路：CREATE TABLE 权威
        try {
            val statements = CCJSqlParserUtil.parseStatements(cleaned)
            for (statement in statements) {
                if (statement is net.sf.jsqlparser.statement.create.table.CreateTable) {
                    statement.table?.name?.let { return normalize(it) }
                }
            }
        } catch (_: Exception) {
            // fail through：残缺语句走正则兜底
        }
        // 正则兜底
        return CREATE_TABLE_REGEX.find(cleaned)?.groupValues?.get(1)?.let { normalize(it) }
    }

    /** 剥引号与 schema 前缀（`db`.`t` → t；探测用短名与 DatabaseSchemaResolver 口径一致）。 */
    private fun normalize(raw: String): String? {
        val unquoted = raw.trim('`', '"', '[', ']')
        return (unquoted.substringAfterLast('.').trim().takeIf { it.isNotBlank() }
            ?: unquoted.trim().takeIf { it.isNotBlank() })
    }
}
