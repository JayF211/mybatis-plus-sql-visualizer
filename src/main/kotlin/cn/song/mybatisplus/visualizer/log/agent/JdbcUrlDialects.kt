package cn.song.mybatisplus.visualizer.log.agent

/**
 * JDBC URL → 数据库显示名映射（T-AgentDialectLoop）。
 *
 * agent 事件携带 connect 时 URL，据此在面板角标展示目标库类型。映射独立于
 * [cn.song.mybatisplus.visualizer.sql.DialectRegistry]：角标是事实展示而非渲染配置，
 * 不能走 `byName` 的未知回退 MySQL（会把 GBASE/openGauss 等误标）。
 *
 * 纯函数、无状态；未知/畸形 URL 返回 null（不显示角标）。
 */
internal object JdbcUrlDialects {

    /** 子协议 → 显示名。键统一小写；匹配时按 URL 子协议小写比对。 */
    private val SUBPROTOCOL_NAMES: Map<String, String> = mapOf(
        "mysql" to "MySQL",
        "mariadb" to "MariaDB",
        "postgresql" to "PostgreSQL",
        "opengauss" to "openGauss",
        "oracle" to "Oracle",
        "sqlserver" to "SQL Server",
        "h2" to "H2",
        "sqlite" to "SQLite",
        "hsqldb" to "HSQL",
        "hsql" to "HSQL",
        "dm" to "DM（达梦）",
        "kingbase8" to "KingBase（人大金仓）",
        "kingbase" to "KingBase（人大金仓）",
        "gbasedbt-sqli" to "GBASE",
        "gbasedbt" to "GBASE",
        "gbase" to "GBASE",
    )

    /**
     * 从 JDBC URL 提取子协议并映射显示名。
     * 形态：`jdbc:<子协议>:...`（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    fun displayName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("jdbc:", ignoreCase = true)) return null
        val rest = trimmed.substring(5)
        val subprotocol = rest.substringBefore(':').lowercase()
        if (subprotocol.isEmpty()) return null
        return SUBPROTOCOL_NAMES[subprotocol]
    }
}
