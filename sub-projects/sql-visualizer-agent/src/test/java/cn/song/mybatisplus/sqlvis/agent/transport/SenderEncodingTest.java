package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.util.Jsons;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Inspect the actual compiled successful send path, not a fabricated source pattern. */
public class SenderEncodingTest {
    @Test public void sqlSendPathConvertsPayloadToUtf8ExactlyOnce() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = SenderLoop.class.getResourceAsStream("SenderLoop.class")) {
            new ClassReader(in).accept(node, 0);
        }
        MethodNode send = node.methods.stream().filter(m -> m.name.equals("sendEvent")).findFirst().get();
        assertEquals("Successful SQL path must reuse the size-checked byte array", 1, conversions(node, send));
    }
    @Test public void exactUtf8ByteLimitAcceptedAndOneByteMoreRejected() throws Exception {
        SqlEvent event = new SqlEvent(); event.sql = "";
        int overhead = Jsons.writeEvent(event).getBytes(StandardCharsets.UTF_8).length;
        int length = SenderLoop.MAX_FRAME_LEN - overhead;
        StringBuilder text = new StringBuilder(length);
        // Include a multibyte character: the limit must remain UTF-8 bytes, not Java chars.
        text.append('\u4e2d');
        for (int i = 3; i < length; i++) text.append('x');
        event.sql = text.toString();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(SenderLoop.sendEvent(new DataOutputStream(bytes), event));
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(SenderLoop.MAX_FRAME_LEN, input.readInt());
        assertEquals(EventBus.CMD_EVENT, input.readUnsignedByte());
        byte[] body = new byte[SenderLoop.MAX_FRAME_LEN]; input.readFully(body);
        assertArrayEquals(Jsons.writeEvent(event).getBytes(StandardCharsets.UTF_8), body);
        assertEquals(-1, input.read());
        event.sql += "x"; bytes.reset();
        assertFalse(SenderLoop.sendEvent(new DataOutputStream(bytes), event));
        assertEquals(0, bytes.size());
    }

    private int conversions(ClassNode owner, MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("java/lang/String") && call.name.equals("getBytes")) count++;
            if (call.owner.equals(owner.name) && call.name.equals("writeFrame")) {
                MethodNode target = owner.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().get();
                count += conversions(owner, target);
            }
        }
        return count;
    }
    @Test public void unicodeNullAndIdentityWireBytesRemainExactlyTheSame() throws Exception {
        for (String sql : new String[]{null, "select 1", "select '\u4e2d\u6587\ud83d\ude00'", "select '\"\\\n'", "select '\ud800'", "select '\udc00'"}) {
            SqlEvent event = new SqlEvent(); event.sql = sql; event.runId = "run"; event.executionId = "run:1";
            event.sequence = 1L; event.requestId = "request"; event.errorText = null;
            byte[] payload = Jsons.writeEvent(event).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            DataOutputStream reference = new DataOutputStream(expected);
            reference.writeInt(payload.length); reference.writeByte(EventBus.CMD_EVENT); reference.write(payload);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            assertTrue(SenderLoop.sendEvent(new DataOutputStream(actual), event));
            assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        }
    }
}
