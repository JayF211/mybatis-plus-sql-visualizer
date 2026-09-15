package cn.song.mybatisplus.sqlvis.agent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 已知 JDBC 驱动类登记表（§4.2 #1 插桩主路径）：只对表内类的
 * {@code connect(String, Properties)} 做方法出口织入——字节码面最小（6~10 方法缩到 1 方法/类），
 * 对驱动版本漂移最不敏感。连接池（Hikari/Druid）底层走 driver.connect，复用路径自动覆盖。
 *
 * 国产库（达梦/GBASE/Kingbase）形态登记为策略条目：类名命中即织入，签名不匹配安全跳过。
 */
public final class DriverRegistry {

    private static final Set<String> DRIVER_CLASSES;

    static {
        Set<String> s = new HashSet<String>();
        // MySQL / MariaDB
        s.add("com.mysql.jdbc.Driver");
        s.add("com.mysql.cj.jdbc.Driver");
        s.add("com.mysql.cj.jdbc.NonRegisteringDriver");
        s.add("org.mariadb.jdbc.Driver");
        // PostgreSQL / OpenGauss 兼容（openGauss 2.x 走 PG 驱动，3.x 起自带驱动）
        s.add("org.postgresql.Driver");
        s.add("org.opengauss.Driver");
        // H2 / HSQL / SQLite（嵌入式，测试矩阵主力）
        s.add("org.h2.Driver");
        s.add("org.hsqldb.jdbcDriver");
        s.add("org.hsqldb.jdbc.JDBCDriver");
        s.add("org.sqlite.JDBC");
        // SQL Server / Oracle
        s.add("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        s.add("oracle.jdbc.OracleDriver");
        s.add("oracle.jdbc.driver.OracleDriver");
        // 国产库（策略表登记：不可用时降级非阻断）
        s.add("dm.jdbc.driver.DmDriver");
        s.add("com.gbasedbt.jdbc.Driver");
        s.add("com.kingbase8.Driver");
        DRIVER_CLASSES = Collections.unmodifiableSet(s);
    }

    public static boolean isWeavable(String className) {
        return DRIVER_CLASSES.contains(className);
    }

    private DriverRegistry() {
    }
}
