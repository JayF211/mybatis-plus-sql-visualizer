package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.FakeDriver;
import cn.song.mybatisplus.sqlvis.agent.hook.ConnectionHandler;
import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-AgentConfigChannel T-01/T-01b/T-02/T-07 环回端到端：真 [SenderLoop] + 真 loopback
 * {@link ServerSocket} + **真语句执行**（经 agent 的 StatementHandler 代理）。
 *
 * <p>与 {@code SenderLoopFrameTest} 的分工：那边钉帧格式（解析层），这边钉「下发 → 生效 → 行为」
 * 整链——发 {@code disabled=true} 后一次语句执行**不产出事件帧**，发 {@code disabled=false} 后恢复。
 * 语句执行走 {@link SqlHookCallbacks#onConnection}（织入字节码在 connect 出口调用的同一个公开入口）
 * → {@link ConnectionHandler} → agent 的 StatementHandler 代理，并对代理身份做前置断言，
 * 避免「根本没走采集路径」的空真绿。
 *
 * <p>计时全部经系统属性压短（心跳 200ms；看门狗按用例注入），整套用例秒级完成。
 */
public class ConfigApplyEndToEndTest {

    /** 心跳间隔注入（毫秒）：缩短事件轮询/读超时节奏，让断言窗口落在百毫秒级。 */
    private static final long HEARTBEAT_MS = 200L;

    /** CONFIG 用例的看门狗窗口：CONFIG 在百毫秒内就发出，取 3s 保证看门狗不抢跑（也不拖长用例）。 */
    private static final long WATCHDOG_MS_CONFIG_CASE = 3000L;

    /** 看门狗用例窗口（毫秒）：会话建立后不发 CONFIG，应在窗口过后主动停采。 */
    private static final long WATCHDOG_MS_CASE = 500L;

    /** 帧读超时（毫秒）：小于心跳间隔的 3 倍，读不到帧即回轮询（不做「一读到底」的等待）。 */
    private static final int FRAME_READ_TIMEOUT_MS = 500;

    private String prevHeartbeat;
    private String prevWatchdog;
    private ServerSocket server;
    private Socket socket;
    private Thread loop;

    @Before
    public void setUp() {
        prevHeartbeat = System.getProperty(SenderLoop.HEARTBEAT_MS_PROP);
        prevWatchdog = System.getProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP);
        AgentState.setDisabled(false);      // 生产初值 fail-open；用例自证「停采是 CONFIG/看门狗带来的」
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
    }

    @After
    public void tearDown() {
        closeQuietly(socket);
        closeQuietly(server);
        if (loop != null) {
            loop.interrupt();               // 循环里的退避 sleep 立即抛 InterruptedException → 线程退出
            try {
                loop.join(3000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        restore(SenderLoop.HEARTBEAT_MS_PROP, prevHeartbeat);
        restore(SenderLoop.CONFIG_WATCHDOG_MS_PROP, prevWatchdog);
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
    }

    /**
     * 主用例：下发停采 → 生效（ACK + 状态）→ 语句照旧执行但不产出事件帧 → 下发恢复 → 事件帧回来。
     * 旧读序下 CONFIG 被解析成 cmd=0 丢弃，ACK 永不到达、停采永不生效 → 本用例在 ACK/无事件断言处必红。
     */
    @Test
    public void 停采生效_语句不再产出事件帧_恢复后继续产出() throws Exception {
        startLoop(WATCHDOG_MS_CONFIG_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        assertEquals("会话建立应先握手 HELLO", EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);
        assertEquals("连接建立即补发心跳", EventBus.CMD_HEARTBEAT, awaitFrame(in, 3000L).cmd);

        // 采集态下先备好语句代理：停采后 ConnectionHandler 会直通（不再包装），此处是唯一的包装窗口
        final PreparedStatement ps = wrappedStatement("INSERT INTO t VALUES (1)");

        // A) 采集态：一次语句执行 → 事件帧
        assertEquals(1, ps.executeUpdate());
        final Frame event = awaitFrame(in, EventBus.CMD_EVENT, 3000L);
        assertNotNull("采集态下 execute 应产出事件帧（本断言同时证明后面「无事件」不是空真）", event);
        assertTrue("事件帧应携带该语句： " + event.payload, event.payload.contains("INSERT INTO t VALUES (1)"));

        // B) 下发停采 → 应用侧回 ACK，状态真生效
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":[]}");
        final Frame ack = awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L);
        assertNotNull("CONFIG 应获 ACK（IDE 侧据此判「生效」，不再靠行为反推）", ack);
        assertEquals("ACK body 为跨侧字面契约",
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":0}", ack.payload);
        assertTrue("应用侧应处于停采态", AgentState.isDisabled());

        // C) 停采态：语句照旧执行（返回原语义值），窗口内不得出现任何事件帧
        assertEquals("停采只停采集，不得影响执行语义", 1, ps.executeUpdate());
        assertNoEventFrame(in, 1200L);

        // D) 下发恢复 → 事件帧回来
        SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":[]}");
        final Frame ack2 = awaitFrame(in, EventBus.CMD_CONFIG_ACK, 3000L);
        assertEquals("恢复采集的 ACK 应反映生效态",
                "{\"disabled\":false,\"desensitize\":false,\"skipPrefixes\":0}", ack2.payload);
        assertFalse(AgentState.isDisabled());
        assertEquals(1, ps.executeUpdate());
        assertNotNull("恢复采集后应重新产出事件帧", awaitFrame(in, EventBus.CMD_EVENT, 3000L));
    }

    /**
     * T-07 看门狗：会话建立后窗口内从未收到任何 CONFIG → 主动停采（且会话本身不死，心跳照发）。
     * 这条是 fail-open 的兜底：控制通道再出任何问题，损害也被限制在窗口内，而不是整场静默采集。
     */
    @Test
    public void 看门狗_从未收到CONFIG时主动停采而会话仍在() throws Exception {
        startLoop(WATCHDOG_MS_CASE);
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEquals(EventBus.CMD_HELLO, awaitFrame(in, 3000L).cmd);

        final long deadline = System.currentTimeMillis() + WATCHDOG_MS_CASE + 3000L;
        while (!AgentState.isDisabled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue("会话建立后 " + WATCHDOG_MS_CASE + "ms 内未收到 CONFIG，应主动停采（fail-open 看门狗）",
                AgentState.isDisabled());

        // 停采只停采集：会话仍活着（心跳继续），否则 IDE 会误判掉线并重建会话
        final Frame f = awaitFrame(in, 3000L);
        assertNotNull("看门狗停采后会话不得关闭（应仍有心跳帧）", f);
        assertEquals("停采后仍应为心跳（会话存活）", EventBus.CMD_HEARTBEAT, f.cmd);
    }

    /** T-07 窗口取值：缺省/非法/非正数一律回落默认，不得因注入失误改变生产行为。 */
    @Test
    public void 看门狗窗口_缺省与非法回落默认() {
        System.clearProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP);
        assertEquals("缺省应为 15s", 15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "abc");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "0");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "-5");
        assertEquals(15000L, SenderLoop.resolveConfigWatchdogMs());
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, "800");
        assertEquals("合法注入应生效（测试可短跑）", 800L, SenderLoop.resolveConfigWatchdogMs());
    }

    /**
     * T-01b：停采下降沿清空已排队事件并计数（计数可观测），且重复停采不重复计数。
     * 否则「点停之后还冒条目」——连接建立时 SenderLoop 先尽出队列，存量事件会在停采生效后被补投。
     */
    @Test
    public void 停采下降沿_清空已排队事件并计数() {
        EventBus.clearQueued(); // 归零起点（同 JVM 其它用例可能留存量）
        final long before = EventBus.clearedCount();
        EventBus.offer(staleEvent("SELECT stale-1"));
        EventBus.offer(staleEvent("SELECT stale-2"));
        EventBus.offer(staleEvent("SELECT stale-3"));

        AgentState.setDisabled(true); // 上升沿：false → true

        assertTrue("停采下降沿应清空已排队事件并累计计数（实测增量 " + (EventBus.clearedCount() - before) + "）",
                EventBus.clearedCount() >= before + 3);
        final long afterFirst = EventBus.clearedCount();
        AgentState.setDisabled(true); // 非下降沿
        assertEquals("重复停采不应重复计数", afterFirst, EventBus.clearedCount());
    }

    // ---------- 辅助 ----------

    private void startLoop(long watchdogMs) throws Exception {
        System.setProperty(SenderLoop.HEARTBEAT_MS_PROP, String.valueOf(HEARTBEAT_MS));
        System.setProperty(SenderLoop.CONFIG_WATCHDOG_MS_PROP, String.valueOf(watchdogMs));
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout(5000);
        loop = new Thread(new SenderLoop(server.getLocalPort()), "sqlvis-agent-cfg-e2e");
        loop.setDaemon(true);
        loop.start();
        socket = server.accept();
        socket.setSoTimeout(FRAME_READ_TIMEOUT_MS);
    }

    /**
     * 起一条经 agent 采集链的 PreparedStatement：{@link SqlHookCallbacks#onConnection} 是织入字节码在
     * connect 出口调用的同一个公开入口（此处省去字节码织入这一步——织入本身由
     * {@code ConnectWeaveEndToEndTest} 覆盖），其后的语句代理就是产线上的 StatementHandler。
     * 代理身份做前置断言：不成立即说明本用例对采集路径无鉴别力（空真绿）。
     */
    private static PreparedStatement wrappedStatement(String sql) throws Exception {
        final Connection raw = new FakeDriver().connect("jdbc:fake://cfg-e2e", new Properties());
        assertNotNull("夹具前提：FakeDriver 应返回连接", raw);
        final Connection conn = (Connection) SqlHookCallbacks.onConnection(raw, "jdbc:fake://cfg-e2e");
        assertTrue("夹具前提：连接应被 agent 包装",
                Proxy.getInvocationHandler(conn) instanceof ConnectionHandler);
        final PreparedStatement ps = conn.prepareStatement(sql);
        assertTrue("夹具前提：语句应被 agent 包装", Proxy.isProxyClass(ps.getClass()));
        assertEquals("夹具前提：语句代理须为本 agent 的 StatementHandler（否则不产出事件）",
                "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                Proxy.getInvocationHandler(ps).getClass().getName());
        return ps;
    }

    private static SqlEvent staleEvent(String sql) {
        final SqlEvent e = new SqlEvent();
        e.sql = sql;
        e.timestamp = System.currentTimeMillis();
        e.threadName = Thread.currentThread().getName();
        return e;
    }

    private static final class Frame {
        final int cmd;
        final String payload;

        Frame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    /** 读下一帧（跳过其它命令），budgetMs 内没有帧则返回 null。 */
    private static Frame awaitFrame(DataInputStream in, long budgetMs) throws IOException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** 读下一帧并要求命令匹配（不匹配继续等），budgetMs 内没有匹配帧则返回 null。 */
    private static Frame awaitFrame(DataInputStream in, int cmd, long budgetMs) throws IOException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f != null && f.cmd == cmd) {
                return f;
            }
        }
        return null;
    }

    /** 窗口内不得出现事件帧；同时要求窗口内确有帧到达（证明通道活着，不是「连接死了所以没事件」）。 */
    private static void assertNoEventFrame(DataInputStream in, long windowMs) throws IOException {
        final long deadline = System.currentTimeMillis() + windowMs;
        int frames = 0;
        while (System.currentTimeMillis() < deadline) {
            final Frame f = tryReadFrame(in);
            if (f == null) {
                continue;
            }
            frames++;
            assertFalse("停采态下不得产出事件帧（收到 EVENT：" + f.payload + "）", f.cmd == EventBus.CMD_EVENT);
        }
        assertTrue("窗口内应持续有帧（心跳）证明通道存活，实测 " + frames + " 帧", frames > 0);
    }

    private static Frame tryReadFrame(DataInputStream in) throws IOException {
        try {
            final int len = in.readInt();
            final int cmd = in.readUnsignedByte();
            final byte[] body = new byte[len];
            in.readFully(body);
            return new Frame(cmd, new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (SocketTimeoutException noData) {
            return null;
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, prev);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
