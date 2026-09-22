package cn.song.mybatisplus.sqlvis.agent.hook;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 织入回调入口（§4.2 #6 classloader 契约）：被织入代码经 SystemClassLoader 反射调用本类。
 * 注意：本类的方法签名被织入代码硬编码（"onConnection"），改动须同步 ConnectWeaver。
 * 采集红线：任何异常自吞（钩子绝不影响宿主应用）；全局禁用 volatile 短路。
 * T-AgentProxyCL：自吞不等于无声——失败统一走 {@link #dropToRaw}（计数 + 一次性告警）。
 */
public final class SqlHookCallbacks {

    private static final AtomicLong DROP_FALLBACK = new AtomicLong();

    /**
     * 首次失败只告警一次：诊断可见性与日志洪水的取舍。失败形态（loader 选不对/驱动隐藏 agent 类）
     * 一旦发生就是**每条连接、每条语句**都失败——逐次 WARN 会在宿主应用日志里刷屏，比故障本身更糟；
     * 而「一次都不说」正是 T-AgentProxyCL 的成因（真机零诊断，只能靠只读代理跑真实驱动才钉住）。
     * 取中间：首次打印完整原因（类名 + 消息），此后只累加 {@link #fallbackCount()}，读该计数即可
     * 判断「是偶发一次还是持续失败」。
     */
    private static final AtomicBoolean WARNED_ONCE = new AtomicBoolean();

    /**
     * 连接出口回调（织入字节码在 connect 出口调用）：**一律包装**，停采只做逐调用短路。
     *
     * <p><b>为什么这里不看 {@code disabled}</b>（T-AgentConfigChannel T-08，2026-09-22 真机实证）：
     * 连接级跳过会让「停采期间建立的连接**永远不被包装**」——连接池（Hikari 等）复用同一批连接，
     * 于是用户「先跑应用、再点开始捕获」时一条都捕不到，停采→恢复同样失效（真机现场：
     * `disabled=false` 的 CONFIG 与 ACK 均已生效，业务窗口内事件数仍为 0）。设计红线本就是
     * 「禁用 = volatile 短路、**不卸载**」：钩子在位、逐调用零成本直返，正是
     * {@link ConnectionHandler#invoke} 与 {@code StatementHandler.invoke} 里的 {@code isDisabled()} 短路；
     * 连接级跳过是与该语义不一致的额外优化，已移除。
     *
     * <p>包装失败的兜底不变：{@link #dropToRaw}（自吞 + 计数 + 一次性告警），宿主应用行为不受影响。
     */
    public static Object onConnection(Object rawConnection, String url) {
        try {
            if (rawConnection == null) {
                return rawConnection;
            }
            if (ProxySupport.isOurProxy(rawConnection)) {
                return rawConnection; // 幂等：连接池复用/二次包装不叠加代理
            }
            final Connection proxied = ProxySupport.wrapConnection(rawConnection, url);
            if (proxied == null) {
                return dropToRaw("wrapConnection 返回空", null, rawConnection);
            }
            return proxied;
        } catch (Throwable t) {
            // 采集红线：catch(Throwable) 自吞——失败返回原连接；但必须可观测（T-AgentProxyCL）
            return dropToRaw("wrapConnection 抛异常", t, rawConnection);
        }
    }

    /**
     * 包装失败统一出口（T-AgentProxyCL）：计数 + 一次性告警 + 返回原对象。
     * 供本包内 Statement 包装路径（ConnectionHandler → wrapStatement）复用——那条路径的异常
     * 会直接冒到宿主的 prepareStatement 调用点，必须在此收口成「返回原语句」。
     */
    static Object dropToRaw(String where, Throwable t, Object fallback) {
        final long count = DROP_FALLBACK.incrementAndGet();
        if (WARNED_ONCE.compareAndSet(false, true)) {
            System.err.println("[T-AgentProxyCL] hook 包装失败，降级为直通（本次采集失效，仅此一条告警，"
                    + "累计次数见 fallbackCount）: " + where + " -> "
                    + (t == null ? "null" : t.getClass().getName() + ": " + t.getMessage())
                    + " [fallbackCount=" + count + "]");
        }
        return fallback;
    }

    /** 供测试观测（反射内联钩子链路不可直接断言的兜底计数）。 */
    public static long fallbackCount() {
        return DROP_FALLBACK.get();
    }

    // T-AgentConfigChannel T-08：原 `AgentStateBridge`（宿主 loader 看不见 agent 类时视同禁用、连接级跳过包装）
    // 已随连接级 `disabled` 判定一并删除——它带来的「连接永不包装」正是恢复采集失效的根因；包装失败的安全网
    // 改由 dropToRaw（自吞 + 计数 + 一次性告警）承担，且从「静默跳过」变为**可观测**。

    private SqlHookCallbacks() {
    }
}
