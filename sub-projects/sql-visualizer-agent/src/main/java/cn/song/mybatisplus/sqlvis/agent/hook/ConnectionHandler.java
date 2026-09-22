package cn.song.mybatisplus.sqlvis.agent.hook;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;

/**
 * Connection 代理：只拦 {@code prepareStatement* / prepareCall / createStatement}，
 * 把返回的 Statement 再包一层代理（挂 SQL 与参数捕获）；其余方法直透原对象（零干预）。
 * 采集红线：代理不得影响原语义——InvocationTargetException 还原原始异常，close/unwrap 直透。
 *
 * <p><b>本类不看 {@code disabled}（T-AgentResiduals R5，2026-09-22 定案 B）</b>：原实现在
 * {@link #invoke} 顶层有 {@code if (AgentState.isDisabled()) return method.invoke(raw, args);}，
 * 于是停采期间 {@code prepareStatement} 返回**原语句**（不包装）——「停采期间 prepare、恢复后才执行」
 * 的语句永远不产出事件。MyBatis 默认 {@code SimpleExecutor} 每条查询现 prepare、执行后关闭，故
 * 实际不可达；但 {@code ReuseExecutor} 一类复用语句的执行器、以及应用自持语句的场景会命中。
 * 该短路与 T-08 定下的「连接/语句包装一律在位」不是同一条规则，本仓统一为后者：
 * <b>唯一闸门是 {@link StatementHandler#invoke} 的逐调用 {@code isDisabled()} 短路</b>——
 * 它排在参数捕获之前，故停采期间照样零产出、参数不累积。
 * 包装失败的安全网不变（{@link SqlHookCallbacks#dropToRaw}：自吞 + 计数 + 一次性告警；
 * 停采期间只计数、不打印、不占一次性名额，见该方法的 KDoc）。
 */
public final class ConnectionHandler implements InvocationHandler {

    private final Object raw;
    private final String url;

    ConnectionHandler(Object raw, String url) {
        this.raw = raw;
        this.url = url;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final String name = method.getName();
        try {
            if (args != null && args.length >= 1 && args.length <= 4 && args[0] instanceof String) {
                if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                    final Object stmt = method.invoke(raw, args);
                    return wrapStatementSafely(stmt, (String) args[0], url);
                }
            }
            if (args == null && "createStatement".equals(name)) {
                final Object stmt = method.invoke(raw, args);
                return wrapStatementSafely(stmt, null, url);
            }
            return method.invoke(raw, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /**
     * Statement 包装收口（T-AgentProxyCL）：代理定义失败（loader 看不见语句接口）时**返回原语句**
     * 而不是把异常抛给宿主——旧实现在这里裸调 wrapStatement，失败会从宿主的 prepareStatement
     * 调用点冒出去（采集红线：钩子绝不影响宿主语义）。失败走 dropToRaw 记数 + 一次性告警。
     */
    private Object wrapStatementSafely(Object stmt, String sql, String url) {
        if (stmt == null) {
            return null;
        }
        try {
            return ProxySupport.wrapStatement(stmt, sql, url);
        } catch (Throwable t) {
            return SqlHookCallbacks.dropToRaw("wrapStatement 抛异常", t, stmt);
        }
    }
}
