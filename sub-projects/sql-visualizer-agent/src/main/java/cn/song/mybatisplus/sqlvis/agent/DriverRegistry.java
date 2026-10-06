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
 *
 * T-StatementWeave：新增**语句面**与 **prepare 面**——attach（agentmain）路径对已加载的
 * 语句实现类 retransform，钩子挂在 execute 族方法体上，随每次执行触发，与连接/语句对象的
 * 创建时间无关（DebugTools 类工具「attach 即生效」的织入点模型）。类名为 javap+zip 实测
 * （mysql-connector-j 8.0.33 / h2 2.3.232，见开发计划 20261005-1148 §二）；织不上的类
 * 安全降级为无钩子（与驱动面同一契约），版本漂移不炸宿主。
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
        // T-StatementWeave 语句面/prepare 面名单在 StatementWeaver.SPECS（单一真相，形态标注
        // 跟类走）；本表只保留驱动 connect 面。两新面谓词委托 specOf 判定。
    }

    public static boolean isWeavable(String className) {
        return DRIVER_CLASSES.contains(className);
    }

    /**
     * 语句面：execute 族织入对象（T-StatementWeave）。
     * 名单单一真相在 [StatementWeaver.specOf]（织入形态标注跟着类走，防两处漂移）——本谓词委托判定。
     */
    public static boolean isStatementWeavable(String className) {
        final StatementWeaver.Kind[] spec = StatementWeaver.specOf(className);
        return spec != null && !hasPrepare(spec);
    }

    /** prepare 面：prepareStatement/prepareCall 出口登记对象（T-StatementWeave S3）。 */
    public static boolean isPrepareWeavable(String className) {
        final StatementWeaver.Kind[] spec = StatementWeaver.specOf(className);
        return spec != null && hasPrepare(spec);
    }

    private static boolean hasPrepare(StatementWeaver.Kind[] spec) {
        for (StatementWeaver.Kind k : spec) {
            if (k == StatementWeaver.Kind.PREPARE) {
                return true;
            }
        }
        return false;
    }

    private DriverRegistry() {
    }
}
