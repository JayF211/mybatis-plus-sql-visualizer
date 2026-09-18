package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.util.StackLocator.Frame;
import cn.song.mybatisplus.sqlvistest.DeepCaller;
import cn.song.mybatisplus.sqlvistest.LocateProbe;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-PS4-09 应用侧栈捕获收敛守卫（七轮审计①②③）：
 * ①一次捕获两用（携栈出口，digest 与 locate 同源）；②缓存查询前移（命中不再全栈建 pool）；
 * ③fat jar 安全网判定缓存（策略 A：身份+校验，不再逐帧 Class.forName）。
 *
 * <p>与 {@link StackLocator} 同包放置，以便访问包内测试通道
 * （{@code jarOriginProbe} 注入口 / {@code clearLocatorCachesForTest} 清场）。
 */
public class StackCaptureConvergenceTest {

    /** 全 JAR 来源探针（fat jar 模拟）：一切类判定为 JAR 来源并计数——计数即「逐帧来源解析」成本代理。 */
    private static final class AllJarProbe implements StackLocator.JarOriginProbe {
        int calls;

        @Override
        public Boolean isFromJar(String className, ClassLoader loader) {
            calls++;
            return Boolean.TRUE;
        }
    }

    private static void clearCaches() {
        StackLocator.clearLocatorCachesForTest();
    }

    // ---------- ① 一次捕获两用：携栈出口 ----------

    @Test
    public void 携栈出口_定位与摘要同源快照() {
        clearCaches();
        final StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", -2),
                new StackTraceElement("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                        "emitEvent", "StatementHandler.java", 124),
                new StackTraceElement("cn.song.mybatisplus.sqlvistest.OrderService", "createOrder",
                        "OrderService.java", 42),
                new StackTraceElement("cn.song.mybatisplus.sqlvistest.OrderController", "submit",
                        "OrderController.java", 17),
        };
        final Frame f = StackLocator.locate(stack, null);
        assertNotNull(f);
        // 定位出自同一数组：主位置 = 首个业务帧（合成类不可加载 → 来源未知 → 安全网兜底取带调试信息帧）
        assertEquals("cn/song/mybatisplus/sqlvistest/OrderService.java", f.sourceFile);
        assertEquals("OrderService#createOrder", f.methodKey);
        assertEquals(42, f.line);
        // 同一数组喂摘要：业务帧在列、agent 帧不过滤（原规则）、自身帧跳过
        final String digest = StackDigest.build(stack);
        assertNotNull(digest);
        assertTrue("摘要应含业务帧（同源快照）",
                digest.contains("cn.song.mybatisplus.sqlvistest.OrderService#createOrder:42"));
        assertTrue("摘要按原规则不过滤 agent 帧", digest.contains("StatementHandler#emitEvent"));
        assertFalse("Thread#getStackTrace 自身帧应跳过", digest.startsWith("java.lang.Thread#getStackTrace"));
    }

    @Test
    public void 携栈出口_与自捕获出口行为等价() {
        clearCaches();
        final Frame self = StackLocator.locate();
        final StackTraceElement[] captured = Thread.currentThread().getStackTrace();
        final Frame carried = StackLocator.locate(captured, null);
        assertNotNull(self);
        assertNotNull(carried);
        // 两次调用同处本测试方法（测试类在 agent 前缀包下，key 落在 JUnit 运行器帧）——同形即等价
        assertEquals(self.methodKey, carried.methodKey);
        assertEquals(self.via, carried.via);
        assertEquals(self.sourceFile, carried.sourceFile);
    }

    // ---------- ② 缓存查询前移：命中行为等价 + 防回潮 ----------

    @Test
    public void 同key二次调用返回同Frame实例_行为等价() {
        clearCaches();
        final Frame first = LocateProbe.locateHere();
        final Frame second = LocateProbe.locateHere();
        assertNotNull(first);
        assertSame("同 key 二次调用应复用缓存整帧（L1 主流路径行为等价）", first, second);
        assertEquals("L1", first.via);
        assertEquals("LocateProbe#locateHere", first.methodKey);
        assertEquals("cn/song/mybatisplus/sqlvistest/LocateProbe.java", first.sourceFile);
    }

    @Test
    public void 缓存命中路径不再全栈建池_深栈计时防回潮() {
        clearCaches();
        final int depth = 2000;
        assertNotNull(DeepCaller.recurse(1));
        // 预热：JIT 编译 + 两路径缓存状态各自就位
        for (int i = 0; i < 3; i++) {
            clearCaches();
            DeepCaller.recurse(depth);
        }
        for (int i = 0; i < 3; i++) {
            DeepCaller.recurse(depth);
        }
        long missMin = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            clearCaches();
            final long t0 = System.nanoTime();
            DeepCaller.recurse(depth);
            missMin = Math.min(missMin, System.nanoTime() - t0);
        }
        long hitMin = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            final long t0 = System.nanoTime();
            DeepCaller.recurse(depth);
            hitMin = Math.min(hitMin, System.nanoTime() - t0);
        }
        // 命中路径 = 前缀扫描到首帧即查缓存返回（O(1)）；miss = 全栈建 pool + 来源解析（O(depth)）。
        // 防回潮：若缓存查询被移回 pool 构建之后，命中路径退化为 O(depth)，比值趋近 1。
        assertTrue("命中应显著快于 miss（hit=" + hitMin + "ns, miss=" + missMin + "ns）",
                hitMin * 3 < missMin);
    }

    // ---------- ③ fat jar 模拟：安全网判定缓存（策略 A） ----------

    @Test
    public void fatjar模拟_判定缓存生效_同key第二次不再逐帧来源解析() {
        clearCaches();
        final AllJarProbe probe = new AllJarProbe();
        StackLocator.jarOriginProbe = probe;
        try {
            final Frame first = LocateProbe.locateHere();
            // 全 JAR 来源（模拟 fat jar）：无 L1 目录帧 → 安全网；首个带调试信息帧 = LocateProbe 自身
            assertEquals("SAFETY", first.via);
            assertEquals("LocateProbe#locateHere", first.methodKey);
            assertEquals("cn/song/mybatisplus/sqlvistest/LocateProbe.java", first.sourceFile);
            final int afterFirst = probe.calls;
            assertTrue("首执行应逐帧做来源解析（pool 全扫）", afterFirst > 0);

            final Frame second = LocateProbe.locateHere();
            assertEquals("第二次执行不得再逐帧来源解析（判定缓存生效，防回潮）", afterFirst, probe.calls);
            // 判定缓存不冻结结果帧：新实例、行号/候选列每执行重建（区别于 L1 整帧缓存的同实例语义）
            assertNotSame(first, second);
            assertEquals("SAFETY", second.via);
            assertEquals(first.methodKey, second.methodKey);
            assertEquals(first.sourceFile, second.sourceFile);
            assertEquals("候选列应逐执行重建且等价（当前栈同形）", first.candidates.size(), second.candidates.size());
        } finally {
            StackLocator.jarOriginProbe = null;
            clearCaches();
        }
    }

    @Test
    public void fatjar判定缓存_身份不在场校验失败回退全量重算() {
        clearCaches();
        final AllJarProbe probe = new AllJarProbe();
        StackLocator.jarOriginProbe = probe;
        try {
            LocateProbe.locateHere();
            final int baseline = probe.calls;
            final String key = "cn.song.mybatisplus.sqlvistest.LocateProbe#locateHere";
            final String[] decision = StackLocator.SAFETY_DECISIONS.get(key);
            assertNotNull(decision);
            assertEquals("SAFETY", decision[0]);
            assertEquals("cn.song.mybatisplus.sqlvistest.LocateProbe", decision[1]);
            assertEquals("locateHere", decision[2]);
            // 篡改主位置身份 → 模拟引擎内部路径变化（身份不在本执行 pool）
            decision[1] = "com.corp.engine.GoneFacade";

            final Frame rebuilt = LocateProbe.locateHere();
            assertTrue("校验失败应回退全量重算（逐帧来源解析重现）", probe.calls > baseline);
            assertEquals("SAFETY", rebuilt.via);
            assertEquals("LocateProbe#locateHere", rebuilt.methodKey);
            // 判定应被刷新回在场身份（自愈）
            assertEquals("cn.song.mybatisplus.sqlvistest.LocateProbe",
                    StackLocator.SAFETY_DECISIONS.get(key)[1]);
        } finally {
            StackLocator.jarOriginProbe = null;
            clearCaches();
        }
    }

    @Test
    public void l1命中清除同key安全网判定_互斥合流() {
        clearCaches();
        StackLocator.jarOriginProbe = new AllJarProbe();
        try {
            LocateProbe.locateHere();   // 全 JAR → SAFETY 判定入缓存
        } finally {
            StackLocator.jarOriginProbe = null;
        }
        final String key = "cn.song.mybatisplus.sqlvistest.LocateProbe#locateHere";
        assertNotNull(StackLocator.SAFETY_DECISIONS.get(key));
        // 篡改身份强制校验失败 → 全量路径 → 真实环境（测试产物为目录）L1 命中
        StackLocator.SAFETY_DECISIONS.get(key)[1] = "com.corp.engine.GoneFacade";
        final Frame f = LocateProbe.locateHere();
        assertEquals("L1", f.via);
        assertNull("L1 命中后同 key 的 SAFETY 判定应被清除（两缓存互斥合流）",
                StackLocator.SAFETY_DECISIONS.get(key));
        clearCaches();
    }

    @Test
    public void 判定缓存命中不重扫来源_身份在场即复用() {
        // 文档化已知残面（见 StackLocator.writeCaches 注释）：判定命中且身份在场时不重扫来源，
        // 来源环境从全 JAR 切回目录后的首个执行仍走判定缓存——候选列每执行重建可兜底，属可恢复降级。
        clearCaches();
        StackLocator.jarOriginProbe = new AllJarProbe();
        try {
            LocateProbe.locateHere();
        } finally {
            StackLocator.jarOriginProbe = null;
        }
        final Frame masked = LocateProbe.locateHere();
        assertEquals("SAFETY", masked.via);
        assertEquals("LocateProbe#locateHere", masked.methodKey);
        clearCaches();
    }
}
