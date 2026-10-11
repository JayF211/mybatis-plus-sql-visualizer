package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.util.StackDigest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-SourceJumpFunnel 单测：CONFIG 帧前缀提取（P1-3）与调用栈快照压缩（P2-2）。
 */
public class SourceJumpFunnelTest {

    // ---------- SenderLoop.extractStringArray ----------

    @Test
    public void 前缀提取_多项与转义() {
        String payload = "{\"disabled\":false,\"desensitize\":true," +
                "\"skipPrefixes\":[\"com.corp.engine.\",\"a\\\"b\\\\c\"]}";
        String[] out = SenderLoop.extractStringArray(payload, "\"skipPrefixes\":");
        assertEquals(2, out.length);
        assertEquals("com.corp.engine.", out[0]);
        assertEquals("a\"b\\c", out[1]);
    }

    @Test
    public void 前缀键缺失_空组() {
        assertEquals(0, SenderLoop.extractStringArray("{\"disabled\":true}", "\"skipPrefixes\":").length);
        assertEquals(0, SenderLoop.extractStringArray("skipPrefixes broken", "\"skipPrefixes\":").length);
        assertEquals(0, SenderLoop.extractStringArray("{\"skipPrefixes\":{}}", "\"skipPrefixes\":").length);
    }

    @Test
    public void 反向名单提取_同扫描器() {
        // T-ToolSiteBizFrame：bizJarPrefixes 与 skipPrefixes 共用同一数组扫描器
        String payload = "{\"disabled\":false,\"desensitize\":false," +
                "\"skipPrefixes\":[\"com.corp.engine.\"],\"bizJarPrefixes\":[\"com.corp.shared.util.\"]}";
        String[] skip = SenderLoop.extractStringArray(payload, "\"skipPrefixes\":");
        String[] biz = SenderLoop.extractStringArray(payload, "\"bizJarPrefixes\":");
        assertEquals(1, skip.length);
        assertEquals("com.corp.engine.", skip[0]);
        assertEquals(1, biz.length);
        assertEquals("com.corp.shared.util.", biz[0]);
        // 两键互不串扰：对 biz 键提取不会误读 skip 数组
        assertEquals(0, SenderLoop.extractStringArray("{\"bizJarPrefixes\":[]}", "\"skipPrefixes\":").length);
    }

    // ---------- StackDigest ----------

    @Test
    public void 栈快照_帧内截断与方向() {
        StackTraceElement[] stack = {
                new StackTraceElement("com.app.XxxService", "getXxxId", "XxxService.java", 36),
                new StackTraceElement("com.app.XxxController", "load", "XxxController.java", 48),
        };
        String s = StackDigest.build(stack);
        assertEquals("com.app.XxxService#getXxxId:36<-com.app.XxxController#load:48", s);
    }

    /**
     * T-StackHoverUi 契约换代（原「上限于十六帧加省略号」改写）：缺省预算 24 下 20 帧链全链保留；
     * 超预算链走两端采样——省略标记在场且段数有界（栈顶 B-8 + 标记 + 栈底 8）。
     */
    @Test
    public void 栈快照_缺省预算内全链_超预算两端采样() {
        // 20 帧 ≤ 缺省 24：全链保留、无标记
        StackTraceElement[] shortChain = new StackTraceElement[20];
        for (int i = 0; i < 20; i++) {
            shortChain[i] = new StackTraceElement("com.app.C" + i, "m" + i, "C" + i + ".java", i);
        }
        String full = StackDigest.build(shortChain);
        assertTrue("预算内不得出省略标记", !full.contains("…"));
        assertEquals(20, full.split("<-").length);

        // 40 帧 > 缺省 24：两端采样，标记在场，段数 = 栈顶 16 + 标记 + 栈底 8 = 25
        StackTraceElement[] longChain = new StackTraceElement[40];
        for (int i = 0; i < 40; i++) {
            longChain[i] = new StackTraceElement("com.app.C" + i, "m" + i, "C" + i + ".java", i);
        }
        String sampled = StackDigest.build(longChain);
        assertTrue("超预算应出省略标记", sampled.contains("中间省略"));
        assertEquals(25, sampled.split("<-").length);
    }

    @Test
    public void 栈快照_空栈为null() {
        assertNull(StackDigest.build(new StackTraceElement[0]));
        assertNull(StackDigest.build(null));
    }
}
