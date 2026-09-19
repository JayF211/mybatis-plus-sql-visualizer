package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import cn.song.mybatisplus.sqlvistest.UserCaller;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-PS4-09 一次捕获两用守卫：emitEvent 内 locate 与 StackDigest 共享同一 getStackTrace 快照
 * （七轮审计①：原实现每条 SQL 两次全栈捕获）。
 *
 * <p>摘要捕获点固化：捕获发生在 emitEvent 内，头帧应为 {@code StatementHandler#emitEvent}；
 * 若回潮为无参 {@code StackDigest.build()} 二次捕获，头帧会变为 {@code StackDigest#build}——
 * 以头帧形态锁死「双栈捕获」回归面。摘要仍按原过滤规则（仅跳 Thread#getStackTrace 自身帧），
 * 用户业务帧必须在列。
 */
public class StackDigestSingleCaptureTest {

    @Test
    public void execute事件摘要与定位同源_单次捕获() throws Throwable {
        final SqlEvent event = runExecute("execute");
        assertNotNull("stackDigest 不得为空（一次捕获两用的摘要出口）", event.stackDigest);
        assertTrue("摘要头帧应为捕获处 emitEvent 帧",
                event.stackDigest.startsWith("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#emitEvent"));
        // 捕获点固化的另一面：头帧不再是 StackDigest#build（出现即说明退回了无参入口二次捕获）
        assertNoDigestBuildHead(event);
        // 业务帧在列（16 帧截断下用户帧位于头部数帧，必不被截掉）
        assertTrue("摘要应含用户业务帧（UserCaller）",
                event.stackDigest.contains("cn.song.mybatisplus.sqlvistest.UserCaller#execute"));
        // 定位与摘要同源：同一执行里 sourceFile 指向用户帧（locate 与 digest 出自同一快照链）
        assertEquals("cn/song/mybatisplus/sqlvistest/UserCaller.java", event.sourceFile);
    }

    private void assertNoDigestBuildHead(SqlEvent event) {
        for (String frame : event.stackDigest.split("<-")) {
            assertTrue("摘要不得含 StackDigest#build 帧（回潮 = 无参 build 二次捕获）: " + event.stackDigest,
                    !frame.startsWith("cn.song.mybatisplus.sqlvis.agent.util.StackDigest#build"));
        }
    }

    /** 经 UserCaller 用户形态帧触发 execute 族，返回出队事件（夹具同 StatementSourceLocationTest）。 */
    private SqlEvent runExecute(String methodName) throws Throwable {
        queue().clear();
        final Object raw = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class},
                (proxy, method, args) -> Boolean.FALSE);
        StatementHandler handler = new StatementHandler(raw, "UPDATE t SET a = 1", null);
        Method m = java.sql.PreparedStatement.class.getMethod(methodName);
        UserCaller.execute(handler, m, new Object[0]);
        SqlEvent event = queue().poll();
        assertNotNull(methodName + " 应产出一条事件", event);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}
