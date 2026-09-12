package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Properties;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * [ConnectWeaver] 端到端：织入 FakeDriver 字节码 → 定义类 → connect 出口钩子链
 * → Connection 被 JDK 代理包装（§4.2 #1 主路径单机全链）。
 * 附带 §4.2 #6 classloader 契约测试：驱动 loader 隐藏 agent 类时 connect 安全降级不抛错。
 */
public class ConnectWeaveEndToEndTest {

    /** 织入产物落盘，返回目录（供隔离 classloader 加载）。 */
    private File writeWoven(ClassLoader weaveLoader) throws Exception {
        byte[] original = classBytes(FakeDriver.class.getName());
        byte[] woven = ConnectWeaver.weave(weaveLoader, original);
        File dir = Files.createTempDirectory("sqlvis-woven-").toFile();
        String pkg = "cn/song/mybatisplus/sqlvis/agent";
        File cls = new File(dir, pkg + "/FakeDriver.class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), woven);
        // 内部类（hidden loader 场景需一并可见）
        for (String inner : new String[]{"FakeDriver$FakeConnHandler", "FakeDriver$FakeStmtHandler"}) {
            Files.write(new File(dir, pkg + "/" + inner + ".class").toPath(), classBytes("cn.song.mybatisplus.sqlvis.agent." + inner));
        }
        return dir;
    }

    @Test
    public void 织入后connect返回被代理的Connection() throws Exception {
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader());
        Class<?> driver = Class.forName(FakeDriver.class.getName(), true, loader);
        Object instance = driver.getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driver.getMethod("connect", String.class, Properties.class)
                .invoke(instance, "jdbc:fake://t", new Properties());
        assertNotNull(conn);
        assertTrue("connect 出口应被包装为代理", Proxy.isProxyClass(conn.getClass()));
        // 代理仍然可用：走 prepareStatement → executeUpdate 原语义透传
        PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?)");
        assertTrue(Proxy.isProxyClass(ps.getClass()));
        assertEquals(1, ps.executeUpdate());
        conn.close(); // 直透：代理 close 不应抛错
    }

    @Test
    public void null连接路径安全() throws Exception {
        File dir = writeWoven(ClassLoader.getSystemClassLoader());
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader());
        Object instance = Class.forName(FakeDriver.class.getName(), true, loader)
                .getDeclaredConstructor().newInstance();
        Object conn = driverConnect(instance, null);
        assertEquals(null, conn);
    }

    @Test
    public void 驱动loader隐藏agent类_connect安全降级不抛错() throws Exception {
        File dir = Files.createTempDirectory("sqlvis-hidden-").toFile();
        String pkg = "cn/song/mybatisplus/sqlvis/agent";
        File cls = new File(dir, pkg + "/FakeDriver.class");
        cls.getParentFile().mkdirs();
        // parent = 平台 loader：JDK 可见，测试 classpath（含 agent 类）不可见——§4.2 #6 契约
        ClassLoader hidden = new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getPlatformClassLoader());
        // 以 hidden loader 做织入判定：回调类不可见 → 原字节不织入
        Files.write(cls.toPath(), ConnectWeaver.weave(hidden, classBytes(FakeDriver.class.getName())));
        for (String inner : new String[]{"FakeDriver$FakeConnHandler", "FakeDriver$FakeStmtHandler"}) {
            Files.write(new File(dir, pkg + "/" + inner + ".class").toPath(), classBytes("cn.song.mybatisplus.sqlvis.agent." + inner));
        }
        Object instance = Class.forName(FakeDriver.class.getName(), true, hidden)
                .getDeclaredConstructor().newInstance();
        Connection conn = (Connection) driverConnect(instance, "jdbc:fake://t");
        assertNotNull("agent 类不可见时仍须返回原连接", conn);
        // 夹具本身是 Proxy——断言「未叠加 agent 代理」（handler 非本 agent 的 ConnectionHandler）
        assertFalse("回调不可达时不得包装（静默降级为无钩子）",
                Proxy.getInvocationHandler(conn) instanceof cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler);
    }

    private Object driverConnect(Object driver, String url) throws Exception {
        return driver.getClass().getMethod("connect", String.class, Properties.class)
                .invoke(driver, url, new java.util.Properties());
    }

    private byte[] classBytes(String fqcn) throws Exception {
        String path = fqcn.replace('.', '/') + ".class";
        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
