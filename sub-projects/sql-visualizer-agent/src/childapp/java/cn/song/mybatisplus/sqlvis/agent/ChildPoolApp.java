package cn.song.mybatisplus.sqlvis.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * T-AttachPoolWarm 真机复现的「用户应用」子进程（MySQL 8）：模拟「应用已在跑、连接池里已有存量
 * 物理连接」的 attach 时序——warm 步骤在 attach 之前完成，随后在同一批 JDBC 对象上持续执行 SQL，
 * 供 attach 后 agent 织入通道捕获。四种模式由 args[0] 选：
 *
 * <ul>
 *   <li><b>A</b>（主场景）：warm 建一条连接并跑一次查询（statement 面 + prepare 面类均已加载），
 *       attach 后**在同一条存量连接上**每条 SQL 新建 PS → executeQuery()。精确模拟 MyBatis +
 *       Hikari 复用存量物理连接。</li>
 *   <li><b>B</b>：warm 建连接 + prepareStatement 拿到 PS 并执行一次（attach 前 prepare），attach 后
 *       **复用同一个 PS 对象**反复 executeQuery()——一期盲区（attach 前 prepare 无登记）实测。</li>
 *   <li><b>C</b>（对照）：warm 只加载驱动 + 建一条连接跑一次后关闭；attach 后**每次新建连接**执行
 *       （classpath 名单/harness 自检）。</li>
 *   <li><b>D</b>：语义同 A，JVM 启动叠加第三方 -javaagent（CoolRequest），观察 retransform 是否被拖红。</li>
 * </ul>
 *
 * args: mode url user password（后三者有缺省）。打印 READY &lt;pid&gt; 供测试侧 attach。
 */
public class ChildPoolApp {

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/mpj_demo";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASS = "root";

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "A";
        final String url = args.length > 1 ? args[1] : DEFAULT_URL;
        final String user = args.length > 2 ? args[2] : DEFAULT_USER;
        final String pass = args.length > 3 ? args[3] : DEFAULT_PASS;
        final String sql = "SELECT 1 AS attachpool_" + mode.toLowerCase();

        Class.forName("com.mysql.cj.jdbc.Driver");

        Connection warmConn = null;
        PreparedStatement warmPs = null;

        // ---------- warm 步骤（attach 之前：模拟应用已跑过请求、池里已有物理连接/PS） ----------
        if ("A".equals(mode) || "D".equals(mode) || "D2".equals(mode) || "D3".equals(mode)) {
            warmConn = DriverManager.getConnection(url, user, pass);
            // statement 面：createStatement 执行（加载 StatementImpl）
            try (Statement st = warmConn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 AS warm_stmt")) {
                rs.next();
            }
            // prepare 面 + PS 面：prepareStatement 执行（加载 ConnectionImpl 的 prepare 路径 + ClientPreparedStatement）
            try (PreparedStatement ps = warmConn.prepareStatement("SELECT 1 AS warm_ps");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            System.out.println("WARM-A: conn+stmt+ps loaded url=" + url);
        } else if ("B".equals(mode)) {
            warmConn = DriverManager.getConnection(url, user, pass);
            warmPs = warmConn.prepareStatement(sql);
            try (ResultSet rs = warmPs.executeQuery()) {
                rs.next();
            }
            System.out.println("WARM-B: ps prepared before attach sql=" + sql);
        } else if ("C".equals(mode)) {
            // 只加载驱动 + 建一条连接跑一次后关闭——attach 后每次新建连接
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 PreparedStatement ps = c.prepareStatement("SELECT 1 AS warm_close");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            System.out.println("WARM-C: warm conn opened+closed");
        } else {
            System.out.println("UNKNOWN-MODE " + mode + "（按 C 处理）");
        }

        // JDK8 兼容：ProcessHandle 是 Java 9+
        final String mx = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        final long pid = Long.parseLong(mx.substring(0, mx.indexOf('@')));
        System.out.println("READY " + pid);
        System.out.flush();

        // ---------- attach 后：持续产生 SQL（存量连接/PS 复用 或 新建连接） ----------
        int i = 0;
        while (true) {
            i++;
            try {
                if ("B".equals(mode)) {
                    // 复用 attach 前 prepare 的 PS 对象
                    try (ResultSet rs = warmPs.executeQuery()) {
                        rs.next();
                    }
                } else if ("C".equals(mode)) {
                    // 每条 SQL 新建连接（对照）
                    try (Connection c = DriverManager.getConnection(url, user, pass);
                         PreparedStatement ps = c.prepareStatement(sql);
                         ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                } else {
                    // A / D：存量连接上每条新 PS
                    PreparedStatement ps = warmConn.prepareStatement(sql);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    } finally {
                        ps.close();
                    }
                }
                System.out.println("TICK mode=" + mode + " i=" + i + " sql=" + sql);
            } catch (Throwable e) {
                System.out.println("LOOP-ERR mode=" + mode + " i=" + i + " "
                        + e.getClass().getName() + ": " + e.getMessage());
            }
            System.out.flush();
            Thread.sleep(300);
        }
    }

    private ChildPoolApp() {
    }
}
