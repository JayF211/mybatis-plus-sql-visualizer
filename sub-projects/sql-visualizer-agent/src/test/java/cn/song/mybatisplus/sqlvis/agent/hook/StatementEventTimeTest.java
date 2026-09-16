package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-AgentLogTime：采集时刻语义——`SqlEvent.timestamp` 必须取 execute **开始**那一刻，
 * 而不是执行结束（旧实现取 `finally` 里的 `currentTimeMillis`）。
 *
 * 面板的「捕获时间」在日志时间回填未命中时就是它——取在执行结束会让显示值比 MyBatis 的
 * Preparing 日志行晚一个执行耗时（毫秒级快查看不出，慢查/首查即肉眼可见的「对不上」）。
 *
 * 观察面：不启动发送线程时事件留在 [EventBus] 静态队列里，测试反射读该私有队列直接取事件
 * （与既有用例反射读 handler 字段同风格）。
 */
public class StatementEventTimeTest {

    /** 慢语句时长——差异窗口：取在执行结束会晚这么多。 */
    private static final long SLOW_MS = 80L;

    @Test
    public void 采集时刻取执行开始而非执行结束() throws Throwable {
        queue().clear();
        final Object raw = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        Thread.sleep(SLOW_MS);
                        return Boolean.TRUE;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StatementHandler handler = new StatementHandler(raw, "SELECT 1", null);
        Method execute = java.sql.PreparedStatement.class.getMethod("execute");

        final long before = System.currentTimeMillis();
        handler.invoke(null, execute, new Object[0]);

        SqlEvent event = queue().poll();
        assertNotNull("execute 应产出一条事件", event);
        assertTrue("耗时须真实计入（防本用例自身假绿）", event.durationNanos / 1_000_000L >= SLOW_MS - 10);
        assertTrue(
                "采集时刻须落在 execute 开始处（取在执行结束会晚 " + SLOW_MS + "ms，实际晚 "
                        + (event.timestamp - before) + "ms）",
                event.timestamp - before < SLOW_MS / 2);
    }

    /** [EventBus] 的事件队列；不启动发送线程时无人消费，测试直接读它取本轮事件。 */
    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
