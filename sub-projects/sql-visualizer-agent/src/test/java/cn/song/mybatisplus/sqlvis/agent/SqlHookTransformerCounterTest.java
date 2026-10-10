package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * T-AttachZeroEvent 守卫（2026-10-09 现场复盘）：transform 层四计数（matched/woven/noChange/failed）。
 *
 * 现场教训：attach 后零事件时，transform 的两种静默形态——织入异常吞掉返回 null、名单命中但织入器
 * 产出原字节——此前**零观测出口**，「retransform 成功」与「真的织上了」无法区分。本守卫锁定：
 * <ul>
 *   <li>名单命中且织入产出原字节 ⇒ woven +1（真织上）；</li>
 *   <li>名单命中但无方法匹配（产出原字节）⇒ noChange +1（无钩子，可观测）；</li>
 *   <li>织入抛异常（垃圾字节）⇒ failed +1 且返回 null（不外溢）；</li>
 *   <li>名单外类 ⇒ 四计数全不动（transform 对非登记类零成本直返 null）。</li>
 * </ul>
 * 织入目标按**登记名**分派（DriverRegistry/StatementWeaver.SPECS），夹具字节可挂任意登记名——
 * 织入器只按方法名+描述符匹配，不校验类名与字节内部名一致（attach 取证测试据此用真实驱动名单）。
 */
public class SqlHookTransformerCounterTest {

    private final SqlHookTransformer transformer = new SqlHookTransformer();

    @Before
    public void resetCounters() {
        SqlHookTransformer.resetCountersForTest();
    }

    @Test
    public void 名单命中且织入产出原字节_woven计数() throws Exception {
        byte[] bytes = resourceBytes("FakeVendorFixtures$VendorConn.class");
        byte[] out = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcConnection", null, null, bytes);
        assertEquals(1L, SqlHookTransformer.weavableCount());
        assertEquals(1L, SqlHookTransformer.wovenCount());
        assertEquals(0L, SqlHookTransformer.noChangeCount());
        assertEquals(0L, SqlHookTransformer.failedCount());
        assertSame(bytes.getClass(), out.getClass());
        assertEquals(true, out != bytes);
    }

    @Test
    public void 名单命中但无方法可织_noChange计数() throws Exception {
        // BoomException 无任何 execute/prepare 方法——语句面 spec 命中后零插桩产出原字节
        byte[] bytes = resourceBytes("FakeVendorFixtures$BoomException.class");
        byte[] out = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcStatement", null, null, bytes);
        assertEquals(1L, SqlHookTransformer.weavableCount());
        assertEquals(0L, SqlHookTransformer.wovenCount());
        assertEquals(1L, SqlHookTransformer.noChangeCount());
        assertEquals(0L, SqlHookTransformer.failedCount());
        assertSame("无钩子形态必须原字节返回（调用方 JVM 语义不变）", bytes, out);
    }

    @Test
    public void 织入抛异常_failed计数且返回null不外溢() throws Exception {
        byte[] garbage = new byte[]{0x00, 0x01, 0x02};
        byte[] out = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcStatement", null, null, garbage);
        assertNull("异常必须收敛为 null（类按原字节装载），绝不外溢", out);
        assertEquals(1L, SqlHookTransformer.weavableCount());
        assertEquals(0L, SqlHookTransformer.wovenCount());
        assertEquals(1L, SqlHookTransformer.failedCount());
    }

    @Test
    public void 名单外类_计数全不动() throws Exception {
        byte[] bytes = resourceBytes("FakeVendorFixtures$BoomException.class");
        byte[] out = transformer.transform(getClass().getClassLoader(),
                "java/lang/Object", null, null, bytes);
        assertNull(out);
        assertEquals(0L, SqlHookTransformer.weavableCount());
        assertEquals(0L, SqlHookTransformer.wovenCount());
        assertEquals(0L, SqlHookTransformer.noChangeCount());
        assertEquals(0L, SqlHookTransformer.failedCount());
    }

    private static byte[] resourceBytes(String simpleName) throws Exception {
        String path = "/cn/song/mybatisplus/sqlvis/agent/" + simpleName;
        InputStream in = SqlHookTransformerCounterTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("夹具字节缺失: " + path);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
