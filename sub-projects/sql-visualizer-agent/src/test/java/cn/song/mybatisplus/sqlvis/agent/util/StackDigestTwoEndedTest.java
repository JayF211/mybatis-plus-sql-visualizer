package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-StackHoverUi：StackDigest 两端采样守卫（A 案＋帧数可配置）。
 *
 * 背景：旧 16 帧顶格预算自栈顶（JDBC 执行点）取帧，JdbcTemplate 深封装链下在 spring-jdbc
 * 内部耗尽、够不着栈底方向的 controller（用户真机实测）。现形态：预算 B=[AgentState] 可配
 * （钳 [12,64]），链长 ≤B 全链保留；&gt;B 时栈顶 B-8 帧＋中段省略标记＋栈底固定 8 帧。
 *
 * 覆盖：全链无标记、两端采样分段与帧内容、标记段无 #（IDE 解析器跳过兼容）、钳位、
 * AgentState 配置路径、超长缩栈顶（业务帧永不因长度被截）、边界（恰等/B+1）、空栈。
 */
public class StackDigestTwoEndedTest {

    private final StringBuilder frameText = new StringBuilder();

    private StackTraceElement f(String cls, String method, int line) {
        return new StackTraceElement(cls, method, cls.replace('.', '/') + ".java", line);
    }

    /** 造 frames[i] = com.test.C0#m0:0 .. C{n-1}#m{n-1}（栈顶在 [0]，无 Thread 自身帧）。 */
    private StackTraceElement[] chain(int n) {
        StackTraceElement[] s = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            s[i] = f("com.test.C" + i, "m" + i, i);
        }
        return s;
    }

    @After
    public void restoreBudget() {
        // AgentState 为进程级静态态：恢复缺省，避免污染同 JVM 内其他用例
        AgentState.setStackMaxFrames(24);
    }

    @Test
    public void 链长不超预算_全链保留无标记() {
        String d = StackDigest.build(chain(20), 24);
        assertFalse("≤预算不得出省略标记", d.contains("中间省略"));
        assertEquals(20, d.split("<-").length);
        assertTrue("栈顶在左", d.startsWith("com.test.C0#m0:0"));
        assertTrue("栈底在右", d.endsWith("com.test.C19#m19:19"));
    }

    @Test
    public void 链长超预算_两端采样含标记() {
        String d = StackDigest.build(chain(40), 24);
        String[] segs = d.split("<-");
        // 栈顶 B-8=16 帧 + 标记 1 段 + 栈底 8 帧 = 25 段
        assertEquals(25, segs.length);
        assertEquals("栈顶保留前 16 帧", "com.test.C0#m0:0", segs[0]);
        assertEquals("栈顶第 16 帧", "com.test.C15#m15:15", segs[15]);
        assertTrue("标记段形如 <…中间省略 N 帧…>", segs[16].startsWith("<…中间省略 ") && segs[16].endsWith(" 帧…>"));
        assertEquals("省略数 = 40-16-8", "<…中间省略 16 帧…>", segs[16]);
        assertEquals("栈底第 1 帧", "com.test.C32#m32:32", segs[17]);
        assertEquals("栈底末帧（业务侧）", "com.test.C39#m39:39", segs[24]);
        // 标记段无 #：IDE 侧解析器按不可解析段跳过（弹层兼容判据）
        assertFalse(segs[16].contains("#"));
    }

    @Test
    public void 边界_恰等预算无标记_超一帧出标记() {
        String at24 = StackDigest.build(chain(24), 24);
        assertFalse(at24.contains("中间省略"));
        String at25 = StackDigest.build(chain(25), 24);
        assertTrue(at25.contains("<…中间省略 1 帧…>"));
    }

    @Test
    public void 预算钳位_下限12上限64() {
        // 20 帧链、预算钳到 12：栈顶 4 帧 + 标记 + 栈底 8 帧 = 13 段
        String low = StackDigest.build(chain(20), 4);
        String[] lowSegs = low.split("<-");
        assertEquals(13, lowSegs.length);
        assertEquals("com.test.C3#m3:3", lowSegs[3]);
        assertEquals("标记段在栈顶之后", "<…中间省略 8 帧…>", lowSegs[4]);
        assertEquals("栈底首帧", "com.test.C12#m12:12", lowSegs[5]);
        // 100 帧链、预算 999 钳到 64：栈顶 56 + 标记 + 栈底 8 = 65 段
        String high = StackDigest.build(chain(100), 999);
        assertEquals(65, high.split("<-").length);
        assertEquals("com.test.C55#m55:55", high.split("<-")[55]);
    }

    @Test
    public void agentState配置路径_无参build读预算() {
        AgentState.setStackMaxFrames(20);
        String d = StackDigest.build(chain(40));
        String[] segs = d.split("<-");
        assertEquals(21, segs.length);
        assertEquals("com.test.C11#m11:11", segs[11]);
        assertEquals("<…中间省略 20 帧…>", segs[12]);
    }

    @Test
    public void agentState写入钳位() {
        AgentState.setStackMaxFrames(2);
        assertEquals(12, AgentState.getStackMaxFrames());
        AgentState.setStackMaxFrames(999);
        assertEquals(64, AgentState.getStackMaxFrames());
    }

    @Test
    public void 超长缩栈顶_栈底业务帧永不因长度被截() {
        // 长类名帧：预算 24 → maxLen=2304；top16+marker+bottom8 必然超长 → 触发缩顶循环
        StackTraceElement[] s = new StackTraceElement[60];
        for (int i = 0; i < 60; i++) {
            StringBuilder pkg = new StringBuilder("com.test.");
            while (pkg.length() < 120) {
                pkg.append('x');
            }
            s[i] = f(pkg + ".C" + i, "m" + i, i);
        }
        String d = StackDigest.build(s, 24);
        assertTrue("总长不超硬顶", d.length() <= 2304);
        assertTrue("标记在场", d.contains("中间省略"));
        // 栈底业务帧（C59）必须完整在串尾——不得被长度截断（fqcn = com.test. + 111 个 x + .C59）
        assertTrue("业务帧完整保留", d.endsWith("com.test." + "x".repeat(111) + ".C59#m59:59"));
        // 标记里的省略数应大于基础值 16（说明顶帧被缩进了省略段，而非砍掉栈底）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("中间省略 (\\d+) 帧").matcher(d);
        assertTrue(m.find());
        assertTrue("缩顶后省略数 ≥16，实际 " + m.group(1), Integer.parseInt(m.group(1)) >= 16);
    }

    @Test
    public void 空栈与null_返回null() {
        assertNull(StackDigest.build(null, 24));
        assertNull(StackDigest.build(new StackTraceElement[0], 24));
    }

    // ---------- T-StackHoverUi 二轮：栈底锚点跳过尾部基础设施帧 ----------

    /** 用户真机链形态：20 个业务帧 + Tomcat/线程池尾巴 6 帧。 */
    private StackTraceElement[] chainWithTomcatTail(int business, int infraTail) {
        StackTraceElement[] s = new StackTraceElement[business + infraTail];
        for (int i = 0; i < business; i++) {
            s[i] = f("com.test.C" + i, "m" + i, i);
        }
        String[] infra = {
            "org.apache.coyote.AbstractProcessorLight", "org.apache.coyote.AbstractProtocol$ConnectionHandler",
            "org.apache.tomcat.util.net.NioEndpoint$SocketProcessor", "org.apache.tomcat.util.threads.ThreadPoolExecutor",
            "java.lang.Thread", "java.util.concurrent.ThreadPoolExecutor",
        };
        for (int i = 0; i < infraTail; i++) {
            s[business + i] = f(infra[i % infra.length], "run", 100 + i);
        }
        return s;
    }

    @Test
    public void 跳过尾部基础设施_业务深度在预算内_全链保留且无容器帧() {
        // 20 业务帧（预算 24）+ 6 帧 Tomcat 尾巴：字面链长 26 > 24，但业务深度 20 ≤ 24
        String d = StackDigest.build(chainWithTomcatTail(20, 6), 24);
        assertFalse("业务深度在预算内不出标记", d.contains("中间省略"));
        assertFalse("容器尾巴不得出现在快照", d.contains("tomcat") || d.contains("java.lang.Thread"));
        assertTrue("栈底=最深业务帧", d.endsWith("com.test.C19#m19:19"));
        assertEquals(20, d.split("<-").length);
    }

    @Test
    public void 跳过尾部基础设施_超预算_栈底锚定最深业务帧() {
        // 30 业务帧（预算 24，栈顶 16）+ 6 帧 Tomcat 尾巴：
        // 栈底 = 最深业务帧 C29 起向上 8 帧（C22..C29），省略 = C16..C21 共 6 帧
        String d = StackDigest.build(chainWithTomcatTail(30, 6), 24);
        String[] segs = d.split("<-");
        assertEquals(25, segs.length);
        assertEquals("com.test.C15#m15:15", segs[15]);
        assertEquals("<…中间省略 6 帧…>", segs[16]);
        assertEquals("栈底首帧", "com.test.C22#m22:22", segs[17]);
        assertEquals("栈底=最深业务帧（Controller 位）", "com.test.C29#m29:29", segs[24]);
        assertFalse("容器帧不进快照", d.contains("tomcat"));
    }

    @Test
    public void 全基础设施链_兜底退回字面两端() {
        StackTraceElement[] s = new StackTraceElement[20];
        for (int i = 0; i < 20; i++) {
            s[i] = f("java.util.X" + i, "run", i);
        }
        String d = StackDigest.build(s, 12);
        // 全 infra：lastBusiness 兜底回末帧，字面两端（栈顶 4 + 标记 + 栈底 8）
        assertEquals(13, d.split("<-").length);
        assertTrue(d.endsWith("java.util.X19#run:19"));
    }
}
