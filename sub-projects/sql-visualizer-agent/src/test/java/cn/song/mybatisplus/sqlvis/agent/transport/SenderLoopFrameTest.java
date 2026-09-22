package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-AgentConfigChannel T-01 帧格式金标准（应用侧入站读序）。
 *
 * <p>缺陷现场：IDE 侧写帧与两侧线上格式都是 {@code [4 字节 len][1 字节 cmd][body]}，而应用侧入站
 * 先读 1 字节当命令、再读 4 字节当长度。对 {@code {"disabled":true,...}}（37 字节）实测：
 * 线上字节 {@code 00 00 00 25 03 7b 22 64 ...} → 应用侧读到 {@code cmd = 0x00 = 0}（≠ CMD_CONFIG=3）
 * → {@code handleInbound} 提前 return 丢弃；随后按错位长度 {@code 0x00002503 = 9475} 读 body
 * → 阻塞到读超时被 catch 吞掉（注释还写着「无入站数据」）→ {@code AgentState.disabled} 永远停在
 * fail-open 初值 false，应用侧从 JVM 启动起一直采集（沙盒日志：{@code capturing=false} 下 agent 事件仍入队）。
 *
 * <p>本类把读序钉在**行为**上（不是源码扫描）：按 IDE 侧写帧形态（{@code writeInt(len) +
 * writeByte(cmd) + body}）造帧，断言解析出 {@code cmd == CMD_CONFIG}、payload 完整、应用后
 * {@code AgentState.isDisabled() == true}；畸形长度帧断言**抛异常走重连 + WARN 记长度与首字节**，
 * 而不是被当成「无入站数据」静默吞。
 *
 * <p><b>对读序的鉴别力（实测读数，勿再笼统写成「全红」）</b>：把读序改回错位形态
 * （{@code readUnsignedByte()} 在前、{@code readInt()} 在后）后，本类 5 例中 **4 例转红**
 * ——{@code 金标准帧}、{@code 畸形长度帧_抛异常并告警长度与首字节}（错位长度意外落进合法区间，
 * 连 WARN 都打不出来）、{@code body截断_抛异常而非静默吞}（错位长度下 WARN 记的是 525691 而非 8）、
 * {@code 畸形长度帧_会话走重连}（被读超时静默吞掉，永不重连）；另 1 例**对读序无鉴别力**，
 * 不得计入覆盖证据：
 * <ul>
 *   <li>{@code 未知命令_只告警一条且计数可观测}——直接调 {@code handleInbound}，绕开了拆帧层，
 *       错位形态下行为完全相同（绿）；它锁的是「一次性告警 + 计数」，不是读序。</li>
 * </ul>
 * 同类跨侧用例 {@code ConfigApplyEndToEndTest} 在错位形态下 5 例红 2（两个依赖 CONFIG 生效的用例）、
 * 绿 3（看门狗 2 例与下降沿清队列 1 例都不经入站读序）。
 *
 * <p>本类曾被写成「改回错位形态 → 本类全红」——那是**未实测的断言**：当时 {@code body截断} 只断言
 * 「抛 IOException」，而旧读序下 {@code readFully} 阻塞到读超时抛出的也是 IOException，天然成立
 * （本仓「守卫看不见它要防的站点」顽疾）。现该例补上 WARN 关键字校验才真正随读序转红。
 */
public class SenderLoopFrameTest {

    private static final String CONFIG_BODY =
            "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":[\"com.corp.engine.\",\"com.corp.a.\"]}";

    @Before
    public void setUp() {
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
    }

    @After
    public void tearDown() {
        AgentState.setDisabled(false);
        AgentState.setDesensitize(true);
        StackLocator.setExtraSkipPrefixes(new String[0]);
    }

    /**
     * 金标准：IDE 侧写帧形态 → 解析出 CMD_CONFIG 与完整 payload → 应用生效（停采）→ 回执字面契约。
     * 旧读序下 {@code cmd} 读到的是长度的高位字节（0），本用例必红。
     */
    @Test
    public void 金标准帧_按IDE写帧形态解析并应用CONFIG() throws Exception {
        final DataInputStream in = frameStream(EventBus.CMD_CONFIG, CONFIG_BODY);

        final SenderLoop.InboundFrame frame = SenderLoop.readInboundFrame(in);
        assertEquals("首 4 字节是长度、第 5 字节才是命令：解析出的 cmd 必须为 CMD_CONFIG",
                EventBus.CMD_CONFIG, frame.cmd);
        assertEquals("payload 必须完整（长度取自长度前缀，而非错位读到的 9475）", CONFIG_BODY, frame.payload);

        final SenderLoop loop = new SenderLoop(1); // 端口不参与本用例：只走「解析 → 应用 → 回执」链
        final String ack = loop.handleInbound(frame.cmd, frame.payload);

        assertTrue("CONFIG 应用后应停采", AgentState.isDisabled());
        assertFalse("desensitize=false 应生效为不打码", AgentState.isDesensitize());
        assertEquals("帧过滤前缀应生效", 2, StackLocator.getExtraSkipPrefixes().length);
        assertEquals("CONFIG ACK body 为跨侧字面契约（键序固定/无空格/布尔小写/前缀条数）",
                "{\"disabled\":true,\"desensitize\":false,\"skipPrefixes\":2}", ack);
        assertTrue("CONFIG 应用计数应可观测（排障用）", AgentState.configAppliedCount() > 0L);
    }

    /**
     * 未知命令（旧读序下 CONFIG 就落在这里）不再静默丢弃；告警**只出一条**、计数逐条累加
     * （照本仓 SqlHookCallbacks 的 WARNED_ONCE 范式——流错位会逐帧命中，逐帧打印等于刷屏宿主日志）。
     *
     * <p>本用例直接驱动 {@code handleInbound}，**对入站读序无鉴别力**（见类 KDoc）。
     */
    @Test
    public void 未知命令_只告警一条且计数可观测() throws Exception {
        SenderLoop.resetUnknownCmdWarnOnceForTest(); // 一次性告警是进程级状态，先重置起点才可断言条数
        final long before = SenderLoop.unknownInboundCount();

        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream prev = System.err;
        final String out;
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            final SenderLoop loop = new SenderLoop(1);
            assertNull("非 CONFIG 命令不应回执", loop.handleInbound(0, CONFIG_BODY));
            assertNull("另一个未知命令同样不回执", loop.handleInbound(7, CONFIG_BODY));
            assertNull("第三条未知命令", loop.handleInbound(0, CONFIG_BODY));
        } finally {
            System.setErr(prev);
            out = err.toString("UTF-8");
        }

        assertEquals("三条未知命令须全部计入计数（计数不封顶，可判「偶发一条还是持续」）",
                before + 3, SenderLoop.unknownInboundCount());
        assertEquals("告警只出一条（一次性，不随命令值/帧数重复）：" + out,
                1, occurrences(out, "忽略未知入站命令"));
        assertFalse("未知命令不得改状态", AgentState.isDisabled());
    }

    /** 畸形长度帧：抛异常（走重连）+ WARN 记长度与首字节——不得以「读超时=无入站数据」静默吞。 */
    @Test
    public void 畸形长度帧_抛异常并告警长度与首字节() throws Exception {
        assertMalformedLenRejected(0, "len=0");
        assertMalformedLenRejected(-1, "len=-1");
        assertMalformedLenRejected(SenderLoop.MAX_FRAME_LEN + 1, "len=" + (SenderLoop.MAX_FRAME_LEN + 1));
    }

    /**
     * 长度合法但 body 截断：解析异常同样必须抛出（否则流已错位却继续按「无数据」轮询）。
     *
     * <p>断言里带 WARN 关键字校验是**鉴别力**所需，不是装饰：旧读序下这里读到的是错位长度
     * （0x0008037B），阻塞到读超时后抛的仍是 IOException——只断言「抛 IOException」在旧形态下
     * 天然成立（本仓「守卫看不见它要防的站点」的典型）。带上 WARN 后，旧形态读到的是
     * {@code len=525691} 而非 {@code len=8}，本用例随读序转红。
     */
    @Test
    public void body截断_抛异常而非静默吞() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(8);                      // 声称 8 字节 body
        dos.writeByte(EventBus.CMD_CONFIG);
        dos.write(new byte[]{'{', '}'});      // 只给 2 字节
        dos.flush();

        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream prev = System.err;
        final String warn;
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            try {
                SenderLoop.readInboundFrame(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
                fail("body 截断应抛 IOException（由外层循环走重连）");
            } catch (IOException expected) {
                // 期望：包装后的 IOException（保留 EOF/超时原因），非静默吞
                assertFalse("半截帧不得以 SocketTimeoutException 形态上抛（外层会把它当「无入站数据」吞掉）",
                        expected instanceof java.net.SocketTimeoutException);
            }
        } finally {
            System.setErr(prev);
            warn = err.toString("UTF-8");
        }
        assertTrue("半截帧须 WARN 记帧头声称的长度：" + warn, warn.contains("len=8"));
        assertTrue("半截帧须 WARN 记首字节：" + warn, warn.contains("首字节=0x"));
    }

    /**
     * 行为级：畸形长度帧后**会话真的重连**（不是只抛个异常就没人管）。
     * 旧读序下这里读的是 {@code readUnsignedByte()=0} → 不抛 → 接着读 4 字节长度（数据不够）
     * → 阻塞 1s 读超时 → 静默吞 → 永不重连 → 本用例在 {@code server.accept()} 上超时失败。
     */
    @Test
    public void 畸形长度帧_会话走重连() throws Exception {
        final ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        server.setSoTimeout(8000);
        final Thread loop = new Thread(new SenderLoop(server.getLocalPort()), "sqlvis-agent-frame-test");
        loop.setDaemon(true);
        loop.start();
        Socket first = null;
        Socket second = null;
        try {
            first = server.accept();
            final DataOutputStream out = new DataOutputStream(first.getOutputStream());
            out.writeInt(0);                     // 非法长度
            out.writeByte(EventBus.CMD_CONFIG);  // 旧读序下这一字节会被当成命令
            out.flush();

            second = server.accept();            // 退避 1s 后应重连
            final DataInputStream in2 = new DataInputStream(second.getInputStream());
            assertEquals("重连后应重新握手 HELLO", EventBus.CMD_HELLO, readFrame(in2).cmd);
        } finally {
            closeQuietly(second);
            closeQuietly(first);
            closeQuietly(server);
            loop.interrupt();
            loop.join(3000L);
        }
    }

    // ---------- 辅助 ----------

    private void assertMalformedLenRejected(int len, String expectedInWarn) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(len);
        dos.writeByte(EventBus.CMD_CONFIG);
        dos.flush();

        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream prev = System.err;
        final String warn;
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            try {
                SenderLoop.readInboundFrame(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
                fail("长度非法（" + len + "）应抛 IOException 让会话重连");
            } catch (IOException expected) {
                // 期望
            }
        } finally {
            System.setErr(prev);
            warn = err.toString("UTF-8");
        }
        assertTrue("WARN 须含非法长度（真机排障要按字节比对）：" + warn, warn.contains(expectedInWarn));
        assertTrue("WARN 须含首字节： " + warn, warn.contains("首字节=0x"));
    }

    /** 按 IDE 侧写帧形态造一帧（写用被测代码自身的 writeFrame，形态即契约）。 */
    private static DataInputStream frameStream(int cmd, String body) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        SenderLoop.writeFrame(new DataOutputStream(bos), cmd, body);
        return new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
    }

    private static final class Frame {
        final int cmd;
        final String payload;

        Frame(int cmd, String payload) {
            this.cmd = cmd;
            this.payload = payload;
        }
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        final int len = in.readInt();
        final int cmd = in.readUnsignedByte();
        final byte[] body = new byte[len];
        in.readFully(body);
        return new Frame(cmd, new String(body, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** needle 在 haystack 中出现次数（一次性告警断言用）。 */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            count++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return count;
    }
}
