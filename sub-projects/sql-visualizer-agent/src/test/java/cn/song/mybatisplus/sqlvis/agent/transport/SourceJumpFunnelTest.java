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

    @Test
    public void 栈快照_上限于十六帧加省略号() {
        StackTraceElement[] stack = new StackTraceElement[20];
        for (int i = 0; i < 20; i++) {
            stack[i] = new StackTraceElement("com.app.C" + i, "m" + i, "C" + i + ".java", i);
        }
        String s = StackDigest.build(stack);
        assertTrue(s.contains("…"));
        assertTrue("帧数应截断到 16", s.split("<-").length <= 16);
    }

    @Test
    public void 栈快照_空栈为null() {
        assertNull(StackDigest.build(new StackTraceElement[0]));
        assertNull(StackDigest.build(null));
    }
}
