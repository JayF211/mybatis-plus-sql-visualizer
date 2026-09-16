package cn.song.mybatisplus.sqlvistest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 用户形态调用帧（T-AgentCudSource 测试夹具）：真实用户代码帧不在 agent 前缀
 * {@code cn.song.mybatisplus.sqlvis.} 下、且代码源为目录（测试产物），因此被
 * {@code StackLocator} 的框架前缀跳过规则与 L1 来源过滤同时放行，天然作为「首个用户帧」。
 *
 * <p>真实 CUD 执行时用户 Service/Mapper 帧即此形态；测试经此帧触发
 * {@code handler.invoke}，使采集事件的 sourceFile 精确指向本类——若放入 agent 前缀包会被
 * 当作本 agent 帧跳过（本轮实测选中 JUnit 的 FrameworkMethod，断言即失真）。
 */
public final class UserCaller {

    public static Object execute(InvocationHandler handler, Method method, Object[] args) throws Throwable {
        return handler.invoke(null, method, args);
    }

    private UserCaller() {
    }
}