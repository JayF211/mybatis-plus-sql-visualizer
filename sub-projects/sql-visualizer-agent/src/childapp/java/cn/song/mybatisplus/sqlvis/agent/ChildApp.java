package cn.song.mybatisplus.sqlvis.agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * PoC 门禁测试的「用户应用」子进程：预加载 H2 驱动 + 预打一轮连接（retransform 前置），
 * 打印 READY <pid>，之后持续产生 SQL（attach 后事件应经 agent 流出）。
 */
public class ChildApp {

    public static void main(String[] args) throws Exception {
        // 预加载驱动 + 预跑一轮连接（门禁前置：attach 时驱动类已加载，须 retransform 生效）
        Class.forName("org.h2.Driver");
        Connection warm = DriverManager.getConnection("jdbc:h2:mem:warm;DB_CLOSE_DELAY=-1");
        try (Statement st = warm.createStatement()) {
            st.executeUpdate("CREATE TABLE warm(i INT)");
            st.executeUpdate("INSERT INTO warm VALUES (1)");
            try (ResultSet rs = st.executeQuery("SELECT * FROM warm")) {
                rs.next();
            }
        }

        // JDK8 兼容：ProcessHandle 是 Java 9+，门禁目标 JVM 可能是 8
        String mx = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        long pid = Long.parseLong(mx.substring(0, mx.indexOf('@')));
        System.out.println("CP=" + System.getProperty("java.class.path"));
        try {
            Class.forName("cn.song.mybatisplus.sqlvis.agent.DriverRegistry", false, ClassLoader.getSystemClassLoader());
            System.out.println("DR-PROBE: VISIBLE-BEFORE-ATTACH");
        } catch (Throwable e) {
            System.out.println("DR-PROBE: not-visible (" + e.getClass().getSimpleName() + ")");
        }
        System.out.println("READY " + pid);
        System.out.flush();

        // attach 后持续产生 SQL
        int i = 0;
        while (true) {
            i++;
            try (Connection c = DriverManager.getConnection("jdbc:h2:mem:db" + i + ";DB_CLOSE_DELAY=-1");
                 Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t(i INT)");
                st.executeUpdate("INSERT INTO t VALUES (" + i + ")");
                try (ResultSet rs = st.executeQuery("SELECT * FROM t")) {
                    while (rs.next()) {
                        // 消费
                    }
                }
            } catch (Throwable ignore) {
                // 应用侧自吞——模拟真实应用的健壮性
            }
            Thread.sleep(300);
        }
    }
}
