package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import cn.song.mybatisplus.sqlvistest.UserCaller;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-AgentCudSource：CUD（增删改）语句的源码位置捕获守卫。
 *
 * 现象：增强捕获下只有 SELECT 查询捕获到源码位置，INSERT/UPDATE/DELETE 捕获不到。
 * 子代理静态复核结论：采集链路对 SELECT 与 CUD 完全同构、无按语句类型分支（execute 族统一
 * 走 executeWithCapture → emitEvent → StackLocator.locate），不存在「executeUpdate 漏采集」
 * 的结构性缺陷；真凶为 {@code StackLocator} 调用方缓存把 null 位置永久锁进列表（自毒化）——
 * 一旦某调用方解析出无调试信息的中间帧，该 class#method 名下所有后续语句（含 CUD）恒无源码位置。
 *
 * 本用例经 [UserCaller] 用户形态帧触发采集（真实 CUD 执行时用户 Service/Mapper 帧即此形态），
 * 把「真实 CUD 执行 → 事件 sourceFile 必指向用户栈帧」锁进绿灯，堵住该缺陷回归面。
 */
public class StatementSourceLocationTest {

    @Test
    public void executeUpdate事件须携带源码位置() throws Throwable {
        SqlEvent event = runCud("executeUpdate");
        assertNotNull("sourceFile 不得为空——CUD 语句必须捕获到源码位置（T-AgentCudSource）", event.sourceFile);
        assertEquals("源码位置应指向用户调用帧（UserCaller）",
                "cn/song/mybatisplus/sqlvistest/UserCaller.java", event.sourceFile);
        assertNotNull("若主位置可用，methodKey 应非空", event.methodKey);
    }

    @Test
    public void execute事件须携带源码位置() throws Throwable {
        SqlEvent event = runCud("execute");
        assertNotNull("execute 透传 CUD 同样须捕获源码位置（T-AgentCudSource）", event.sourceFile);
        assertEquals("cn/song/mybatisplus/sqlvistest/UserCaller.java", event.sourceFile);
    }

    /** 用 CUD 语句走指定 execute 族方法，返回出队事件。 */
    private SqlEvent runCud(String methodName) throws Throwable {
        queue().clear();
        final Object raw = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        return 1;
                    }
                    if ("execute".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StatementHandler handler = new StatementHandler(raw, "UPDATE t SET a = ? WHERE id = ?", null);
        java.lang.reflect.Method m = java.sql.PreparedStatement.class.getMethod(methodName);
        UserCaller.execute(handler, m, new Object[0]);
        SqlEvent event = queue().poll();
        assertNotNull(methodName + " 应产出一条事件", event);
        assertTrue("事件 SQL 应携带占位符（CUD 形态）", event.sql.contains("UPDATE"));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        Field field = EventBus.class.getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }
}