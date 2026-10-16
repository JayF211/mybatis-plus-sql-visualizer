package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.*;

/** Real class bytes are woven and verified by the JVM; no mocked callback assertions. */
public class SqlInsightWeaveProbeTest {
    public static class StatementFixture {
        public int executeUpdate(String sql) { return 7; }
        public boolean execute(String sql) { return false; }
        public int getUpdateCount() { return 9; }
        public Object executeQuery(String sql) {
            if ("implicit".equals(sql)) return fail();
            if ("caught".equals(sql)) {
                try { throw new IllegalArgumentException("caught internally"); }
                catch (IllegalArgumentException ignored) { return new Object(); }
            }
            throw new IllegalStateException("explicit");
        }
        private Object fail() { throw new IllegalStateException("implicit"); }
    }

    private boolean disabled;
    private Object statement;

    @Before public void setUp() throws Exception {
        disabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        queue().clear();
        String name = StatementFixture.class.getName();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) bytes.write(buffer, 0, n);
        }
        final byte[] woven = StatementWeaver.weaveSpec(
                new StatementWeaver.Kind[]{StatementWeaver.Kind.EXEC_PARAM},
                getClass().getClassLoader(), bytes.toByteArray());
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(name, woven, 0, woven.length); }
        };
        java.lang.reflect.Method define = loader.getClass().getDeclaredMethod("define");
        define.setAccessible(true);
        statement = ((Class<?>) define.invoke(loader)).getDeclaredConstructor().newInstance();
    }

    @After public void tearDown() throws Exception {
        queue().clear();
        AgentState.setDisabled(disabled);
    }

    @Test public void pureIreturnUpdateIsAlreadyCaptured() throws Exception {
        assertEquals(7, call("executeUpdate", "update t set x=1"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertEquals(Long.valueOf(7), event.updateCount);
        assertNull(queue().poll());
    }

    @Test public void booleanIreturnMustReachJdbcUpdateCount() throws Exception {
        assertEquals(false, call("execute", "update t set x=1"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertEquals(Long.valueOf(9), event.updateCount);
        assertNull(queue().poll());
    }

    @Test public void implicitExceptionMustProduceOneError() throws Exception {
        assertFailure("implicit");
        SqlEvent event = queue().poll();
        assertNotNull("implicit exception must reach the exit hook", event);
        assertTrue(event.errorText.contains("IllegalStateException"));
        assertNull(queue().poll());
    }

    @Test public void internallyCaughtThrowMustNotReportAnExecutionError() throws Exception {
        assertNotNull(call("executeQuery", "caught"));
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertNull("an ATHROW caught by the driver is not a failed execute", event.errorText);
        assertNull("one invocation must yield one event", queue().poll());
    }

    @Test public void explicitExceptionIsRethrownUnchanged() throws Exception {
        assertFailure("explicit");
        SqlEvent event = queue().poll();
        assertNotNull(event);
        assertTrue(event.errorText.contains("IllegalStateException"));
        assertNull(queue().poll());
    }

    private void assertFailure(String sql) throws Exception {
        try { call("executeQuery", sql); fail("expected fixture exception"); }
        catch (InvocationTargetException expected) {
            assertEquals(IllegalStateException.class, expected.getCause().getClass());
            assertEquals(sql, expected.getCause().getMessage());
        }
    }

    private Object call(String method, String sql) throws Exception {
        return statement.getClass().getMethod(method, String.class).invoke(statement, sql);
    }

    @SuppressWarnings("unchecked") private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
