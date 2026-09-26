package cn.song.mybatisplus.visualizer.log.agent;

import java.util.Locale;
import java.util.Map;

/**
 * JDBC URL → 数据库显示名映射（T-AgentDialectLoop）。
 *
 * agent 事件携带 connect 时 URL，据此在面板角标展示目标库类型。映射独立于
 * {@link cn.song.mybatisplus.visualizer.sql.DialectRegistry}：角标是事实展示而非渲染配置，
 * 不能走 byName 的未知回退 MySQL（会把 GBASE/openGauss 等误标）。
 *
 * 纯函数、无状态；未知/畸形 URL 返回 null（不显示角标）。
 */
public final class JdbcUrlDialects {

    private JdbcUrlDialects() {
    }

    /** 子协议 → 显示名。键统一小写；匹配时按 URL 子协议小写比对。 */
    private static final Map<String, String> SUBPROTOCOL_NAMES = Map.ofEntries(
        Map.entry("mysql", "MySQL"),
        Map.entry("mariadb", "MariaDB"),
        Map.entry("postgresql", "PostgreSQL"),
        Map.entry("opengauss", "openGauss"),
        Map.entry("oracle", "Oracle"),
        Map.entry("sqlserver", "SQL Server"),
        Map.entry("h2", "H2"),
        Map.entry("sqlite", "SQLite"),
        Map.entry("hsqldb", "HSQL"),
        Map.entry("hsql", "HSQL"),
        Map.entry("dm", "DM（达梦）"),
        Map.entry("kingbase8", "KingBase（人大金仓）"),
        Map.entry("kingbase", "KingBase（人大金仓）"),
        // T-DialectXcFamily（O-63）：gaussdb（华为 GaussDB 官方前缀）与 opengauss 同归 openGauss
        // 角标（D2=A 一 object 两前缀）；oceanbase 新增角标（此前在册即无）
        Map.entry("oceanbase", "OceanBase"),
        Map.entry("gaussdb", "openGauss"),
        Map.entry("gbasedbt-sqli", "GBASE"),
        Map.entry("gbasedbt", "GBASE"),
        Map.entry("gbase", "GBASE"));

    /**
     * 从 JDBC URL 提取子协议并映射显示名。
     * 形态：{@code jdbc:<子协议>:...}（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    public static String displayName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return null;
        }
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        String subprotocol = (colon >= 0 ? rest.substring(0, colon) : rest).toLowerCase(Locale.ROOT);
        if (subprotocol.isEmpty()) {
            return null;
        }
        return SUBPROTOCOL_NAMES.get(subprotocol);
    }
}
