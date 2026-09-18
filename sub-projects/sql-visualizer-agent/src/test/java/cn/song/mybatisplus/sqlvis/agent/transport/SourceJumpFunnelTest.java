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

    // ---------- SenderLoop.extractSkipPrefixes ----------

    @Test
    public void 前缀提取_多项与转义() {
        String payload = "{\"disabled\":false,\"desensitize\":true," +
                "\"skipPrefixes\":[\"com.corp.engine.\",\"a\\\"b\\\\c\"]}";
        String[] out = SenderLoop.extractSkipPrefixes(payload);
        assertEquals(2, out.length);
        assertEquals("com.corp.engine.", out[0]);
        assertEquals("a\"b\\c", out[1]);
    }

    @Test
    public void 前缀键缺失_空组() {
        assertEquals(0, SenderLoop.extractSkipPrefixes("{\"disabled\":true}").length);
        assertEquals(0, SenderLoop.extractSkipPrefixes("skipPrefixes broken").length);
        assertEquals(0, SenderLoop.extractSkipPrefixes("{\"skipPrefixes\":{}}").length);
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
