package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.RequestHooks;
import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.transport.TraceBus;
import org.junit.*;
import org.objectweb.asm.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/** Loads REAL HttpServlet class bytes in child-only API loaders, then executes a concrete servlet. */
public class ServletRequestWeaveTest implements Opcodes {
    private boolean disabled;
    public static int mode;
    public static Runnable nested;
    public static void business() throws Exception {
        if (mode == 1) throw new java.sql.SQLException("business failure", "42000");
        if (mode == 2) { Object stmt = new Object(); long token = SqlHookCallbacks.onStatementEnter(stmt);
            SqlHookCallbacks.onExecuteExit(stmt, "select 1", "executeQuery", token, new Object(), null); }
        if (mode == 3 && nested != null) { Runnable call = nested; nested = null; call.run(); }
    }
    @Before public void setup() throws Exception {
        disabled = AgentState.isDisabled(); AgentState.setDisabled(false);
        TraceBus.disconnected(); TraceBus.configure(true, false, "aa11", true); drain(); mode = 0; nested = null;
    }
    @After public void cleanup() { TraceBus.disconnected(); AgentState.setDisabled(disabled); }

    @Test public void javaxRealBytecodeNormalErrorAndThreadReuse() throws Exception { verify("javax"); }
    @Test public void jakartaRealBytecodeNormalErrorAndThreadReuse() throws Exception { verify("jakarta"); }
    @Test public void disabledDefaultAndNestedDispatchStack() throws Exception {
        Invocation invocation = invocation("javax");
        TraceBus.disconnected(); invocation.run(); assertTrue(drain().isEmpty());
        TraceBus.configure(true, false, "aa11", true);
        mode = 3; nested = () -> { try { invocation.run(); } catch (Exception e) { throw new RuntimeException(e); } };
        invocation.run();
        List<String> events = drain(); assertEquals(4, events.size());
        assertTrue(events.get(1).contains("parentRequestId"));
        assertNull(RequestHooks.currentId());
    }
    @Test public void absentCallbackLoaderDoesNotWeave() throws Exception {
        byte[] raw = bytes("javax.servlet.http.HttpServlet");
        assertSame(raw, ServletWeaver.weave(new ClassLoader(null) {}, "javax.servlet.http.HttpServlet", raw));
    }
    private void verify(String family) throws Exception {
        Invocation invocation = invocation(family);
        invocation.run(); List<String> noSql = drain(); assertEquals(2, noSql.size());
        assertTrue(noSql.get(0).contains("\"kind\":\"START\""));
        assertTrue(noSql.get(1).contains("\"generatedSql\":0"));
        assertTrue(noSql.get(1).contains("SERVLET_THREAD_ONLY"));
        mode = 1;
        try { invocation.run(); fail(); } catch (InvocationTargetException expected) {
            assertEquals(java.sql.SQLException.class, expected.getCause().getClass());
        }
        List<String> failed = drain(); assertEquals(2, failed.size());
        assertTrue(failed.get(1).contains("\"status\":\"ERROR\"")); assertNull(RequestHooks.currentId());
        mode = 2; invocation.run(); List<String> withSql = drain(); assertEquals(2, withSql.size());
        assertTrue(withSql.get(1).contains("\"generatedSql\":1"));
        assertTrue(withSql.get(1).contains("\"completedSql\":1"));
        assertNull(RequestHooks.currentId());
    }
    private Invocation invocation(String family) throws Exception {
        final String servlet = family + ".servlet.http.HttpServlet";
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(family + ".servlet.") || name.equals("fixture.ProbeServlet")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (!name.equals("fixture.ProbeServlet") && getParent().getResource(name.replace('.', '/') + ".class") == null)
                            throw new ClassNotFoundException(name); // ResourceBundle must fall back to .properties.
                        if (type == null) try {
                            byte[] raw = name.equals("fixture.ProbeServlet") ? fixture(family) : bytes(name);
                            byte[] woven = name.equals(servlet) ? ServletWeaver.weave(this, name, raw) : raw;
                            type = defineClass(name, woven, 0, woven.length);
                        } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
                        if (resolve) resolveClass(type); return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Object instance = loader.loadClass("fixture.ProbeServlet").getDeclaredConstructor().newInstance();
        Class<?> requestType = loader.loadClass(family + ".servlet.http.HttpServletRequest");
        Class<?> responseType = loader.loadClass(family + ".servlet.http.HttpServletResponse");
        InvocationHandler handler = (p, m, a) -> {
            if ("getMethod".equals(m.getName())) return "GET";
            if ("getProtocol".equals(m.getName())) return "HTTP/1.1";
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            if (m.getReturnType() == long.class) return -1L;
            return null;
        };
        Object request = Proxy.newProxyInstance(loader, new Class[]{requestType}, handler);
        Object response = Proxy.newProxyInstance(loader, new Class[]{responseType}, handler);
        Method service = instance.getClass().getMethod("service", loader.loadClass(family + ".servlet.ServletRequest"),
                loader.loadClass(family + ".servlet.ServletResponse"));
        return () -> service.invoke(instance, request, response);
    }
    private static byte[] fixture(String family) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String parent = family + "/servlet/http/HttpServlet";
        writer.visit(V1_8, ACC_PUBLIC, "fixture/ProbeServlet", null, parent, null);
        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor get = writer.visitMethod(ACC_PROTECTED, "doGet", "(L" + family + "/servlet/http/HttpServletRequest;L"
                + family + "/servlet/http/HttpServletResponse;)V", null, null); get.visitCode();
        get.visitMethodInsn(INVOKESTATIC, "cn/song/mybatisplus/sqlvis/agent/ServletRequestWeaveTest", "business", "()V", false);
        get.visitInsn(RETURN); get.visitMaxs(0, 0); get.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = ServletRequestWeaveTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(name, in); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); return out.toByteArray();
        }
    }
    static List<String> drain() throws Exception {
        Method poll = TraceBus.class.getDeclaredMethod("poll"); poll.setAccessible(true);
        List<String> bodies = new ArrayList<String>(); Object frame;
        while ((frame = poll.invoke(null)) != null) { Field body = frame.getClass().getDeclaredField("body");
            body.setAccessible(true); bodies.add((String) body.get(frame)); }
        return bodies;
    }
    private interface Invocation { void run() throws Exception; }
}
